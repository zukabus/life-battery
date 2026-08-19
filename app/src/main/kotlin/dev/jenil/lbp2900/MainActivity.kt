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
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import dev.jenil.capt.CaptLogger
import dev.jenil.capt.PageGeometry
import dev.jenil.capt.PageRange
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pick a PDF, adjust the handful of settings this printer actually
 * supports, press Print, watch a log.
 *
 * Still not a native PrintService — that remains the eventual goal — but
 * the settings here cover what a normal print dialog would offer for a
 * printer with this few capabilities.
 */
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var printButton: Button
    private lateinit var settingsPanel: LinearLayout

    private lateinit var paperSpinner: Spinner
    private lateinit var scaleSpinner: Spinner
    private lateinit var mediaSpinner: Spinner
    private lateinit var copiesField: EditText
    private lateinit var rangeField: EditText
    private lateinit var tonerSaveBox: CheckBox

    private val handler = Handler(Looper.getMainLooper())
    private val logLines = StringBuilder()
    private val timestamps = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private var selectedUri: Uri? = null
    private var documentPages = 0
    private var printing = false

    private val logger = CaptLogger { line ->
        handler.post {
            logLines.append(timestamps.format(Date())).append("  ").append(line).append('\n')
            logView.text = logLines
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private val controller by lazy { PrintController(this, logger) }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
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

    private fun handleIncomingIntent(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }
            Intent.ACTION_VIEW -> intent.data
            else -> null
        } ?: return
        setDocument(uri)
    }

    private fun setDocument(uri: Uri) {
        selectedUri = uri
        documentPages = runCatching { controller.pageCount(uri) }.getOrDefault(0)
        logger.log("opened ${uri.lastPathSegment ?: uri} ($documentPages page(s))")
    }

    // ---- UI ---------------------------------------------------------------

    private val dp: Int get() = (8 * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp * 2, dp * 2, dp * 2, dp * 2)
        }

        statusView = TextView(this).apply { textSize = 14f }
        root.addView(statusView)

        settingsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp, 0, dp)
        }

        paperSpinner = spinner(PageGeometry.ALL.map { it.name })
        scaleSpinner = spinner(ScaleMode.entries.map { it.label })
        mediaSpinner = spinner(Media.entries.map { it.label })

        copiesField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("1")
            hint = "1"
        }
        rangeField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "all pages"
        }
        tonerSaveBox = CheckBox(this).apply { text = "Toner save (lighter, uses less toner)" }

        settingsPanel.addView(labelled("Paper size", paperSpinner))
        settingsPanel.addView(labelled("Scaling", scaleSpinner))
        settingsPanel.addView(labelled("Paper type", mediaSpinner))
        settingsPanel.addView(labelled("Copies", copiesField))
        settingsPanel.addView(labelled("Pages (e.g. 1-3,5)", rangeField))
        settingsPanel.addView(tonerSaveBox)
        root.addView(settingsPanel)

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

        val scroller = ScrollView(this).apply { isFillViewport = true }
        scroller.addView(root, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(scroller)

        logger.log("ready. Connect the printer with an OTG cable and pick a PDF.")
    }

    private fun spinner(items: List<String>): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(
            this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items,
        )
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) =
                refreshStatus()

            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
    }

    private fun labelled(label: String, field: View): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@MainActivity).apply {
            text = label
            textSize = 13f
            width = (140 * resources.displayMetrics.density).toInt()
        })
        addView(field, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    }

    private fun currentSettings() = PrintSettings(
        paperSize = PageGeometry.ALL[paperSpinner.selectedItemPosition].name,
        copies = copiesField.text.toString().trim().toIntOrNull()?.coerceIn(1, 99) ?: 1,
        tonerSave = tonerSaveBox.isChecked,
        scaleMode = ScaleMode.entries[scaleSpinner.selectedItemPosition],
        media = Media.entries[mediaSpinner.selectedItemPosition],
        pageRange = rangeField.text.toString().trim(),
    )

    private fun refreshStatus() {
        val device = controller.findPrinter()
        val printerText = if (device == null) {
            "No USB printer detected — check the OTG cable and that the printer is on."
        } else {
            "Printer: %s".format(device.productName ?: device.deviceName)
        }
        val fileText = selectedUri?.let {
            "\nFile: ${it.lastPathSegment ?: it}" +
                if (documentPages > 0) "  ($documentPages page${if (documentPages == 1) "" else "s"})" else ""
        } ?: "\nNo file selected."
        statusView.text = printerText + fileText
        printButton.isEnabled = device != null && selectedUri != null && !printing
    }

    private fun pickFile() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/pdf"
            },
            REQUEST_PICK_PDF,
        )
    }

    @Deprecated("startActivityForResult is fine for a single picker in a plain Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_PDF && resultCode == RESULT_OK) {
            data?.data?.let { setDocument(it) }
            refreshStatus()
        }
    }

    private fun copyLog() {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("LBP2900 log", logLines.toString()))
        Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
    }

    // ---- printing ---------------------------------------------------------

    private fun requestPermissionThenPrint() {
        val device = controller.findPrinter() ?: run {
            logger.log("no printer attached")
            return
        }

        // Validate the page range before touching the printer, so a typo is
        // a message on screen rather than a half-finished job.
        try {
            PageRange.parse(currentSettings().pageRange, maxOf(documentPages, 1))
        } catch (e: IllegalArgumentException) {
            Toast.makeText(this, "Pages: ${e.message}", Toast.LENGTH_LONG).show()
            logger.log("invalid page range: ${e.message}")
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
            manager.requestPermission(
                device,
                PendingIntent.getBroadcast(
                    this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags,
                ),
            )
        }
    }

    private fun startPrinting() {
        val uri = selectedUri ?: run { setBusy(false); return }
        val device = controller.findPrinter() ?: run {
            logger.log("printer disappeared")
            setBusy(false)
            return
        }
        val settings = currentSettings()
        Thread({
            try {
                controller.print(device, uri, settings)
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
        for (i in 0 until settingsPanel.childCount) {
            settingsPanel.getChildAt(i).isEnabled = !busy
        }
        if (!busy) refreshStatus()
    }

    private companion object {
        const val ACTION_USB_PERMISSION = "dev.jenil.lbp2900.USB_PERMISSION"
        const val REQUEST_PICK_PDF = 1
    }
}
