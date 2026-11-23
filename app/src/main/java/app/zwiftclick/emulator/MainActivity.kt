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
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Adapter queries and start calls follow the runtime permission check.
@SuppressLint("MissingPermission")
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var start: Button
    private lateinit var stop: Button
    private val gearButtons = mutableListOf<Button>()
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var manager: BluetoothManager
    private lateinit var peripheral: ClickPeripheral

    private var visible = false
    private var adapterState = "Checking"
    private var peripheralSupport = "Checking"
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
        setContentView(createLayout())
        peripheral = ClickPeripheral(applicationContext, manager, ::log, ::renderStatus)
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
        unregisterReceiver(adapterReceiver)
        super.onDestroy()
    }

    private fun createLayout(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        layout.addView(TextView(this).apply {
            setText(R.string.app_name)
            textSize = 22f
        })
        status = TextView(this).apply { textSize = 16f }
        layout.addView(status)
        val controls = LinearLayout(this)
        start = Button(this).apply {
            setText(R.string.start_advertising)
            setOnClickListener { startWithPermissions() }
        }
        stop = Button(this).apply {
            setText(R.string.stop_advertising)
            setOnClickListener { peripheral.stop("Advertising and GATT stopped by user") }
        }
        controls.addView(start, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(stop, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        layout.addView(controls)

        addGearRow(
            layout,
            getString(R.string.click_minus) to { peripheral.press(ClickButton.MINUS) },
            getString(R.string.click_plus) to { peripheral.press(ClickButton.PLUS) }
        )
        addGearRow(layout, rideChoice(RideButton.LS1, R.string.gear_ls1), rideChoice(RideButton.RS1, R.string.gear_rs1))
        addGearRow(layout, rideChoice(RideButton.LS2, R.string.gear_ls2), rideChoice(RideButton.RS2, R.string.gear_rs2))
        addGearRow(layout, rideChoice(RideButton.LEFT_AUX, R.string.gear_left_aux))
        layout.addView(TextView(this).apply {
            setText(R.string.logs)
            setTypeface(typeface, Typeface.BOLD)
        })
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
        }
        logScroll = ScrollView(this).apply { addView(logView) }
        layout.addView(logScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return layout
    }

    private fun rideChoice(choice: RideButton, label: Int): Pair<String, () -> Unit> =
        getString(R.string.gear_test_button, getString(label), choice.mask) to { peripheral.press(choice) }

    private fun addGearRow(layout: LinearLayout, vararg choices: Pair<String, () -> Unit>) {
        val row = LinearLayout(this)
        for ((label, press) in choices) {
            val button = Button(this).apply {
                isEnabled = false
                text = label
                setOnClickListener { press() }
            }
            gearButtons.add(button)
            row.addView(button, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        layout.addView(row)
    }

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
        val gearReady = peripheral.gearReady
        val gearStatus = getString(if (gearReady) R.string.gears_ready else R.string.gears_waiting)
        status.text = getString(
            R.string.status, adapterState, peripheralSupport, peripheral.advertising, peripheral.clientCount, gearStatus
        )
        start.isEnabled = !peripheral.active
        stop.isEnabled = peripheral.active
        gearButtons.forEach { it.isEnabled = gearReady }
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        if (logLines.size == 150) logLines.removeFirst()
        logLines.addLast("${clock.format(Date())} $message")
        logView.text = logLines.joinToString("\n")
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private companion object {
        const val TAG = "ZwiftClickPoC"
        const val BLUETOOTH_PERMISSIONS = 1
    }
}
