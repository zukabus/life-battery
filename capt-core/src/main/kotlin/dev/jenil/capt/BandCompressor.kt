package dev.jenil.capt

/**
 * Turns a page of 1bpp scanlines into the compressed bands the printer
 * wants. Port of the band loop in captdriver's `src/rastertocapt.c`.
 *
 * Bit convention: a SET bit means "put toner here" (CUPS colour space K).
 * Whatever produces the raster must match that, or the page comes out
 * inverted.
 *
 * The raster is narrower than the line the printer is told about, so each
 * line is centred inside the wider band line — see [PageGeometry] for why
 * that gap exists.
 */
class BandCompressor(
    private val geometry: PageGeometry,
    private val params: HiScoa.Params = HiScoa.DEFAULT_PARAMS,
) {
    /** Supplies one page's scanlines, top to bottom. */
    fun interface RasterSource {
        /**
         * Fills [dest] with scanline [line], packed 1bpp MSB-first,
         * [PageGeometry.rasterBytesPerLine] bytes.
         *
         * [dest] is pre-zeroed, so a source that has run out of image can
         * simply return false and leave it blank.
         *
         * @return false once the image is exhausted; remaining lines are
         *   emitted as blank paper.
         */
        fun readLine(line: Int, dest: ByteArray): Boolean
    }

    private val lineBuf = ByteArray(geometry.rasterBytesPerLine)
    private val bandBuf = ByteArray(geometry.lineSize * geometry.bandSize)

    // Worst case for this scheme is 12 output bits per incompressible input
    // byte, plus the end-of-band marker and up to 32 bits of padding. 2x the
    // input plus a page of slack is what upstream allocates and is ample.
    private val compBuf = ByteArray(2 * geometry.lineSize * geometry.bandSize + 4096)

    /**
     * Compresses a whole page.
     *
     * @param onProgress called with (bandIndex, bandCount) as work proceeds,
     *   so a UI can show something moving during the slow part.
     * @return one compressed byte array per band, in order.
     */
    fun compressPage(
        source: RasterSource,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): List<ByteArray> {
        val g = geometry
        val out = ArrayList<ByteArray>(g.bandCount)
        var exhausted = false

        for (bandIndex in 0 until g.bandCount) {
            val start = bandIndex * g.bandSize
            val nLines = g.linesInBand(bandIndex)

            bandBuf.fill(0)
            for (i in 0 until nLines) {
                lineBuf.fill(0)
                if (!exhausted && !source.readLine(start + i, lineBuf)) {
                    exhausted = true
                }
                if (!exhausted) {
                    System.arraycopy(
                        lineBuf, 0,
                        bandBuf, i * g.lineSize + g.shiftBytes,
                        g.rasterBytesPerLine,
                    )
                }
            }

            compBuf.fill(0)
            val n = HiScoa.compressBand(compBuf, bandBuf, g.lineSize, nLines, HiScoa.Eob.NORMAL, params)
            out.add(compBuf.copyOf(n))
            onProgress(bandIndex + 1, g.bandCount)
        }
        return out
    }
}
