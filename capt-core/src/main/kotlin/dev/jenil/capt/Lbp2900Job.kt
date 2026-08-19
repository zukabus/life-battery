package dev.jenil.capt

import java.util.Calendar

/**
 * The CAPT print-job state machine for the LBP2900 (and the LBP3000, which
 * shares everything but its job prologue).
 *
 * Port of captdriver's `src/prn_lbp2900.c` plus the driving loop in
 * `src/rastertocapt.c`. (C) 2013 Alexey Galakhov, (C) 2016 Alexei Gordeev,
 * GPLv3.
 *
 * A job runs as:
 *
 *     jobPrologue()
 *       for each page:  pagePrologue() -> sendBands() -> pageEpilogue()
 *     jobEpilogue()
 *
 * Pages are compressed up front by [PageSource] and only then sent, because
 * the printer will not tolerate long gaps in the middle of a page's data.
 *
 * Everything blocks, so run this off the main thread. [sleeper] is injected
 * so tests can drive the whole sequence without waiting on real seconds.
 */
class Lbp2900Job(
    private val session: CaptSession,
    private val geometry: PageGeometry = PageGeometry.A4,
    private val options: PageOptions = PageOptions(),
    private val logger: CaptLogger = CaptLogger.NONE,
    private val sleeper: Sleeper = Sleeper.REAL,
    private val clock: () -> Calendar = { Calendar.getInstance() },
) {
    /** Supplies already-compressed bands, one page at a time. */
    interface PageSource {
        /** Total pages in this job. */
        val pageCount: Int

        /**
         * Compressed bands for page [index] (0-based), in top-to-bottom
         * order, each already HiSCoA-encoded for [geometry].
         */
        fun bandsForPage(index: Int): List<ByteArray>
    }

    private val status = CaptStatus()
    private var jobId = 0
    private var sendCounter = 0

    /** Raised when the printer runs out of paper mid-page. */
    class OutOfPaper : Exception("printer is out of paper")

    // ---- magic byte blobs, verbatim from upstream -------------------------

    private val magicBuf0 = byteArrayOf(0x00, 0x00, 0x1E, 0x00, 0x00, 0x00, 0x00, 0x00)

    private val magicBuf2 = byteArrayOf(
        0xEE.toByte(), 0xDB.toByte(), 0xEA.toByte(), 0xAD.toByte(),
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    )

    /**
     * Sent during the job prologue. Upstream's LBP2900 path sends the
     * *LBP3010* 16-byte init blob here, not the 12-byte LBP2900 one it
     * declares elsewhere — an inconsistency in the driver that nonetheless
     * describes a working printer, so it is reproduced exactly.
     */
    private val gpioInitJob = ByteArray(16)

    /** The 12-byte LBP2900 blob, used for cancel cleanup. */
    private val gpio2900Init = ByteArray(12)

    /** Blinks the ready LED while waiting for the user (manual duplex). */
    private val gpio2900Blink = byteArrayOf(
        0x00, 0x00, 0x01, 0x02, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00,
    )

    // ---- public entry point ----------------------------------------------

    /**
     * Runs a complete job. Returns normally once the last sheet has landed
     * in the output tray.
     */
    fun print(source: PageSource) {
        require(source.pageCount > 0) { "nothing to print" }
        var inJob = false
        try {
            var page = 0
            while (page < source.pageCount) {
                val bands = source.bandsForPage(page)

                if (!inJob) {
                    jobPrologue()
                    inJob = true
                }

                logger.log("--- page ${page + 1} of ${source.pageCount} ---")
                if (options.manualDuplex && page > 0) {
                    logger.log("manual duplex: reload the paper, then press the printer button")
                    waitForUser()
                }

                if (!pagePrologue()) {
                    logger.log("printer refused the page; waiting for user, then restarting the job")
                    waitForUser()
                    jobEpilogue()
                    inJob = false
                    continue   // same page again
                }

                sendBands(bands)

                if (!pageEpilogue()) {
                    logger.log("page did not print; waiting for user, then retrying")
                    waitForUser()
                    continue   // same page again
                }
                page++
            }
        } finally {
            if (inJob) {
                runCatching { jobEpilogue() }
                    .onFailure { logger.log("job epilogue failed: ${it.message}") }
            }
        }
    }

    /** Best-effort tidy-up after an aborted job. */
    fun cancel() {
        runCatching {
            getXStatus()
            session.sendRecv(CaptCommand.GPIO, gpio2900Init)
            sendJobStart(4, status.pageCompleted)
            session.sendRecv(CaptCommand.JOB_END, u16(jobId))
        }.onFailure { logger.log("cancel cleanup failed: ${it.message}") }
    }

    /**
     * Confirms the attached device is a model this state machine drives.
     * Parses the MDL/MODEL field out of the IEEE 1284 device ID.
     */
    fun detectModel(): String {
        val id = session.deviceId()
        logger.log("device ID: $id")
        for (field in id.trim().split(';')) {
            val idx = field.indexOf(':')
            if (idx <= 0) continue
            val key = field.substring(0, idx).trim()
            if (key == "MDL" || key == "MODEL") return field.substring(idx + 1).trim()
        }
        throw CaptException("no model field in device ID: $id")
    }

    // ---- job level --------------------------------------------------------

    private fun jobPrologue() {
        logger.log("=== job prologue ===")
        session.sendRecv(CaptCommand.IDENT)
        sleeper.sleep(1000)
        status.reset()
        getXStatus()

        session.sendRecv(CaptCommand.START_0)
        val reply = session.sendRecv(CaptCommand.JOB_BEGIN, magicBuf0)
        if (reply.size < 4) throw CaptException("short JOB_BEGIN reply (${reply.size}B)")
        jobId = word(reply[2], reply[3])
        logger.log("job id = $jobId")

        session.sendRecv(CaptCommand.GPIO, gpioInitJob)
        waitReady()

        sendJobStart(1, 0)
        waitReady()
    }

    private fun jobEpilogue() {
        logger.log("=== job epilogue ===")
        while (true) {
            getXStatus()
            if (status.pageCompleted == status.pageDecoding) {
                sendJobStart(4, status.pageCompleted)
                break
            }
            sleeper.sleep(1000)
        }
        session.sendRecv(CaptCommand.JOB_END, u16(jobId))
    }

    /**
     * The job-control record. [flag] selects what it means:
     * 1 = job start, 2 = page ready to fire, 4 = job finished,
     * 6 = page released. [page] is the page counter it refers to.
     */
    private fun sendJobStart(flag: Int, page: Int) {
        val now = clock()
        val year = now.get(Calendar.YEAR) - 1900
        val buf = ByteArray(72)   // 32-byte head + 40 zero bytes
        buf[0] = 0; buf[1] = 0; buf[2] = 0; buf[3] = 0
        buf[4] = (page and 0xFF).toByte()
        buf[5] = ((page shr 8) and 0xFF).toByte()
        // [6..15] host/user/document name lengths, all zero: we send no names
        buf[16] = flag.toByte()
        buf[17] = 0x01
        buf[18] = (jobId and 0xFF).toByte()
        buf[19] = ((jobId shr 8) and 0xFF).toByte()
        // Timezone offsets in minutes, hardcoded upstream as -60 and -120.
        // The printer only uses these for its own job log, so they are
        // harmless; kept identical rather than localised.
        buf[20] = 0xC4.toByte(); buf[21] = 0xFF.toByte()
        buf[22] = 0x88.toByte(); buf[23] = 0xFF.toByte()
        buf[24] = (year and 0xFF).toByte()
        buf[25] = ((year shr 8) and 0xFF).toByte()
        buf[26] = now.get(Calendar.MONTH).toByte()             // 0-based, as tm_mon
        buf[27] = now.get(Calendar.DAY_OF_MONTH).toByte()
        buf[28] = now.get(Calendar.HOUR_OF_DAY).toByte()
        buf[29] = now.get(Calendar.MINUTE).toByte()
        buf[30] = now.get(Calendar.SECOND).toByte()
        buf[31] = 0x01
        session.sendRecv(CaptCommand.JOB_SETUP, buf)
    }

    // ---- page level -------------------------------------------------------

    /** @return false if the printer would not accept the page. */
    private fun pagePrologue(): Boolean {
        getXStatus()
        if (status.flag(CaptFlag.UNINIT1) || status.flag(CaptFlag.UNINIT2)) {
            logger.log("printer uninitialised; running the warm-up sequence")
            session.sendRecv(CaptCommand.START_1)
            session.sendRecv(CaptCommand.START_2)
            session.sendRecv(CaptCommand.START_3)
            waitReady()
            session.sendRecv(CaptCommand.UPLOAD_2, magicBuf2)
            waitReady()
        }

        while (true) {
            getXStatus()
            if (!status.flag(CaptFlag.BUFFERFULL)) break
            logger.log("printer buffer full; waiting")
            sleeper.sleep(1000)
        }

        session.sendMulti(CaptCommand.SET_PARMS) {
            add(CaptCommand.SET_PARM_PAGE, pageParams())
            add(CaptCommand.SET_PARM_HISCOA, HiScoa.formatParams())
            add(CaptCommand.SET_PARM_1)
            add(CaptCommand.SET_PARM_2)
        }
        return true
    }

    /** The 40-byte page parameter block. */
    private fun pageParams(): ByteArray {
        val g = geometry
        val o = options
        val b = ByteArray(40)
        b[0] = 0x00; b[1] = 0x00; b[2] = 0x30; b[3] = 0x2A
        b[4] = g.sizeCode.toByte()
        b[5] = 0x00; b[6] = 0x00; b[7] = 0x00
        b[8] = ((o.density shl 2) and 0xFF).toByte()
        b[9] = 0x1C; b[10] = 0x1C; b[11] = 0x1C
        b[12] = o.mediaType.toByte()
        b[13] = o.mediaAdapt.toByte()
        b[14] = 0x04; b[15] = 0x00
        b[16] = 0x01; b[17] = 0x01
        b[18] = 0x02                                  // automatic image refinement
        b[19] = o.tonerSave.toByte()
        b[20] = 0x00; b[21] = 0x00
        putLe16(b, 22, g.marginHeight)
        putLe16(b, 24, g.marginWidth)
        putLe16(b, 26, g.lineSize)
        putLe16(b, 28, g.numLines)
        putLe16(b, 30, g.paperWidth)
        putLe16(b, 32, g.paperHeight)
        b[34] = 0x00; b[35] = 0x00
        b[36] = o.fuserMode.toByte()
        b[37] = 0x00; b[38] = 0x00; b[39] = 0x00
        return b
    }

    /**
     * Streams the page's compressed bands. Data is chunked to 0xFF00 bytes,
     * and every sixteenth chunk we stop to let the printer catch up —
     * without that pause it overruns and drops data.
     */
    private fun sendBands(bands: List<ByteArray>) {
        sendCounter = 0
        var total = 0
        for (band in bands) {
            var offset = 0
            var remaining = band.size
            while (remaining > 0) {
                val n = if (remaining > 0xFF00) 0xFF00 else remaining
                sendCounter++
                if (sendCounter % 16 == 0) waitReady()
                session.send(CaptCommand.PRINT_DATA, band, offset, n)
                offset += n
                remaining -= n
                total += n
            }
        }
        logger.log("sent ${bands.size} bands, $total compressed bytes")
    }

    /** @return false if the sheet did not make it out (e.g. no paper). */
    private fun pageEpilogue(): Boolean {
        session.send(CaptCommand.PRINT_DATA_END)

        // Wait for the printer to acknowledge it has the whole page.
        while (true) {
            sleeper.sleep(1000)
            getXStatus()
            if (status.pageReceived == status.pageDecoding) break
        }

        val page = status.pageDecoding
        sendJobStart(2, page)
        waitReady()

        session.sendRecv(CaptCommand.FIRE, u16(page))
        waitReady()

        sendJobStart(6, page)

        while (true) {
            getXStatus()
            // Upstream note: comparing pagePrinting here instead of pageOut
            // produces a vertically shifted print. Do not "simplify" this.
            if (status.pageOut == page) return true
            if (status.flag(CaptFlag.NOPAPER2) || status.flag(CaptFlag.NOPAPER1)) {
                if (status.flag(CaptFlag.PRINTING) || status.flag(CaptFlag.PROCESSING1)) continue
                logger.log("out of paper")
                return false
            }
            sleeper.sleep(1000)
        }
    }

    /** Blinks the LED and blocks until the user presses the printer button. */
    private fun waitForUser() {
        session.sendRecv(CaptCommand.GPIO, gpio2900Blink)
        waitReady()
        while (true) {
            getXStatus()
            if (status.flag(CaptFlag.BUTTON)) break
            sleeper.sleep(1000)
        }
        session.sendRecv(CaptCommand.GPIO, gpio2900Init)
        waitReady()
    }

    // ---- status polling ---------------------------------------------------

    private fun downloadStatus(cmd: Int) {
        status.decode(session.sendRecv(cmd))
    }

    private fun getStatus(): CaptStatus {
        downloadStatus(CaptCommand.CHKSTATUS)
        return status
    }

    private fun getXStatus(): CaptStatus {
        downloadStatus(CaptCommand.CHKSTATUS)
        if (status.flag(CaptFlag.XSTATUS_CHNG)) {
            downloadStatus(CaptCommand.CHKXSTATUS)
            logger.log("status: ${status.summary()}")
        }
        return status
    }

    private fun waitReady() {
        while (getStatus().flag(CaptFlag.BUSY)) {
            sleeper.sleep(1000)
        }
    }

    // ---- helpers ----------------------------------------------------------

    private fun u16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    private fun putLe16(b: ByteArray, at: Int, v: Int) {
        b[at] = (v and 0xFF).toByte()
        b[at + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun word(lo: Byte, hi: Byte) =
        ((hi.toInt() and 0xFF) shl 8) or (lo.toInt() and 0xFF)
}
