package dev.jenil.lbp2900

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import dev.jenil.capt.BandCompressor
import dev.jenil.capt.CaptLogger
import dev.jenil.capt.CaptSession
import dev.jenil.capt.Lbp2900Job
import dev.jenil.capt.PageRange
import dev.jenil.capt.Sleeper

/**
 * Ties the pieces together: open the PDF, rasterise it, compress it, and
 * drive the CAPT job over USB.
 *
 * Pages are compressed fully before any of their data is sent, because the
 * printer will not tolerate a stall in the middle of a page's data stream.
 * That is also why this is blocking and belongs on a worker thread.
 */
class PrintController(
    private val context: Context,
    private val logger: CaptLogger,
) {
    /** Models this state machine is known to drive. */
    private val supportedModels = setOf("LBP2900", "LBP2900B", "LBP3000")

    /**
     * Reads the page count without printing, so the UI can validate a page
     * range before the user commits to anything.
     */
    fun pageCount(uri: Uri): Int =
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val renderer = android.graphics.pdf.PdfRenderer(pfd)
            try {
                renderer.pageCount
            } finally {
                renderer.close()
            }
        } ?: 0

    /**
     * Prints [uri] on [device]. Blocking — call from a worker thread.
     * @throws Exception on any failure; the message is fit to show the user.
     */
    fun print(device: UsbDevice, uri: Uri, settings: PrintSettings = PrintSettings()) {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val transport = UsbCaptTransport.open(manager, device, logger)
        try {
            val session = CaptSession(transport, logger)
            val job = Lbp2900Job(
                session = session,
                geometry = settings.geometry,
                options = settings.pageOptions,
                logger = logger,
                sleeper = Sleeper.REAL,
            )

            val model = job.detectModel()
            logger.log("printer reports model: $model")
            if (model !in supportedModels) {
                // Not fatal: the protocol may well still work, and refusing
                // outright would be unhelpful. Say so and carry on.
                logger.log("warning: '$model' is not a model this app was built for")
            }

            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalArgumentException("cannot open the selected file")

            pfd.use { descriptor ->
                PdfRasterizer(
                    descriptor = descriptor,
                    geometry = settings.geometry,
                    logger = logger,
                    scaleMode = settings.scaleMode,
                ).use { rasterizer ->
                    val compressor = BandCompressor(settings.geometry)
                    logger.log("document has ${rasterizer.pageCount} page(s)")

                    val selected = PageRange.parse(settings.pageRange, rasterizer.pageCount)
                    // Collated: whole document, then again. Matches what a
                    // normal print dialog does with "collate" left on.
                    val sheets = buildList {
                        repeat(settings.copies) { addAll(selected) }
                    }
                    logger.log("printing ${sheets.size} sheet(s): ${settings.summary()}")

                    val source = object : Lbp2900Job.PageSource {
                        override val pageCount = sheets.size

                        override fun bandsForPage(index: Int): List<ByteArray> {
                            val pdfPage = sheets[index]
                            logger.log("rasterising page ${pdfPage + 1} (sheet ${index + 1}/${sheets.size})...")
                            val started = System.currentTimeMillis()
                            val bands = compressor.compressPage(
                                rasterizer.sourceFor(pdfPage),
                            ) { done, total ->
                                if (done % 20 == 0 || done == total) logger.log("  band $done/$total")
                            }
                            val elapsed = System.currentTimeMillis() - started
                            logger.log("  compressed to ${bands.sumOf { it.size } / 1024} KB in ${elapsed}ms")
                            return bands
                        }
                    }

                    job.print(source)
                    logger.log("done")
                }
            }
        } catch (t: Throwable) {
            logger.log("ERROR: ${t.message}")
            throw t
        } finally {
            transport.close()
        }
    }

    fun findPrinter(): UsbDevice? {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val candidates = UsbCaptTransport.findPrinters(manager)
        if (candidates.isEmpty()) return null
        // Prefer a Canon device if several are attached.
        return candidates.firstOrNull { it.vendorId == UsbCaptTransport.CANON_VENDOR_ID }
            ?: candidates.first()
    }
}
