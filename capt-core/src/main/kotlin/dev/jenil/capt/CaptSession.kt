package dev.jenil.capt

/**
 * CAPT framing layer — port of captdriver's `src/capt-command.c`.
 * (C) 2013 Alexey Galakhov, GPLv3.
 *
 * Frame layout, all little-endian:
 *
 *     [0..1] command
 *     [2..3] total length (payload + 4)
 *     [4..]  payload
 *
 * The upstream driver runs through CUPS, so it has to poke the CUPS USB
 * backend with a side-channel DRAIN_OUTPUT after every chunk to make it
 * flush. Talking to the endpoints directly, that whole dance disappears —
 * a bulk write is already a flush. This class is the only thing that knows
 * about framing; everything above it deals in commands.
 */
class CaptSession(
    private val transport: CaptTransport,
    private val logger: CaptLogger = CaptLogger.NONE,
    private val replyTimeoutMs: Int = 15_000,
) {
    /** Matches captdriver's static I/O buffer size. */
    private val ioBuf = ByteArray(0x10000)
    private var ioSize = 0

    /** Max payload per bulk write, mirroring upstream's 4096-byte chunks. */
    private val writeChunk = 4096

    // ---- frame construction ----------------------------------------------

    private fun putCmd(cmd: Int, data: ByteArray?, offset: Int, length: Int) {
        val len = if (data == null) 0 else length
        if (ioSize + 4 + len > ioBuf.size) {
            throw CaptException("CAPT output buffer overflow (${ioSize + 4 + len} bytes)")
        }
        if (data != null && len > 0) {
            System.arraycopy(data, offset, ioBuf, ioSize + 4, len)
        }
        ioBuf[ioSize + 0] = (cmd and 0xFF).toByte()
        ioBuf[ioSize + 1] = ((cmd shr 8) and 0xFF).toByte()
        ioBuf[ioSize + 2] = ((len + 4) and 0xFF).toByte()
        ioBuf[ioSize + 3] = (((len + 4) shr 8) and 0xFF).toByte()
        ioSize += len + 4
    }

    private fun flushOut() {
        var pos = 0
        var remaining = ioSize
        while (remaining > 0) {
            val n = if (remaining > writeChunk) writeChunk else remaining
            transport.write(ioBuf, pos, n)
            pos += n
            remaining -= n
        }
    }

    // ---- public API -------------------------------------------------------

    /** Fire-and-forget: no reply is read. */
    fun send(cmd: Int, data: ByteArray? = null, offset: Int = 0, length: Int = data?.size ?: 0) {
        ioSize = 0
        putCmd(cmd, data, offset, length)
        logger.log("-> ${CaptCommand.name(cmd)} (${length}B payload)")
        flushOut()
    }

    /**
     * Sends [cmd] and reads the matching reply frame.
     * @return the reply payload (frame minus its 4-byte header).
     */
    fun sendRecv(cmd: Int, data: ByteArray? = null): ByteArray {
        send(cmd, data)
        receiveFrame(cmd)
        val payload = ByteArray(ioSize - 4)
        System.arraycopy(ioBuf, 4, payload, 0, payload.size)
        logger.log("<- ${CaptCommand.name(cmd)} (${payload.size}B payload)")
        return payload
    }

    /**
     * Builds one composite frame carrying several sub-commands, used for
     * CAPT_SET_PARMS. The outer frame's length covers everything inside it.
     */
    fun sendMulti(cmd: Int, build: MultiBuilder.() -> Unit) {
        ioBuf[0] = (cmd and 0xFF).toByte()
        ioBuf[1] = ((cmd shr 8) and 0xFF).toByte()
        ioSize = 4
        MultiBuilder().build()
        ioBuf[2] = (ioSize and 0xFF).toByte()
        ioBuf[3] = ((ioSize shr 8) and 0xFF).toByte()
        logger.log("-> ${CaptCommand.name(cmd)} (multi, ${ioSize}B total)")
        flushOut()
    }

    inner class MultiBuilder {
        fun add(cmd: Int, data: ByteArray? = null) {
            putCmd(cmd, data, 0, data?.size ?: 0)
        }
    }

    fun deviceId(): String = transport.deviceId()

    // ---- reply parsing ----------------------------------------------------

    private fun readInto(offset: Int, expected: Int) {
        if (offset + expected > ioBuf.size) {
            throw CaptException("CAPT input buffer overflow")
        }
        val n = transport.read(ioBuf, offset, expected, replyTimeoutMs)
        if (n < 0) throw CaptException("no reply from printer (read error)")
        if (n == 0) throw CaptException("no reply from printer (timeout after ${replyTimeoutMs}ms)")
        ioSize = offset + n
    }

    private fun word(lo: Int, hi: Int) = ((hi and 0xFF) shl 8) or (lo and 0xFF)

    private fun u8(i: Int) = ioBuf[i].toInt() and 0xFF

    /**
     * Some replies encode their length as BCD rather than binary. Upstream
     * accepts either, so we do too.
     */
    private fun bcd(lo: Int, hi: Int): Int {
        val a = (hi shr 4) and 0x0F
        val b = hi and 0x0F
        val c = (lo shr 4) and 0x0F
        val d = lo and 0x0F
        if (a > 9 || b > 9 || c > 9 || d > 9) return word(lo, hi)
        return a * 1000 + b * 100 + c * 10 + d
    }

    private fun receiveFrame(expectedCmd: Int) {
        readInto(0, 6)
        if (ioSize != 6 || word(u8(0), u8(1)) != expectedCmd) {
            throw CaptException(
                "bad reply: expected ${CaptCommand.name(expectedCmd)}, got " +
                    hex(ioBuf, 0, minOf(ioSize, 6))
            )
        }
        while (true) {
            val declared = word(u8(2), u8(3))
            if (declared == ioSize) return
            if (bcd(u8(2), u8(3)) == ioSize) return
            // A frame that lands exactly on a 64-byte USB packet boundary is
            // not the last one — go back for the rest.
            if (declared > ioSize && ioSize % 64 == 6) {
                readInto(ioSize, declared - ioSize)
                continue
            }
            throw CaptException(
                "bad reply length: header says $declared, received $ioSize; " +
                    hex(ioBuf, 0, minOf(ioSize, 32))
            )
        }
    }

    companion object {
        fun hex(b: ByteArray, off: Int = 0, len: Int = b.size): String {
            val sb = StringBuilder(len * 3)
            for (i in off until off + len) {
                if (i > off) sb.append(' ')
                sb.append("%02X".format(b[i]))
            }
            return sb.toString()
        }
    }
}
