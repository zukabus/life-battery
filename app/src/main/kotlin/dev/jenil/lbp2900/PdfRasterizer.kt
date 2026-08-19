package dev.jenil.lbp2900

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import dev.jenil.capt.BandCompressor
import dev.jenil.capt.CaptLogger
import dev.jenil.capt.PageGeometry
import java.io.Closeable
import kotlin.math.min

/**
 * Renders PDF pages to the 1bpp raster the printer expects.
 *
 * A full A4 page at 600dpi is 4722 x 6780 pixels, which as ARGB_8888 is
 * about 128 MB — far too much to hold at once. So the page is rendered in
 * horizontal strips and dithered as it goes.
 *
 * Floyd–Steinberg error diffusion carries across strip boundaries via
 * [errorRow]; without that you get faint horizontal seams every strip.
 *
 * Bit convention: a set bit means toner, matching what [BandCompressor]
 * expects.
 */
class PdfRasterizer(
    descriptor: ParcelFileDescriptor,
    private val geometry: PageGeometry,
    private val logger: CaptLogger = CaptLogger.NONE,
    /** Whether to scale the page to the sheet or print it at true size. */
    private val scaleMode: ScaleMode = ScaleMode.FIT,
    /** Scanlines rendered per strip. Larger is faster but uses more memory. */
    private val stripHeight: Int = DEFAULT_STRIP_HEIGHT,
) : Closeable {

    private val renderer = PdfRenderer(descriptor)

    val pageCount: Int get() = renderer.pageCount

    private val width = geometry.paperWidth
    private val bytesPerLine = geometry.rasterBytesPerLine

    /** Packed 1bpp lines for the strip currently in memory. */
    private var stripBits: ByteArray = ByteArray(0)
    private var stripFirstLine = -1
    private var stripLines = 0
    private var currentPage = -1

    /** Diffused error carried into the next row, in gray units. */
    private val errorRow = IntArray(width + 2)
    private val nextErrorRow = IntArray(width + 2)

    private val pixels = IntArray(width * stripHeight)

    /**
     * A [BandCompressor.RasterSource] for one page. Lines must be requested
     * in increasing order, which is what the band loop does.
     */
    fun sourceFor(pageIndex: Int): BandCompressor.RasterSource {
        preparePage(pageIndex)
        return BandCompressor.RasterSource { line, dest ->
            if (line >= geometry.numLines) return@RasterSource false
            ensureStrip(line)
            val offset = (line - stripFirstLine) * bytesPerLine
            if (offset < 0 || offset + bytesPerLine > stripBits.size) {
                return@RasterSource false
            }
            System.arraycopy(stripBits, offset, dest, 0, bytesPerLine)
            true
        }
    }

    private var pageScale = 1f
    private var pageOffsetX = 0f
    private var pageOffsetY = 0f
    private var page: PdfRenderer.Page? = null

    private fun preparePage(index: Int) {
        closePage()
        val p = renderer.openPage(index)
        page = p
        currentPage = index

        // PdfRenderer reports page size in points (1/72 inch).
        val dpiScale = 600f / 72f
        pageScale = when (scaleMode) {
            ScaleMode.ACTUAL -> dpiScale
            ScaleMode.FIT -> {
                // Shrink to fit the printable area, preserving aspect ratio.
                // Never enlarge: blowing a small page up to fill A4 is
                // surprising, and "fit" should mean "make it all visible".
                val fit = min(
                    width / (p.width * dpiScale),
                    geometry.paperHeight / (p.height * dpiScale),
                )
                dpiScale * min(fit, 1f)
            }
        }
        // Centre it either way; in ACTUAL mode an oversized page is clipped
        // evenly on all sides rather than losing everything from one edge.
        pageOffsetX = (width - p.width * pageScale) / 2f
        pageOffsetY = (geometry.paperHeight - p.height * pageScale) / 2f

        stripFirstLine = -1
        stripLines = 0
        errorRow.fill(0)
        logger.log(
            "page ${index + 1}: ${p.width}x${p.height}pt -> " +
                "scale %.3f, offset %.0f,%.0f".format(pageScale, pageOffsetX, pageOffsetY)
        )
    }

    private fun ensureStrip(line: Int) {
        if (stripFirstLine >= 0 && line >= stripFirstLine && line < stripFirstLine + stripLines) {
            return
        }
        val first = (line / stripHeight) * stripHeight
        renderStrip(first)
    }

    private fun renderStrip(firstLine: Int) {
        val p = page ?: throw IllegalStateException("no page open")
        val lines = min(stripHeight, geometry.numLines - firstLine)
        if (lines <= 0) return

        val bitmap = Bitmap.createBitmap(width, lines, Bitmap.Config.ARGB_8888)
        try {
            // PDF pages have no background of their own; start from white.
            bitmap.eraseColor(Color.WHITE)

            val matrix = Matrix()
            matrix.setScale(pageScale, pageScale)
            matrix.postTranslate(pageOffsetX, pageOffsetY - firstLine)
            p.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

            bitmap.getPixels(pixels, 0, width, 0, 0, width, lines)
        } finally {
            bitmap.recycle()
        }

        if (stripBits.size < lines * bytesPerLine) {
            stripBits = ByteArray(stripHeight * bytesPerLine)
        }
        java.util.Arrays.fill(stripBits, 0, lines * bytesPerLine, 0)

        ditherStrip(lines)
        stripFirstLine = firstLine
        stripLines = lines
    }

    /** Floyd–Steinberg, writing packed 1bpp with set = black. */
    private fun ditherStrip(lines: Int) {
        for (y in 0 until lines) {
            nextErrorRow.fill(0)
            val rowBase = y * width
            val outBase = y * bytesPerLine
            for (x in 0 until width) {
                val argb = pixels[rowBase + x]
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                // Rec. 601 luma, then add the diffused error for this pixel.
                val gray = (r * 77 + g * 151 + b * 28) shr 8
                val value = gray + errorRow[x + 1]

                val black = value < 128
                val quantised = if (black) 0 else 255
                val error = value - quantised

                if (black) {
                    val byteIndex = outBase + (x shr 3)
                    val bit = 7 - (x and 7)     // MSB first
                    stripBits[byteIndex] = (stripBits[byteIndex].toInt() or (1 shl bit)).toByte()
                }

                // 7/16 right, 3/16 below-left, 5/16 below, 1/16 below-right
                errorRow[x + 2] += error * 7 / 16
                nextErrorRow[x] += error * 3 / 16
                nextErrorRow[x + 1] += error * 5 / 16
                nextErrorRow[x + 2] += error * 1 / 16
            }
            System.arraycopy(nextErrorRow, 0, errorRow, 0, errorRow.size)
        }
    }

    private fun closePage() {
        page?.let { runCatching { it.close() } }
        page = null
    }

    override fun close() {
        closePage()
        runCatching { renderer.close() }
    }

    companion object {
        /**
         * Eight bands' worth. At A4 width that is a ~10 MB intermediate
         * bitmap, which modern phones handle comfortably while keeping the
         * number of PDF render passes down to about a dozen per page.
         */
        const val DEFAULT_STRIP_HEIGHT = 560
    }
}
