package dev.jenil.lbp2900

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import dev.jenil.capt.BandCompressor
import dev.jenil.capt.CaptLogger
import dev.jenil.capt.CaptSession
import dev.jenil.capt.Lbp2900Job
import dev.jenil.capt.PageGeometry
import dev.jenil.capt.PageOptions
import dev.jenil.capt.Sleeper

/**
 * Ties the pieces together: open the PDF, rasterise it, compress it, and
 * drive the CAPT job over USB.
 *
 * Pages are compressed fully before any of their data is sent, because the
 * printer will not tolerate a stall in the middle of a page's data stream.
 * That is also why compression happens off the main thread.
 */
class PrintController(
    private val context: Context,
    private val logger: CaptLogger,
) {
    /** Models this state machine is known to drive. */
    private val supportedModels = setOf("LBP2900", "LBP2900B", "LBP3000")

    data class Options(
        val geometry: PageGeometry = PageGeometry.A4,
        val page: PageOptions = PageOptions(),
        val copies: Int = 1,
    )

    /**
     * Prints [uri] on [device]. Blocking — call from a worker thread.
     * @throws Exception on any failure; the message is fit to show the user.
     */
    fun print(device: UsbDevice, uri: Uri, options: Options = Options()) {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val transport = UsbCaptTransport.open(manager, device, logger)
        try {
            val session = CaptSession(transport, logger)
            val job = Lbp2900Job(
                session = session,
                geometry = options.geometry,
                options = options.page,
                logger = logger,
                sleeper = Sleeper.REAL,
            )

            val model = job.detectModel()
            logger.log("printer reports model: $model")
            if (model !in supportedModels) {
                // Not fatal: the protocol may well still work, and refusing
                // outright would be unhelpful. Say so and continue.
                logger.log("warning: '$model' is not a model this app has been built for")
            }

            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalArgumentException("cannot open the selected file")
            pfd.use { pfd ->
                PdfRasterizer(pfd, options.geometry, logger).use { rasterizer ->
                    val compressor = BandCompressor(options.geometry)
                    logger.log("document has ${rasterizer.pageCount} page(s)")

                    val source = object : Lbp2900Job.PageSource {
                        override val pageCount = rasterizer.pageCount * options.copies

                        override fun bandsForPage(index: Int): List<ByteArray> {
                            val realPage = index % rasterizer.pageCount
                            logger.log("rasterising page ${realPage + 1}...")
                            val started = System.currentTimeMillis()
                            val bands = compressor.compressPage(
                                rasterizer.sourceFor(realPage),
                            ) { done, total ->
                                if (done % 20 == 0 || done == total) {
                                    logger.log("  band $done/$total")
                                }
                            }
                            val elapsed = System.currentTimeMillis() - started
                            val bytes = bands.sumOf { it.size }
                            logger.log("  compressed to ${bytes / 1024} KB in ${elapsed}ms")
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
