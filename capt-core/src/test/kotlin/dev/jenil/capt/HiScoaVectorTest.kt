package dev.jenil.capt

import java.io.File

/**
 * Verifies the Kotlin HiSCoA port against golden vectors produced by
 * captdriver's own C compressor (tests/genvectors.c). Every vector must
 * match byte for byte — anything less means the printer gets garbage.
 *
 * Run: kotlinc ... && kotlin dev.jenil.capt.HiScoaVectorTestKt <vectors.txt>
 */

private var rndState = 12345

private fun rnd(): Int {
    rndState = rndState * 1103515245 + 12345   // wraps mod 2^32, as in C
    return (rndState ushr 16) and 0x7FFF
}

private class Case(
    val name: String,
    val band: ByteArray,
    val lineSize: Int,
    val nLines: Int,
    val eob: HiScoa.Eob,
)

private fun buildCases(): List<Case> {
    val cases = mutableListOf<Case>()
    var lineSize = 16
    var nLines = 8
    var sz = lineSize * nLines
    var b = ByteArray(sz)

    // 1. all zero
    b.fill(0x00)
    cases += Case("all_zero", b.copyOf(), lineSize, nLines, HiScoa.Eob.NORMAL)

    // 2/3. all 0xFF, both EOB types
    b.fill(0xFF.toByte())
    cases += Case("all_ff", b.copyOf(), lineSize, nLines, HiScoa.Eob.NORMAL)
    cases += Case("all_ff_lasteob", b.copyOf(), lineSize, nLines, HiScoa.Eob.LAST)

    // 4. identical lines — exercises the line-delta origins
    for (l in 0 until nLines) for (i in 0 until lineSize) b[l * lineSize + i] = (i * 7 + 3).toByte()
    cases += Case("repeat_lines", b.copyOf(), lineSize, nLines, HiScoa.Eob.NORMAL)

    // 5. incrementing bytes, no repeats
    for (i in 0 until sz) b[i] = i.toByte()
    cases += Case("incrementing", b.copyOf(), lineSize, nLines, HiScoa.Eob.NORMAL)

    // 6. pseudo-random — worst case for the compressor
    for (i in 0 until sz) b[i] = rnd().toByte()
    cases += Case("random", b.copyOf(), lineSize, nLines, HiScoa.Eob.NORMAL)

    // 7. sparse text-like
    b.fill(0x00)
    var l = 1
    while (l < nLines) { for (i in 3 until 9) b[l * lineSize + i] = 0x3C; l += 2 }
    cases += Case("sparse_text", b.copyOf(), lineSize, nLines, HiScoa.Eob.NORMAL)

    // 8. long run > 127 — exercises the 0xFC long-length escape
    lineSize = 64; nLines = 16; sz = lineSize * nLines
    b = ByteArray(sz); b.fill(0xA5.toByte())
    cases += Case("long_run", b.copyOf(), lineSize, nLines, HiScoa.Eob.NORMAL)

    // 9. realistic A4 line width, single line
    lineSize = 595; nLines = 1; sz = lineSize * nLines
    b = ByteArray(sz)
    for (i in 0 until sz) b[i] = if (i % 37 == 0) 0xFF.toByte() else 0x00
    cases += Case("a4_single_line", b.copyOf(), lineSize, nLines, HiScoa.Eob.NORMAL)

    // 10. realistic A4 band of 70 lines (the real band height for this printer)
    lineSize = 595; nLines = 70; sz = lineSize * nLines
    b = ByteArray(sz)
    for (ln in 10 until 60) for (i in 50 until 300) b[ln * lineSize + i] = ((ln * 31 + i * 17) and 0xFF).toByte()
    cases += Case("a4_band70", b.copyOf(), lineSize, nLines, HiScoa.Eob.NORMAL)

    // 11/12. degenerate single-byte bands — these catch the unsigned-origin trap
    cases += Case("single_byte", byteArrayOf(0x5A), 1, 1, HiScoa.Eob.NORMAL)
    cases += Case("single_zero", byteArrayOf(0x00), 1, 1, HiScoa.Eob.LAST)

    return cases
}

private fun ByteArray.toHex(n: Int): String {
    val sb = StringBuilder(n * 2)
    for (i in 0 until n) sb.append("%02x".format(this[i]))
    return sb.toString()
}

fun main(args: Array<String>) {
    val vectorFile = File(if (args.isNotEmpty()) args[0] else "/tmp/vectors.txt")
    val golden = vectorFile.readLines().filter { it.isNotBlank() }.associate { line ->
        val f = line.split(" ")
        f[0] to Triple(f[1].toInt(), f[2].toInt(), f[4].toInt() to f[5])
    }

    var pass = 0
    var fail = 0
    for (c in buildCases()) {
        val g = golden[c.name]
        if (g == null) {
            println("MISSING GOLDEN: ${c.name}")
            fail++
            continue
        }
        val (gLineSize, gNLines, rest) = g
        val (gLen, gHex) = rest
        check(gLineSize == c.lineSize && gNLines == c.nLines) {
            "${c.name}: harness/golden geometry mismatch"
        }

        val out = ByteArray(2 * c.lineSize * c.nLines + 4096)
        val n = HiScoa.compressBand(out, c.band, c.lineSize, c.nLines, c.eob)
        val hex = out.toHex(n)

        if (n == gLen && hex == gHex) {
            println("PASS  ${c.name.padEnd(16)} ${n} bytes")
            pass++
        } else {
            println("FAIL  ${c.name.padEnd(16)} got ${n} bytes, want ${gLen}")
            println("        got  ${hex.take(160)}")
            println("        want ${gHex.take(160)}")
            fail++
        }
    }

    // formatParams must match hiscoa_format_params exactly
    val expectedParams = "0104010100f90000"
    val actualParams = HiScoa.formatParams().toHex(8)
    if (actualParams == expectedParams) {
        println("PASS  ${"format_params".padEnd(16)} $actualParams")
        pass++
    } else {
        println("FAIL  format_params got $actualParams, want $expectedParams")
        fail++
    }

    println("\n$pass passed, $fail failed")
    if (fail > 0) kotlin.system.exitProcess(1)
}
