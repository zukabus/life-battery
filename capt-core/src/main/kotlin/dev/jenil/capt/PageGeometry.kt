package dev.jenil.capt

/**
 * Page geometry for the LBP2900 at 600dpi.
 *
 * These numbers are not guesses. The upstream driver gets them from CUPS,
 * so they were obtained by compiling captdriver's `canon-lbp.drv` with
 * `ppdc` and running CUPS's own `cupsRasterInterpretPPD()` against the
 * resulting PPD — the exact call chain a real print job goes through.
 *
 * Two quirks worth knowing, both faithful to upstream:
 *
 *  1. [lineSize] is NOT the raster's bytes-per-line. Upstream sets it from
 *     `header->PageSize[0]`, the page WIDTH IN POINTS, which happens to sit
 *     4 bytes above the real line length. The raster is then centred inside
 *     that wider line, giving a 2-byte left shift. Reproducing this is
 *     mandatory: the printer is told [lineSize], so a "corrected" value
 *     skews every scanline.
 *
 *  2. Upstream's media-size lookup compares against `header->MediaType`
 *     ("Plain") rather than the page size name, so no case ever matches and
 *     the code always falls through to A4's 0x02. That means A4 is the only
 *     size with real-world mileage; the other codes here come from the
 *     source table but have never actually been exercised.
 */
data class PageGeometry(
    val name: String,
    /** Media size code sent in page parameters byte 4. */
    val sizeCode: Int,
    /** Bytes per line in the band buffer handed to the compressor. */
    val lineSize: Int,
    /** Total scanlines in the page. */
    val numLines: Int,
    /** Printable width in pixels at 600dpi. */
    val paperWidth: Int,
    /** Printable height in pixels at 600dpi. */
    val paperHeight: Int,
    /** Bottom margin in points, as CUPS reports it. */
    val marginHeight: Int,
    /** Left margin in points, as CUPS reports it. */
    val marginWidth: Int,
    /** Bytes per line of actual raster data ((paperWidth + 7) / 8). */
    val rasterBytesPerLine: Int,
    /** Scanlines per band. */
    val bandSize: Int = 70,
) {
    /** Left shift applied when centring the raster in the wider line. */
    val shiftBytes: Int get() = (lineSize - rasterBytesPerLine) / 2

    /** Number of bands in a full page; the last one may be short. */
    val bandCount: Int get() = (numLines + bandSize - 1) / bandSize

    fun linesInBand(index: Int): Int {
        val start = index * bandSize
        return minOf(bandSize, numLines - start)
    }

    init {
        require(lineSize >= rasterBytesPerLine) { "lineSize must not be narrower than the raster" }
        require(bandSize > 0 && numLines > 0)
    }

    companion object {
        /**
         * A4 at 600dpi with the driver's 5mm hardware margins.
         * The only size with real-world confirmation — see note (2) above.
         */
        val A4 = PageGeometry(
            name = "A4",
            sizeCode = 0x02,
            lineSize = 595,
            numLines = 6780,
            paperWidth = 4722,
            paperHeight = 6780,
            marginHeight = 14,
            marginWidth = 14,
            rasterBytesPerLine = 591,
        )

        /** US Letter. Derived the same way, but untested against hardware. */
        val LETTER = PageGeometry(
            name = "Letter",
            sizeCode = 0x0D,
            lineSize = 612,
            numLines = 6364,
            paperWidth = 4864,
            paperHeight = 6364,
            marginHeight = 14,
            marginWidth = 14,
            rasterBytesPerLine = 608,
        )

        val ALL = listOf(A4, LETTER)

        fun byName(name: String): PageGeometry =
            ALL.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: A4
    }
}

/** Per-page options that map onto the page-parameter block. */
data class PageOptions(
    /** 0 = plain, 1 = thick, 3 = thick H, 4/5 = transparency, 6 = envelope. */
    val mediaType: Int = 0,
    /** Toner save: 0 off, 1 on. */
    val tonerSave: Int = 0,
    /**
     * Print density 0..15. The LBP2900 PPD exposes no density control, so a
     * real CUPS job always sends 0 — that is the proven value.
     */
    val density: Int = 0,
    /** Wait for the user to reload paper between sides. */
    val manualDuplex: Boolean = false,
) {
    /** Fuser mode, chosen by media type exactly as upstream does. */
    val fuserMode: Int
        get() = when (mediaType) {
            0x00, 0x02 -> 0x01   // plain, plain L
            0x01 -> 0x01         // thick
            0x03 -> 0x02         // thick H
            0x04 -> 0x13         // transparency
            0x05 -> 0x14         // transparency
            0x06 -> 0x1C         // envelope
            else -> 0x01
        }

    /** 0x11 at 600dpi; 0x81 would signal the 400dpi mode. */
    val mediaAdapt: Int get() = 0x11
}
