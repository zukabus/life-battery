package dev.jenil.lbp2900

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import dev.jenil.capt.CaptException
import dev.jenil.capt.CaptLogger
import dev.jenil.capt.CaptTransport

/**
 * [CaptTransport] over Android USB host mode, for a printer on the end of
 * an OTG cable. No root required — the USB host API is public.
 *
 * The one non-obvious part is [read]. USB bulk endpoints deliver whole
 * packets: ask for 6 bytes when the printer sent a 40-byte reply and the
 * host controller discards the other 34. The CAPT framing layer genuinely
 * does want 6 bytes first (to learn the frame length), so this class always
 * pulls a full endpoint-sized transfer off the wire and hands out bytes
 * from an internal buffer. Getting this wrong looks like random
 * "bad reply length" failures on the larger status replies.
 */
class UsbCaptTransport private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val endpointIn: UsbEndpoint,
    private val endpointOut: UsbEndpoint,
    private val interfaceNumber: Int,
    private val altSetting: Int,
    private val logger: CaptLogger,
) : CaptTransport {

    private val packetSize = maxOf(endpointIn.maxPacketSize, 64)
    private val rxBuf = ByteArray(maxOf(packetSize * 16, 4096))
    private var rxPos = 0
    private var rxLen = 0

    override fun deviceId(): String {
        // USB Printer class request GET_DEVICE_ID (bRequest 0).
        // Reply is a big-endian 16-bit length followed by the ID string.
        val buf = ByteArray(1024)
        val n = connection.controlTransfer(
            /* requestType = */ 0xA1,               // in | class | interface
            /* request = */ 0,
            /* value = */ 0,                        // config index
            /* index = */ (interfaceNumber shl 8) or altSetting,
            buf, buf.size, CONTROL_TIMEOUT_MS,
        )
        if (n < 2) throw CaptException("GET_DEVICE_ID failed (returned $n)")
        var declared = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
        if (declared > n) declared = n
        return String(buf, 2, maxOf(declared - 2, 0), Charsets.US_ASCII).trim()
    }

    override fun write(data: ByteArray, offset: Int, length: Int) {
        var pos = offset
        var remaining = length
        while (remaining > 0) {
            val chunk = minOf(remaining, MAX_BULK_TRANSFER)
            val n = connection.bulkTransfer(endpointOut, data, pos, chunk, WRITE_TIMEOUT_MS)
            if (n < 0) throw CaptException("USB write failed at offset $pos ($chunk bytes)")
            if (n == 0) throw CaptException("USB write timed out at offset $pos")
            pos += n
            remaining -= n
        }
    }

    override fun read(dest: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        if (rxPos >= rxLen && !refill(timeoutMs)) return 0
        val available = rxLen - rxPos
        val n = minOf(length, available)
        System.arraycopy(rxBuf, rxPos, dest, offset, n)
        rxPos += n
        return n
    }

    /**
     * Pulls one transfer off the bulk IN endpoint. Retries in short slices
     * so a printer that is merely thinking does not look like a failure.
     */
    private fun refill(timeoutMs: Int): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val n = connection.bulkTransfer(endpointIn, rxBuf, 0, rxBuf.size, POLL_SLICE_MS)
            if (n > 0) {
                rxPos = 0
                rxLen = n
                return true
            }
            if (System.currentTimeMillis() >= deadline) {
                logger.log("read timed out after ${timeoutMs}ms")
                return false
            }
        }
    }

    /** Drops any buffered reply bytes; used when recovering from an error. */
    fun discardInput() {
        rxPos = 0
        rxLen = 0
        val scratch = ByteArray(rxBuf.size)
        while (connection.bulkTransfer(endpointIn, scratch, 0, scratch.size, 50) > 0) {
            // keep draining
        }
    }

    override fun close() {
        runCatching { connection.releaseInterface(usbInterface) }
        runCatching { connection.close() }
    }

    companion object {
        const val CANON_VENDOR_ID = 0x04A9

        private const val CONTROL_TIMEOUT_MS = 3_000
        private const val WRITE_TIMEOUT_MS = 10_000
        private const val POLL_SLICE_MS = 250

        /**
         * Android's bulkTransfer has historically misbehaved on very large
         * buffers. The framing layer chunks to 4096 anyway; this is a
         * backstop.
         */
        private const val MAX_BULK_TRANSFER = 16 * 1024

        /** Any attached USB device that presents a printer interface. */
        fun findPrinters(manager: UsbManager): List<UsbDevice> =
            manager.deviceList.values.filter { device ->
                (0 until device.interfaceCount).any {
                    device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_PRINTER
                }
            }

        /**
         * Opens [device] and claims its printer interface.
         * @throws CaptException if it has no usable bulk endpoint pair.
         */
        fun open(
            manager: UsbManager,
            device: UsbDevice,
            logger: CaptLogger = CaptLogger.NONE,
        ): UsbCaptTransport {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass != UsbConstants.USB_CLASS_PRINTER) continue

                var epIn: UsbEndpoint? = null
                var epOut: UsbEndpoint? = null
                for (e in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(e)
                    if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (ep.direction == UsbConstants.USB_DIR_IN && epIn == null) epIn = ep
                    if (ep.direction == UsbConstants.USB_DIR_OUT && epOut == null) epOut = ep
                }
                if (epIn == null || epOut == null) {
                    logger.log("interface $i has no bulk in/out pair; skipping")
                    continue
                }

                val conn = manager.openDevice(device)
                    ?: throw CaptException("could not open USB device (permission not granted?)")
                if (!conn.claimInterface(iface, true)) {
                    conn.close()
                    throw CaptException("could not claim the printer interface")
                }
                logger.log(
                    "opened ${device.deviceName} interface $i, " +
                        "bulk in=${epIn.address} out=${epOut.address} " +
                        "packet=${epIn.maxPacketSize}"
                )
                return UsbCaptTransport(
                    connection = conn,
                    usbInterface = iface,
                    endpointIn = epIn,
                    endpointOut = epOut,
                    interfaceNumber = iface.id,
                    altSetting = iface.alternateSetting,
                    logger = logger,
                )
            }
            throw CaptException("no USB printer interface found on ${device.deviceName}")
        }
    }
}
