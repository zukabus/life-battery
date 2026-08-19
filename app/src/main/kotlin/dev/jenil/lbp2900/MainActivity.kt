package dev.jenil.lbp2900

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.jenil.capt.CaptLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Deliberately plain: pick a PDF, press Print, watch a log.
 *
 * There is no native print-dialog integration yet, and that is on purpose.
 * Nothing here has been tested against real hardware, so the first version
 * optimises for being debuggable — every command and status poll is visible
 * on screen and can be copied out in one tap. Once the pipeline is known
 * good, this becomes an Android PrintService and the UI mostly disappears.
 */
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var printButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private val logLines = StringBuilder()
    private val timestamps = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private var selectedUri: Uri? = null
    private var printing = false

    private val logger = CaptLogger { line ->
        handler.post {
            logLines.append(timestamps.format(Date())).append("  ").append(line).append('\n')
            logView.text = logLines
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private val controller by lazy { PrintController(this, logger) }

    // ---- USB permission ---------------------------------------------------

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted) {
                logger.log("USB permission granted")
                startPrinting()
            } else {
                logger.log("USB permission denied")
                setBusy(false)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbPermissionReceiver, filter)
        }

        handleIncomingIntent(intent)
        refreshStatus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
        refreshStatus()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(usbPermissionReceiver) }
        super.onDestroy()
    }

    /** Accepts a PDF shared or opened from another app. */
    private fun handleIncomingIntent(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }
            Intent.ACTION_VIEW -> intent.data
            else -> null
        } ?: return
        selectedUri = uri
        logger.log("received ${uri.lastPathSegment ?: uri}")
    }

    // ---- UI ---------------------------------------------------------------

    private fun buildUi() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        statusView = TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, 0, pad / 2)
        }
        root.addView(statusView)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        buttonRow.addView(Button(this).apply {
            text = "Choose PDF"
            setOnClickListener { pickFile() }
        })
        printButton = Button(this).apply {
            text = "Print"
            setOnClickListener { requestPermissionThenPrint() }
        }
        buttonRow.addView(printButton)
        buttonRow.addView(Button(this).apply {
            text = "Copy log"
            setOnClickListener { copyLog() }
        })
        root.addView(buttonRow)

        logScroll = ScrollView(this)
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTextColor(Color.DKGRAY)
            setTextIsSelectable(true)
        }
        logScroll.addView(logView)
        root.addView(logScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        setContentView(root, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        logger.log("ready. Connect the printer with an OTG cable and pick a PDF.")
    }

    private fun refreshStatus() {
        val device = controller.findPrinter()
        val printerText = if (device == null) {
            "No USB printer detected — check the OTG cable and that the printer is on."
        } else {
            "Printer: %s (vendor %04X, product %04X)".format(
                device.productName ?: device.deviceName, device.vendorId, device.productId
            )
        }
        val fileText = selectedUri?.let { "\nFile: ${it.lastPathSegment ?: it}" } ?: "\nNo file selected."
        statusView.text = printerText + fileText
        printButton.isEnabled = device != null && selectedUri != null && !printing
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
        }
        startActivityForResult(intent, REQUEST_PICK_PDF)
    }

    @Deprecated("startActivityForResult is fine for a single picker in a plain Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_PDF && resultCode == RESULT_OK) {
            selectedUri = data?.data
            selectedUri?.let { logger.log("selected ${it.lastPathSegment ?: it}") }
            refreshStatus()
        }
    }

    private fun copyLog() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("LBP2900 log", logLines.toString()))
        Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
    }

    // ---- printing ---------------------------------------------------------

    private fun requestPermissionThenPrint() {
        val device = controller.findPrinter()
        if (device == null) {
            logger.log("no printer attached")
            return
        }
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        setBusy(true)
        if (manager.hasPermission(device)) {
            startPrinting()
        } else {
            logger.log("requesting USB permission...")
            // Must be mutable: the system fills in the device and result.
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val pending = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags,
            )
            manager.requestPermission(device, pending)
        }
    }

    private fun startPrinting() {
        val uri = selectedUri ?: run { setBusy(false); return }
        val device = controller.findPrinter() ?: run {
            logger.log("printer disappeared")
            setBusy(false)
            return
        }
        Thread({
            try {
                controller.print(device, uri)
                handler.post { Toast.makeText(this, "Printed", Toast.LENGTH_SHORT).show() }
            } catch (t: Throwable) {
                logger.log("failed: ${t::class.java.simpleName}: ${t.message}")
            } finally {
                handler.post { setBusy(false) }
            }
        }, "capt-print").start()
    }

    private fun setBusy(busy: Boolean) {
        printing = busy
        printButton.isEnabled = !busy
        printButton.text = if (busy) "Printing..." else "Print"
        if (!busy) refreshStatus()
    }

    private companion object {
        const val ACTION_USB_PERMISSION = "dev.jenil.lbp2900.USB_PERMISSION"
        const val REQUEST_PICK_PDF = 1
    }
}
