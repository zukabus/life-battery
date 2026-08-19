package dev.jenil.capt

/**
 * Printer status block — port of captdriver's `src/capt-status.[ch]`.
 * (C) 2013 Alexey Galakhov, GPLv3.
 *
 * Note the accumulating behaviour, which is load-bearing: a short
 * CHKSTATUS reply updates only `status[0]`, leaving the page counters from
 * the last long CHKXSTATUS reply intact. Zeroing the whole block on every
 * poll would break the page-counter comparisons the job state machine
 * depends on.
 */
class CaptStatus {
    val status = IntArray(7)

    var pageDecoding = 0; private set
    var pagePrinting = 0; private set
    var pageOut = 0; private set
    var pageCompleted = 0; private set
    var pageReceived = 0; private set

    fun reset() {
        status.fill(0)
        pageDecoding = 0
        pagePrinting = 0
        pageOut = 0
        pageCompleted = 0
        pageReceived = 0
    }

    /**
     * Decodes a status reply payload (the frame minus its 4-byte header).
     *
     * DELIBERATE DEVIATION FROM UPSTREAM. captdriver's `decode_status()`
     * guards its field reads with `if (size <= 2)` and `if (size <= 10)`,
     * but the `size` it receives is the TOTAL frame length, payload plus
     * four. So a 2-byte payload arrives as size 6, sails past the `<= 2`
     * guard, and reads bytes 8 and 9 of a two-byte buffer. In C that is a
     * harmless over-read of a 64KB static buffer full of the previous
     * reply; in Kotlin it is an exception.
     *
     * The guards are clearly meant to be payload-length thresholds, so
     * that is what they are here: 2 bytes for status[0], 10 for status[1],
     * 40 for the page counters (the highest field ends at byte 39). Short
     * replies now leave the remaining fields at their previous values,
     * which is the accumulating behaviour the state machine relies on.
     */
    fun decode(s: ByteArray) {
        fun w(i: Int) = ((s[i + 1].toInt() and 0xFF) shl 8) or (s[i].toInt() and 0xFF)

        if (s.size < 2) return
        status[0] = w(0)
        if (s.size < 10) return

        status[1] = w(8)
        if (s.size < 40) return

        status[2] = w(10)
        status[3] = w(12)

        pageDecoding = w(14)
        pagePrinting = w(16)
        pageOut = w(18)
        pageCompleted = w(20)
        pageReceived = w(34)

        status[4] = w(24)
        status[5] = w(30)
        status[6] = w(38)
    }

    fun flag(f: CaptFlag): Boolean = (status[f.word] and (1 shl f.bit)) != 0

    override fun toString(): String =
        "status=[${status.joinToString(" ") { "%04X".format(it) }}] " +
            "pages dec=$pageDecoding print=$pagePrinting out=$pageOut " +
            "done=$pageCompleted recv=$pageReceived"

    /** The human-readable summary upstream prints after each status poll. */
    fun summary(): String =
        "P1=${b(CaptFlag.NOPAPER1)} P2=${b(CaptFlag.NOPAPER2)} " +
            "B=${b(CaptFlag.BUTTON_ON)} B0=${b(CaptFlag.BUTTON)} " +
            "B1=${b(CaptFlag.BUTTON1)} nE=${b(CaptFlag.N_ERROR)} " +
            "busy=${b(CaptFlag.BUSY)} | pages $pageDecoding/$pagePrinting/$pageOut/$pageCompleted"

    private fun b(f: CaptFlag) = if (flag(f)) '1' else '0'
}

/** Status bits, as `word` index into [CaptStatus.status] plus bit number. */
enum class CaptFlag(val word: Int, val bit: Int) {
    // status[0]
    READY1(0, 15),
    READY2(0, 12),
    JOBSTAT_CHNG(0, 9),
    XSTATUS_CHNG(0, 8),
    BUSY(0, 7),
    UNINIT1(0, 5),
    UNINIT2(0, 4),
    BUFFERFULL(0, 2),
    NOPAPER1(0, 1),
    PROCESSING(0, 0),

    // status[1]
    NOPAPER2(1, 14),
    PROCESSING1(1, 7),
    BUTTON(1, 5),
    PRINTING(1, 2),
    POWERUP(1, 0),

    // status[2]
    N_ERROR(2, 7),
    BUTTON1(2, 8),

    // status[3]
    POWERUP1(3, 12),

    // status[4]
    BUTTON_ON(4, 0),
}
