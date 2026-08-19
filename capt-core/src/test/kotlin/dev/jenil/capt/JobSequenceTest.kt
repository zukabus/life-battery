package dev.jenil.capt

import java.util.Calendar
import java.util.TimeZone

/**
 * Drives the whole job state machine against a scripted fake printer.
 *
 * This cannot prove the real LBP2900 is happy — only the hardware can do
 * that. What it does prove is that the command ORDER, the framing, the
 * status parsing and the page-parameter bytes are what the C driver would
 * have produced, which is where transcription errors hide.
 */

private class FakePrinter(
    private val model: String = "LBP2900",
    /** Report the printer as needing warm-up on the first page. */
    private var uninitialised: Boolean = true,
) : CaptTransport {

    val commandLog = mutableListOf<Pair<Int, ByteArray>>()

    private var status0 = XSTATUS_CHNG
    private var pageDecoding = 0
    private var pageReceived = 0
    private var pageOut = 0
    private var pageCompleted = 0
    private var printDataBytes = 0

    private val pending = ArrayDeque<Byte>()

    override fun deviceId() = "MFG:Canon;CMD:CAPT;MDL:$model;CLS:PRINTER;"

    override fun write(data: ByteArray, offset: Int, length: Int) {
        // Frames can be split across writes by the 4096-byte chunking, so
        // buffer and parse whatever complete frames have arrived.
        outBuf.addAll(data.slice(offset until offset + length))
        parseFrames()
    }

    private val outBuf = ArrayDeque<Byte>()

    private fun parseFrames() {
        while (outBuf.size >= 4) {
            val b = outBuf.toList()
            val cmd = (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
            val len = (b[2].toInt() and 0xFF) or ((b[3].toInt() and 0xFF) shl 8)
            if (len < 4 || outBuf.size < len) return
            val payload = ByteArray(len - 4) { b[4 + it] }
            repeat(len) { outBuf.removeFirst() }
            handle(cmd, payload)
        }
    }

    private fun handle(cmd: Int, payload: ByteArray) {
        commandLog += cmd to payload

        when (cmd) {
            // Send-only commands: no reply frame.
            CaptCommand.PRINT_DATA -> { printDataBytes += payload.size; return }
            CaptCommand.PRINT_DATA_END -> {
                pageDecoding += 1
                pageReceived = pageDecoding
                return
            }
            CaptCommand.SET_PARMS -> return

            CaptCommand.CHKSTATUS -> { replyShortStatus(); return }
            CaptCommand.CHKXSTATUS -> { replyLongStatus(); return }

            CaptCommand.JOB_BEGIN -> {
                // Reply payload: 4 bytes, job id in bytes 2..3.
                reply(cmd, byteArrayOf(0x00, 0x00, 0x2A, 0x00))
                return
            }
            CaptCommand.FIRE -> {
                pageOut = pageDecoding
                pageCompleted = pageDecoding
                reply(cmd, ByteArray(2))
                return
            }
            CaptCommand.START_1, CaptCommand.START_2, CaptCommand.START_3,
            CaptCommand.UPLOAD_2 -> {
                uninitialised = false
                reply(cmd, ByteArray(2))
                return
            }
            else -> { reply(cmd, ByteArray(2)); return }
        }
    }

    private fun replyShortStatus() {
        var s = status0
        if (uninitialised) s = s or UNINIT1 else s = s and UNINIT1.inv()
        reply(CaptCommand.CHKSTATUS, le16(s))
    }

    private fun replyLongStatus() {
        val p = ByteArray(40)
        var s = status0
        if (uninitialised) s = s or UNINIT1
        putLe16(p, 0, s)
        putLe16(p, 8, 0)                 // status[1]
        putLe16(p, 10, 0)                // status[2]
        putLe16(p, 12, 0)                // status[3]
        putLe16(p, 14, pageDecoding)
        putLe16(p, 16, pageOut)
        putLe16(p, 18, pageOut)
        putLe16(p, 20, pageCompleted)
        putLe16(p, 24, 0)                // status[4]
        putLe16(p, 30, 0)                // status[5]
        putLe16(p, 34, pageReceived)
        putLe16(p, 38, 0)                // status[6]
        reply(CaptCommand.CHKXSTATUS, p)
    }

    private fun reply(cmd: Int, payload: ByteArray) {
        val frame = ByteArray(4 + payload.size)
        frame[0] = (cmd and 0xFF).toByte()
        frame[1] = ((cmd shr 8) and 0xFF).toByte()
        frame[2] = (frame.size and 0xFF).toByte()
        frame[3] = ((frame.size shr 8) and 0xFF).toByte()
        System.arraycopy(payload, 0, frame, 4, payload.size)
        frame.forEach { pending.addLast(it) }
    }

    override fun read(dest: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        if (pending.isEmpty()) return 0
        var n = 0
        while (n < length && pending.isNotEmpty()) {
            dest[offset + n] = pending.removeFirst()
            n++
        }
        return n
    }

    override fun close() {}

    fun totalPrintDataBytes() = printDataBytes

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun putLe16(b: ByteArray, at: Int, v: Int) {
        b[at] = (v and 0xFF).toByte(); b[at + 1] = ((v shr 8) and 0xFF).toByte()
    }

    companion object {
        const val XSTATUS_CHNG = 1 shl 8
        const val UNINIT1 = 1 shl 5
    }
}

private fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }

private var failures = 0

private fun check(name: String, condition: Boolean, detail: String = "") {
    if (condition) {
        println("PASS  $name")
    } else {
        println("FAIL  $name${if (detail.isNotEmpty()) "\n        $detail" else ""}")
        failures++
    }
}

fun main() {
    val printer = FakePrinter()
    val session = CaptSession(printer, CaptLogger.NONE)

    val fixedClock = {
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2026, Calendar.AUGUST, 19, 14, 30, 45)
        }
    }

    val job = Lbp2900Job(
        session = session,
        geometry = PageGeometry.A4,
        options = PageOptions(),
        logger = CaptLogger.NONE,
        sleeper = Sleeper.NONE,
        clock = fixedClock,
    )

    check("model detection", job.detectModel() == "LBP2900", "got '${job.detectModel()}'")

    // One page of blank paper: cheap to compress, exercises every band.
    val compressor = BandCompressor(PageGeometry.A4)
    val bands = compressor.compressPage(
        BandCompressor.RasterSource { _, _ -> true },   // blank page: dest stays zeroed
    )
    check(
        "band count for A4",
        bands.size == 97,
        "expected 97 bands (96 x 70 lines + 1 x 60), got ${bands.size}",
    )

    job.print(object : Lbp2900Job.PageSource {
        override val pageCount = 1
        override fun bandsForPage(index: Int) = bands
    })

    val cmds = printer.commandLog.map { it.first }

    // The prologue sequence, straight out of lbp2900_job_prologue().
    val prologue = listOf(
        CaptCommand.IDENT,
        CaptCommand.CHKSTATUS,
        CaptCommand.CHKXSTATUS,
        CaptCommand.START_0,
        CaptCommand.JOB_BEGIN,
        CaptCommand.GPIO,
    )
    check(
        "job prologue order",
        cmds.take(6) == prologue,
        "got ${cmds.take(6).map { CaptCommand.name(it) }}",
    )

    // The warm-up path must have run, since the fake reports UNINIT1.
    // Not contiguous: a readiness poll sits between START_3 and UPLOAD_2.
    val warmupOrder = listOf(
        CaptCommand.START_1, CaptCommand.START_2,
        CaptCommand.START_3, CaptCommand.UPLOAD_2,
    ).map { cmds.indexOf(it) }
    check(
        "warm-up sequence present and ordered",
        warmupOrder.none { it < 0 } && warmupOrder == warmupOrder.sorted(),
        "indices of START_1/2/3, UPLOAD_2 = $warmupOrder",
    )

    check("page parameters sent", cmds.contains(CaptCommand.SET_PARMS))
    check("page fired", cmds.contains(CaptCommand.FIRE))
    check("job closed", cmds.last() == CaptCommand.JOB_END,
        "last command was ${CaptCommand.name(cmds.last())}")

    // PRINT_DATA_END must come after every PRINT_DATA and before FIRE.
    val lastData = cmds.indexOfLast { it == CaptCommand.PRINT_DATA }
    val dataEnd = cmds.indexOf(CaptCommand.PRINT_DATA_END)
    val fire = cmds.indexOf(CaptCommand.FIRE)
    check("data -> end -> fire ordering", lastData < dataEnd && dataEnd < fire,
        "lastData=$lastData dataEnd=$dataEnd fire=$fire")

    // JOB_SETUP flags must appear as 1 (start), 2 (ready), 6 (release), 4 (done).
    val flags = printer.commandLog
        .filter { it.first == CaptCommand.JOB_SETUP }
        .map { it.second[16].toInt() }
    check("job-setup flag order", flags == listOf(1, 2, 6, 4), "got $flags")

    // Every band must have reached the printer intact.
    check(
        "all band bytes transmitted",
        printer.totalPrintDataBytes() == bands.sumOf { it.size },
        "sent ${printer.totalPrintDataBytes()}, expected ${bands.sumOf { it.size }}",
    )

    // The page parameter block for A4, byte for byte.
    val pageParams = printer.commandLog.first { it.first == CaptCommand.SET_PARMS }.second
    val expected = "0000302A02000000001C1C1C001104000101020000000E000E0053027C1A72127C1A000001000000"
    val actual = hex(pageParams).let { full ->
        // The SET_PARMS payload nests sub-frames; pull out SET_PARM_PAGE's.
        val idx = full.indexOf("A0D0")
        if (idx < 0) full else full.substring(idx + 8, idx + 8 + expected.length)
    }
    check("A4 page parameters", actual == expected, "got  $actual\n        want $expected")

    println()
    if (failures == 0) println("all checks passed") else println("$failures check(s) failed")
    if (failures > 0) kotlin.system.exitProcess(1)
}
