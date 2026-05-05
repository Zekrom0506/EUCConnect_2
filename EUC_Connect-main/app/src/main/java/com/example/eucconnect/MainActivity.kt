package com.example.eucconnect

import android.Manifest
import android.view.KeyEvent
import android.bluetooth.BluetoothAdapter
import android.media.AudioManager
import android.media.ToneGenerator
import android.bluetooth.BluetoothGattService
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.eucconnect.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bleManager: BleManager
    private lateinit var deviceAdapter: DeviceAdapter

    /** Tracks the actual headlight state (toggled by the user via the headlight button). */
    private var headlightOn = false

    /** Tracks the actual side-lights state. */
    private var sideLightsOn = true

    /** Tracks whether the blinker loop is currently active. */
    private var blinkerRunning = false

    private val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            startScanning()
        } else {
            Toast.makeText(this, "Bluetooth permissions are required", Toast.LENGTH_LONG).show()
        }
    }

    private fun playBeep() {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        } catch (e: Exception) {
            // ignore if audio not available
        }
    }

    private val bluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (bleManager.isBluetoothEnabled()) {
            checkPermissionsAndScan()
        } else {
            Toast.makeText(this, "Bluetooth must be enabled", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bleManager = BleManager(this)
        setupBleCallbacks()

        deviceAdapter = DeviceAdapter { device -> onDeviceClicked(device) }

        binding.rvDevices.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = deviceAdapter
        }

        // Scan / Stop Scan button
        binding.btnScan.setOnClickListener {
            if (binding.btnScan.text == "Stop Scan") {
                bleManager.stopScan()
            } else {
                deviceAdapter.clear()
                binding.tvDeviceCount.text = "Devices found: 0"
                checkPermissionsAndScan()
            }
        }

        // Disconnect button
        binding.btnDisconnect.setOnClickListener {
            bleManager.disconnect()
        }

        // Headlight button
        // Headlight button
        binding.btnHeadlight.setOnClickListener {
            headlightOn = !headlightOn
            bleManager.setHeadlight(headlightOn)
            updateHeadlightButton()
            playBeep()  // beep only on manual press
        }

        // Side LEDs button
        binding.btnSideLights.setOnClickListener {
            sideLightsOn = !sideLightsOn
            bleManager.setSideLights(sideLightsOn)
            updateSideLightsButton()
        }

        // Blinkers button – pass current headlight state so it can be restored
        // Blinkers button – toggle blinker loop on/off
        binding.btnBlinkers.setOnClickListener {
            blinkerRunning = !blinkerRunning
            bleManager.toggleBlinker()
            updateBlinkerButton()
        }

        // Bell button
        binding.btnBell.setOnClickListener {
            bleManager.playBell()
            Toast.makeText(this, "Ding ding!", Toast.LENGTH_SHORT).show()
        }

        // Start in disconnected state
        showScanPanel()
        setWheelControlsEnabled(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.cleanup()
    }

    // ── UI panel helpers ─────────────────────────────────────────────────────

    /** Show the device-scan list; hide the connected controls panel. */
    private fun showScanPanel() {
        binding.layoutScanPanel.visibility = View.VISIBLE
        binding.layoutConnectedPanel.visibility = View.GONE
    }

    /** Hide the device-scan list; show the connected controls panel. */
    private fun showConnectedPanel() {
        binding.layoutScanPanel.visibility = View.GONE
        binding.layoutConnectedPanel.visibility = View.VISIBLE
    }

    // ── Button appearance helpers ─────────────────────────────────────────────

    private fun updateHeadlightButton() {
        if (headlightOn) {
            binding.btnHeadlight.setBackgroundColor(0xFF0D47A1.toInt())
            binding.tvHeadlightState.text = "ON"
            binding.tvHeadlightState.setTextColor(0xFF4CAF50.toInt())
        } else {
            binding.btnHeadlight.setBackgroundColor(0xFF1E3A5F.toInt())
            binding.tvHeadlightState.text = "OFF"
            binding.tvHeadlightState.setTextColor(0xFF888888.toInt())
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            bleManager.playBell()
            return true  // consume the event so volume doesn't change
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun updateBlinkerButton() {
        if (blinkerRunning) {
            binding.btnBlinkers.setBackgroundColor(0xFFF57F17.toInt()) // amber when active
        } else {
            binding.btnBlinkers.setBackgroundColor(0xFF1E3A5F.toInt()) // default blue-dark
        }
    }

    private fun updateSideLightsButton() {
        if (sideLightsOn) {
            binding.btnSideLights.setBackgroundColor(0xFF0D47A1.toInt())
            binding.tvSideLightsState.text = "ON"
            binding.tvSideLightsState.setTextColor(0xFF4CAF50.toInt())
        } else {
            binding.btnSideLights.setBackgroundColor(0xFF1E3A5F.toInt())
            binding.tvSideLightsState.text = "OFF"
            binding.tvSideLightsState.setTextColor(0xFF888888.toInt())
        }
    }

    // ── BLE callbacks ─────────────────────────────────────────────────────────

    private fun setupBleCallbacks() {

        bleManager.onDeviceFound = { device ->
            runOnUiThread {
                deviceAdapter.addOrUpdateDevice(device)
                val count = binding.rvDevices.adapter?.itemCount ?: 0
                binding.tvDeviceCount.text = "Devices found: $count"
            }
        }

        bleManager.onConnectionStateChanged = { connected, deviceName ->
            runOnUiThread {
                if (connected) {
                    binding.tvStatus.text = "✅ Connected to $deviceName"
                    binding.tvStatus.setTextColor(0xFF4CAF50.toInt())
                    binding.btnDisconnect.isEnabled = true
                    setWheelControlsEnabled(true)
                    showConnectedPanel()           // ← switch panel
                    Toast.makeText(
                        this,
                        "Connected to $deviceName!",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    binding.tvStatus.text = "❌ Disconnected"
                    binding.tvStatus.setTextColor(0xFFFF5722.toInt())
                    binding.btnDisconnect.isEnabled = false
                    setWheelControlsEnabled(false)
                    showScanPanel()                // ← switch panel back
                    binding.tvSpeedValue.text = "-- km/h"
                    binding.tvBatteryValue.text = "--%"
                    // Reset light states for next connection
                    headlightOn = false
                    sideLightsOn = true
                    blinkerRunning = false
                    updateHeadlightButton()
                    updateSideLightsButton()
                    updateBlinkerButton()
                }
            }
        }

        bleManager.onTelemetryPacket = { packet ->
            runOnUiThread {
                packet.speedKmh?.let { speed ->
                    binding.tvSpeedValue.text = String.format("%.1f km/h", speed)
                }
                packet.batteryPercent?.let { percent ->
                    binding.tvBatteryValue.text = "$percent%"
                }
            }
        }

        bleManager.onError = { error ->
            runOnUiThread {
                binding.tvStatus.text = "⚠ $error"
                binding.tvStatus.setTextColor(0xFFFF5722.toInt())
                Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
            }
        }

        bleManager.onCommandStatus = { status ->
            runOnUiThread {
                Toast.makeText(this, status, Toast.LENGTH_SHORT).show()
            }
        }

        bleManager.onScanningStateChanged = { scanning ->
            runOnUiThread {
                if (scanning) {
                    binding.btnScan.text = "Stop Scan"
                    binding.progressScanning.visibility = View.VISIBLE
                    binding.tvStatus.text = "🔍 Scanning..."
                    binding.tvStatus.setTextColor(0xFF2196F3.toInt())
                } else {
                    binding.btnScan.text = "Scan"
                    binding.progressScanning.visibility = View.GONE
                    if (binding.tvStatus.text.toString().contains("Scanning")) {
                        binding.tvStatus.text = "Scan complete"
                        binding.tvStatus.setTextColor(0xFFAAAAAA.toInt())
                    }
                }
            }
        }
    }

    // ── Navigation helpers ────────────────────────────────────────────────────

    private fun checkPermissionsAndScan() {
        if (!bleManager.isBluetoothEnabled()) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            bluetoothEnableLauncher.launch(enableBtIntent)
            return
        }

        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            startScanning()
        } else {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun startScanning() {
        bleManager.startScan()
    }

    private fun onDeviceClicked(device: BleDevice) {
        AlertDialog.Builder(this)
            .setTitle("Connect to ${device.name}?")
            .setMessage(
                "Address: ${device.address}\n" +
                        "Signal: ${device.rssi} dBm (${device.signalStrength()})"
            )
            .setPositiveButton("Connect") { _, _ ->
                binding.tvStatus.text = "Connecting to ${device.name}..."
                binding.tvStatus.setTextColor(0xFFFFC107.toInt())
                bleManager.connect(device.address)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setWheelControlsEnabled(enabled: Boolean) {
        val alpha = if (enabled) 1.0f else 0.4f
        binding.btnHeadlight.isEnabled = enabled
        binding.btnHeadlight.alpha = alpha
        binding.btnSideLights.isEnabled = enabled
        binding.btnSideLights.alpha = alpha
        binding.btnBlinkers.isEnabled = enabled
        binding.btnBlinkers.alpha = alpha
        binding.btnBell.isEnabled = enabled
        binding.btnBell.alpha = alpha
    }
}