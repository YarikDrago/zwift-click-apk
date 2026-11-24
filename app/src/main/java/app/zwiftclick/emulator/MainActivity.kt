package app.zwiftclick.emulator

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.ViewFlipper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Adapter queries and start calls follow the runtime permission check.
@SuppressLint("MissingPermission")
class MainActivity : Activity() {
    private lateinit var pages: ViewFlipper
    private lateinit var homeStatus: TextView
    private lateinit var connectionStatus: TextView
    private lateinit var gearStatus: TextView
    private lateinit var start: Button
    private lateinit var stop: Button
    private lateinit var rideTests: Button
    private lateinit var gearPad: GearPad
    private lateinit var adjustPad: GearPad
    private lateinit var gearEditor: GearLayoutEditor
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var manager: BluetoothManager
    private lateinit var peripheral: ClickPeripheral

    private var page = Page.HOME
    private var systemBack: OnBackInvokedCallback? = null
    private var visible = false
    private var adapterState = "Checking"
    private var peripheralSupport = "Checking"
    private var gearLayoutKey = ""
    private val gearLayouts by lazy { getSharedPreferences("gear_layouts", MODE_PRIVATE) }
    private val logLines = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) refreshBluetooth()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        manager = getSystemService(BluetoothManager::class.java)
        peripheral = ClickPeripheral(applicationContext, manager, ::log, ::renderStatus)
        pages = ViewFlipper(this).apply {
            background = GlassBackdrop()
            addView(createHome())
            addView(createConnection())
            addView(createGearControls())
            addView(createAdjust())
        }
        loadGearLayout(resources.configuration)
        setContentView(pages)
        showPage(Page.entries.getOrNull(savedInstanceState?.getInt(PAGE_KEY) ?: 0) ?: Page.HOME)
        renderStatus()

        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(adapterReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(adapterReceiver, filter)
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
    }

    override fun onResume() {
        super.onResume()
        refreshBluetooth()
    }

    override fun onStop() {
        visible = false
        peripheral.stop("Advertising and GATT stopped as the app left the foreground")
        super.onStop()
    }

    override fun onDestroy() {
        gearEditor.cancelEdit()
        unregisterSystemBack()
        unregisterReceiver(adapterReceiver)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(PAGE_KEY, page.ordinal)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        loadGearLayout(newConfig)
        if (page == Page.CONNECTION) scrollLog()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (page == Page.HOME) super.onBackPressed() else showPage(Page.HOME)
    }

    private fun showPage(destination: Page) {
        page = destination
        pages.displayedChild = destination.ordinal
        if (destination == Page.HOME) {
            unregisterSystemBack()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && systemBack == null) {
            val callback = OnBackInvokedCallback { showPage(Page.HOME) }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            systemBack = callback
        }
        if (destination == Page.CONNECTION) scrollLog()
    }

    private fun unregisterSystemBack() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            systemBack?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
            systemBack = null
        }
    }

    private fun createHome(): ScrollView {
        val layout = pageLayout().apply { gravity = Gravity.CENTER_VERTICAL }
        layout.addView(TextView(this).apply {
            setText(R.string.home_title)
            setTextColor(GlassUi.text)
            textSize = 34f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(3), 0, dp(3), dp(14))
        })
        homeStatus = TextView(this).apply {
            textSize = 15f
            background = GlassUi.panel(context)
            setPadding(dp(15), dp(12), dp(15), dp(12))
        }
        layout.addView(homeStatus, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(3), 0, dp(3), dp(12))
        })
        layout.addView(
            glassButton(R.string.go, GlassUi.Tone.BLUE).apply {
                textSize = 24f
                setOnClickListener { showPage(Page.GEARS) }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(68))
        )
        val secondary = LinearLayout(this).apply {
            addView(glassButton(R.string.connection).apply {
                setOnClickListener { showPage(Page.CONNECTION) }
            }, LinearLayout.LayoutParams(0, dp(52), 1f))
            addView(glassButton(R.string.adjust).apply {
                setOnClickListener { showPage(Page.ADJUST) }
            }, LinearLayout.LayoutParams(0, dp(52), 1f))
        }
        layout.addView(secondary)
        return ScrollView(this).apply {
            isFillViewport = true
            addView(layout)
        }
    }

    private fun createConnection(): LinearLayout {
        val layout = pageLayout()
        layout.addView(pageHeader(R.string.connection))
        connectionStatus = TextView(this).apply {
            setTextColor(GlassUi.secondaryText)
            textSize = 15f
            background = GlassUi.panel(context)
            setPadding(dp(15), dp(13), dp(15), dp(13))
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        layout.addView(connectionStatus, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(3), dp(10), dp(3), dp(8))
        })
        val controls = LinearLayout(this)
        start = glassButton(R.string.start_advertising, GlassUi.Tone.BLUE).apply {
            setOnClickListener { startWithPermissions() }
        }
        stop = glassButton(R.string.stop_advertising).apply {
            setOnClickListener { peripheral.stop("Advertising and GATT stopped by user") }
        }
        controls.addView(start, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(stop, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        layout.addView(controls)

        val logHeader = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val logTitle = TextView(this).apply {
            setText(R.string.logs)
            setTextColor(GlassUi.text)
            textSize = 16f
            setPadding(dp(3), 0, 0, 0)
            setTypeface(typeface, Typeface.BOLD)
        }
        logHeader.addView(logTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        rideTests = glassButton(R.string.ride_tests).apply {
            setOnClickListener { showRideTests() }
        }
        logHeader.addView(rideTests)
        layout.addView(logHeader)
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(GlassUi.secondaryText)
            setTextIsSelectable(true)
        }
        logScroll = ScrollView(this).apply {
            background = GlassUi.panel(context)
            setPadding(dp(13), dp(12), dp(13), dp(12))
            addView(logView)
        }
        layout.addView(logScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            setMargins(dp(3), dp(5), dp(3), 0)
        })
        return layout
    }

    private fun createGearControls(): LinearLayout {
        val layout = pageLayout()
        layout.addView(pageHeader(R.string.gears))
        gearStatus = gearPageSubtitle()
        layout.addView(gearStatus)
        gearPad = GearPad(this) { peripheral.press(it) }
        addGearPad(layout, gearPad)
        return layout
    }

    private fun createAdjust(): LinearLayout {
        val layout = pageLayout()
        val layoutMenu = glassButton(R.string.adjust_layout_menu).apply {
            setOnClickListener { showLayoutMenu(it) }
        }
        layout.addView(pageHeader(R.string.adjust, layoutMenu))
        layout.addView(gearPageSubtitle().apply { setText(R.string.adjust_help) })
        adjustPad = GearPad(this) { gearEditor.resize(it) }
        gearEditor = GearLayoutEditor(adjustPad, ::saveGearLayout)
        addGearPad(layout, adjustPad)
        return layout
    }

    private fun showLayoutMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            for (choice in ClickButton.entries) {
                menu.add(getString(R.string.adjust_resize, adjustPad.button(choice).text))
                    .setOnMenuItemClickListener {
                        gearEditor.resize(choice)
                        true
                    }
            }
            menu.add(R.string.adjust_reset).setOnMenuItemClickListener {
                gearEditor.cancelEdit()
                saveGearLayout(GearLayout.DEFAULT)
                true
            }
            show()
        }
    }

    private fun loadGearLayout(config: Configuration) {
        val key = if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
        if (key == gearLayoutKey) return
        gearEditor.cancelEdit()
        gearLayoutKey = key
        applyGearLayout(GearLayout.decode(gearLayouts.getString(key, null)))
    }

    private fun saveGearLayout(layout: GearLayout) {
        applyGearLayout(layout)
        gearLayouts.edit().putString(gearLayoutKey, layout.encode()).apply()
    }

    private fun applyGearLayout(layout: GearLayout) {
        gearPad.arrangement = layout
        adjustPad.arrangement = layout
    }

    private fun gearPageSubtitle() = TextView(this).apply {
        setTextColor(GlassUi.secondaryText)
        textSize = 16f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(3), dp(6), dp(3), 0)
    }

    private fun addGearPad(container: LinearLayout, pad: GearPad) {
        container.addView(pad, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(12)
        })
    }

    private fun pageLayout(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(16))
    }

    private fun pageHeader(title: Int, action: View? = null): LinearLayout = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(glassButton(R.string.back).apply {
            setOnClickListener { showPage(Page.HOME) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)))
        addView(TextView(this@MainActivity).apply {
            setText(title)
            setTextColor(GlassUi.text)
            textSize = 23f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(8), 0, 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        action?.let { addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48))) }
    }

    private fun glassButton(label: Int, tone: GlassUi.Tone = GlassUi.Tone.NEUTRAL) = Button(this).apply {
        GlassUi.styleButton(this, tone)
        setText(label)
    }

    private fun showRideTests() {
        val choices = listOf(
            RideButton.LS1 to R.string.gear_ls1,
            RideButton.LS2 to R.string.gear_ls2,
            RideButton.RS1 to R.string.gear_rs1,
            RideButton.RS2 to R.string.gear_rs2,
            RideButton.LEFT_AUX to R.string.gear_left_aux
        )
        PopupMenu(this, rideTests).apply {
            for ((button, label) in choices) {
                menu.add(getString(R.string.gear_test_button, getString(label), button.mask))
                    .setOnMenuItemClickListener {
                        peripheral.press(button)
                        true
                    }
            }
            show()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun missingPermissions(): Array<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyArray()
        return arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            .toTypedArray()
    }

    private fun startWithPermissions() {
        val missing = missingPermissions()
        if (missing.isEmpty()) {
            startPeripheral()
        } else {
            requestPermissions(missing, BLUETOOTH_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != BLUETOOTH_PERMISSIONS) return
        if (missingPermissions().isEmpty()) {
            refreshBluetooth()
            if (visible) startPeripheral()
        } else {
            refreshBluetooth()
            log("Nearby devices permission denied. Grant it in Android Settings > Apps > Permissions.")
        }
    }

    private fun startPeripheral() {
        refreshBluetooth()
        try {
            val adapter = manager.adapter
            if (adapterState != "On" || adapter == null) {
                log("Start requires Bluetooth to be on. Enable it in Android Settings.")
                return
            }
            val advertiser = adapter.bluetoothLeAdvertiser
            if (peripheralSupport == "Supported" && advertiser != null) {
                peripheral.start(adapter, advertiser)
            } else {
                log("Start failed: BLE peripheral advertising unavailable")
            }
        } catch (e: SecurityException) {
            log("Start failed: ${e.message}")
            refreshBluetooth()
        }
    }

    private fun refreshBluetooth() {
        val (newState, newSupport) = try {
            val adapter = manager.adapter
            when {
                adapter == null -> "Unavailable" to "Unsupported"
                missingPermissions().isNotEmpty() -> "Permission needed" to "Waiting for permission"
                else -> {
                    val state = when (adapter.state) {
                        BluetoothAdapter.STATE_ON -> "On"
                        BluetoothAdapter.STATE_OFF -> "Off"
                        BluetoothAdapter.STATE_TURNING_ON -> "Turning on"
                        BluetoothAdapter.STATE_TURNING_OFF -> "Turning off"
                        else -> "Unknown"
                    }
                    val support = when {
                        !packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) -> "Unsupported"
                        state != "On" -> "Enable Bluetooth to check"
                        adapter.bluetoothLeAdvertiser == null -> "Unsupported"
                        else -> "Supported"
                    }
                    state to support
                }
            }
        } catch (e: SecurityException) {
            log("Bluetooth state unavailable: ${e.message}")
            "Permission needed" to "Waiting for permission"
        }
        if (newState != adapterState || newSupport != peripheralSupport) {
            log("Bluetooth adapter: $newState. BLE peripheral: $newSupport")
            adapterState = newState
            peripheralSupport = newSupport
        }
        if (newState != "On") peripheral.stop("Advertising and GATT stopped because Bluetooth is $newState")
        renderStatus()
    }

    private fun renderStatus() {
        val ready = peripheral.gearReady
        val readyLabel = getString(if (ready) R.string.gears_ready else R.string.gears_waiting)
        val status = getString(R.string.gear_status, readyLabel)
        homeStatus.text = status
        gearStatus.text = status
        val statusColor = if (ready) 0xFF9BDFC9.toInt() else GlassUi.secondaryText
        homeStatus.setTextColor(statusColor)
        gearStatus.setTextColor(statusColor)
        connectionStatus.text = getString(
            R.string.status, adapterState, peripheralSupport, peripheral.advertising, peripheral.clientCount, readyLabel
        )
        start.isEnabled = !peripheral.active
        stop.isEnabled = peripheral.active
        rideTests.isEnabled = ready
        for (choice in ClickButton.entries) gearPad.button(choice).isEnabled = ready
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        if (logLines.size == 150) logLines.removeFirst()
        logLines.addLast("${clock.format(Date())} $message")
        logView.text = logLines.joinToString("\n")
        if (page == Page.CONNECTION) scrollLog()
    }

    private fun scrollLog() {
        logScroll.post {
            if (page == Page.CONNECTION) logScroll.scrollTo(0, logView.height)
        }
    }

    private enum class Page { HOME, CONNECTION, GEARS, ADJUST }

    private companion object {
        const val TAG = "ZwiftClickPoC"
        const val BLUETOOTH_PERMISSIONS = 1
        const val PAGE_KEY = "page"
    }
}
