package dev.jenil.capt

/**
 * HiSCoA band compressor for Canon CAPT printers (LBP2900 and relatives).
 *
 * Direct port of captdriver's `src/hiscoa-compress.c`
 * (C) 2013 Alexey Galakhov, GPLv3 — this file inherits that licence.
 *
 * The format is an LZ77-ish scheme with six "origin" back-references, a
 * move-to-front cache of the last 16 literal bytes, and Elias-style
 * variable-length codes, with the whole output stream XORed by 0x43.
 *
 * Two behaviours below deliberately mirror C semantics rather than the
 * "obvious" Kotlin ones; both are load-bearing and covered by the golden
 * vectors in HiScoaVectorTest:
 *
 *  1. `origin` values are UNSIGNED 32-bit. `origin[2]` is `lineSize - 7`,
 *     which underflows to a huge value for narrow bands; in C that makes
 *     `pos < diff` true and the match is skipped. Signed comparison would
 *     instead run off the end of the input.
 *  2. The final padding to a 32-bit boundary is unconditional — when
 *     bitPos is already aligned it emits a full extra 32 bits. The
 *     upstream `if` is commented out on purpose.
 */
object HiScoa {

    /** Origin seeds. Defaults match captdriver's `hiscoa_default_params`. */
    data class Params(
        val origin3: Int = 1,
        val origin5: Int = 4,
        val origin0: Int = 0,
        val origin2: Int = -7,
        val origin4: Int = 0,
    )

    val DEFAULT_PARAMS = Params()

    /** End-of-band marker written after the last symbol of a band. */
    enum class Eob(val code: Int) { NORMAL(0x0), LAST(0x1) }

    private const val XOR_VAL = 0x43

    private class State(
        val input: ByteArray,
        val inputSize: Int,
        val output: ByteArray,
        val outputBitSize: Int,
        val lineSize: Int,
    ) {
        var inputPos = 0
        var bitPos = 0
        var nBytes = 0
        val bytes = ByteArray(16)
        val origin = IntArray(6)
    }

    /**
     * Compresses one band of a page.
     *
     * @param out destination buffer; must be zero-filled and large enough
     *   (captdriver allocates `2 * lineSize * nLines`, plus slack for the
     *   pathological incompressible case).
     * @param src the band, [lineSize] bytes per line, [nLines] lines, 1bpp
     *   with a set bit meaning "mark the paper".
     * @return the number of bytes written to [out].
     */
    fun compressBand(
        out: ByteArray,
        src: ByteArray,
        lineSize: Int,
        nLines: Int,
        eob: Eob = Eob.NORMAL,
        params: Params = DEFAULT_PARAMS,
    ): Int {
        val st = State(
            input = src,
            inputSize = lineSize * nLines,
            output = out,
            outputBitSize = 8 * out.size,
            lineSize = lineSize,
        )
        st.origin[1] = 0
        st.origin[3] = params.origin3
        st.origin[5] = params.origin5
        st.origin[0] = params.origin0 + lineSize
        st.origin[2] = params.origin2 + lineSize
        st.origin[4] = params.origin4

        while (st.inputPos < st.inputSize) {
            if (tryWriteLongRepeat(st)) continue
            if (tryWriteByteRepeat(st)) continue
            writeSimpleByte(st)
        }

        pushBits(st, 0xFE, 8)                       // end of band
        pushBits(st, eob.code, 2)
        // Unconditional, matching upstream: emits 32 bits when already aligned.
        pushBits(st, -1, 32 - (st.bitPos % 32))

        return st.bitPos / 8
    }

    /**
     * Serialises the compressor parameters for the CAPT_SET_PARM_HISCOA
     * sub-command. Always 8 bytes.
     */
    fun formatParams(params: Params = DEFAULT_PARAMS): ByteArray = byteArrayOf(
        params.origin3.toByte(),
        params.origin5.toByte(),
        0x01,
        0x01,
        params.origin0.toByte(),
        params.origin2.toByte(),
        (params.origin4 and 0xFF).toByte(),
        ((params.origin4 shr 8) and 0xFF).toByte(),
    )

    // ---- bit output -------------------------------------------------------

    private fun pushBits(st: State, bits: Int, count: Int) {
        var remaining = count
        var value = bits
        while (remaining > 0 && st.bitPos < st.outputBitSize) {
            val word = st.bitPos / 8
            val spc = 8 - (st.bitPos % 8)
            val cnt = if (remaining > spc) spc else remaining
            val mask = ((0xFF ushr (8 - cnt)) shl (spc - cnt)) and 0xFF
            val v = (value ushr (remaining - cnt)) shl (spc - cnt)
            var x = st.output[word].toInt() and 0xFF
            x = x xor XOR_VAL
            x = x and mask.inv()
            x = x or (mask and v)
            x = x xor XOR_VAL
            st.output[word] = x.toByte()
            st.bitPos += cnt
            remaining -= cnt
        }
    }

    // ---- matching ---------------------------------------------------------

    /**
     * Length of the run starting at `inputPos` that repeats the bytes
     * `diff` positions earlier. [diff] is unsigned; see the class note.
     */
    private fun tryMatch(st: State, diff: Int): Int {
        var pos = st.inputPos
        // C: `if (pos < diff) return 0;` with both operands unsigned.
        if (java.lang.Integer.compareUnsigned(pos, diff) < 0) return 0
        while (st.input[pos] == st.input[pos - diff]) {
            ++pos
            if (pos >= st.inputSize) break
            if (pos >= st.inputPos + 512 + 127) break
            if (pos % st.lineSize == 0) break
        }
        return pos - st.inputPos
    }

    /** Bit length of [value]; 0 for 0. Mirrors upstream `find_msb`. */
    private fun findMsb(value: Int): Int =
        if (value == 0) 0 else 32 - Integer.numberOfLeadingZeros(value)

    private fun tryWriteLongRepeat(st: State): Boolean {
        var bestCmd = 0
        var bestLen = 0
        for (cmd in 0 until 6) {
            if (st.origin[cmd] != 0) {
                val len = tryMatch(st, st.origin[cmd])
                if (len > bestLen) {
                    bestCmd = cmd
                    bestLen = len
                }
            }
        }
        if (bestLen <= 1) return false

        var len = bestLen
        if (len > 127) {
            val v = len / 128
            val nbits = findMsb(v) - 1
            pushBits(st, 0xFC, 8)
            pushBits(st, nbits, 2)
            pushBits(st, v.inv(), nbits)
            len %= 128
        }

        pushBits(st, 0xFFFFFFFE.toInt(), bestCmd + 1)   // unary command code
        if (bestCmd == 2) pushBits(st, 0x0, 1)          // subcommand 0

        when (len) {
            0 -> pushBits(st, 0x3F, 6)
            1 -> pushBits(st, 0x0, 2)
            2 -> pushBits(st, 0x3, 3)
            3 -> pushBits(st, 0x2, 3)
            else -> {
                val nbits = findMsb(len) - 1
                pushBits(st, 0xFFFFFFFE.toInt(), nbits)
                pushBits(st, len.inv(), nbits)
            }
        }

        if (bestCmd == 2) { val t = st.origin[2]; st.origin[2] = st.origin[0]; st.origin[0] = t }
        if (bestCmd == 5) { val t = st.origin[5]; st.origin[5] = st.origin[3]; st.origin[3] = t }

        st.inputPos += bestLen
        return true
    }

    /** Emits a reference to one of the last 16 literal bytes (move-to-front). */
    private fun tryWriteByteRepeat(st: State): Boolean {
        val byte = st.input[st.inputPos]
        for (i in 0 until st.nBytes) {
            if (byte == st.bytes[i]) {
                for (j in i downTo 1) st.bytes[j] = st.bytes[j - 1]
                st.bytes[0] = byte
                pushBits(st, 0x20 or ((15 - i) and 0xF), 6)
                st.inputPos += 1
                return true
            }
        }
        return false
    }

    private fun writeSimpleByte(st: State) {
        val byte = st.input[st.inputPos]
        if (st.nBytes < 16) st.nBytes += 1
        for (i in st.nBytes - 1 downTo 1) st.bytes[i] = st.bytes[i - 1]
        st.bytes[0] = byte

        val b = byte.toInt() and 0xFF
        if (b == 0) {
            pushBits(st, 0xFD, 8)                 // zero literal
        } else {
            pushBits(st, 0xD00 or b, 12)          // escaped literal
        }
        st.inputPos += 1
    }
}
