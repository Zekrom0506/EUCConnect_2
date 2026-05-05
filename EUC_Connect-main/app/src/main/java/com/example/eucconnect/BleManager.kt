package com.example.eucconnect

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

class BleManager(private val context: Context) {

    companion object {
        private const val TAG = "BleManager"
        private const val SCAN_TIMEOUT_MS = 30_000L
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val INMOTION_CLASSIC_NOTIFY_UUID: UUID =
            UUID.fromString("0000ffe4-0000-1000-8000-00805f9b34fb")
        private val INMOTION_CLASSIC_WRITE_UUID: UUID =
            UUID.fromString("0000ffe9-0000-1000-8000-00805f9b34fb")
        private val INMOTION_V2_NOTIFY_UUID: UUID =
            UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        private val INMOTION_V2_WRITE_UUID: UUID =
            UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        private val BATTERY_LEVEL_UUID: UUID =
            UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    }

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var bluetoothLeScanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner

    private var bluetoothGatt: BluetoothGatt? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var isScanning = false
    private var isWritingCommand = false
    private var commandDelayMs = 80L
    private val handler = Handler(Looper.getMainLooper())
    private val commandQueue = ArrayDeque<ByteArray>()
    private val packetAssembler = InMotionPacketAssembler()

    private val scanTimeoutRunnable = Runnable {
        stopScan()
    }

    private val telemetryPollRunnable = object : Runnable {
        override fun run() {
            if (isConnected()) {
                sendCommand(InMotionProtocol.requestFastTelemetry(), silent = true)
                handler.postDelayed(this, 1_000)
            }
        }
    }

    var onDeviceFound: ((BleDevice) -> Unit)? = null
    var onConnectionStateChanged: ((connected: Boolean, deviceName: String?) -> Unit)? = null
    var onServicesDiscovered: ((List<BluetoothGattService>) -> Unit)? = null
    var onTelemetryPacket: ((TelemetryPacket) -> Unit)? = null
    var onCommandStatus: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onScanningStateChanged: ((Boolean) -> Unit)? = null

    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (isScanning) return

        if (!isBluetoothEnabled()) {
            onError?.invoke("Bluetooth is not enabled")
            return
        }

        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner

        if (bluetoothLeScanner == null) {
            onError?.invoke("BLE Scanner not available")
            return
        }

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        try {
            bluetoothLeScanner?.startScan(null, scanSettings, scanCallback)
            isScanning = true
            onScanningStateChanged?.invoke(true)
            Log.d(TAG, "Scan started")

            handler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start scan", e)
            onError?.invoke("Failed to start scan: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!isScanning) return
        try {
            bluetoothLeScanner?.stopScan(scanCallback)
            isScanning = false
            onScanningStateChanged?.invoke(false)
            handler.removeCallbacks(scanTimeoutRunnable)
            Log.d(TAG, "Scan stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop scan", e)
        }
    }

    private val scanCallback = object : ScanCallback() {

        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val advertisedName = result.scanRecord?.deviceName
            val deviceName = when {
                !advertisedName.isNullOrBlank() -> advertisedName
                !device.name.isNullOrBlank() -> device.name
                else -> return
            }

            val bleDevice = BleDevice(
                name = deviceName,
                address = device.address,
                rssi = result.rssi
            )
            onDeviceFound?.invoke(bleDevice)
        }

        override fun onScanFailed(errorCode: Int) {
            val errorMessage = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "Scan already started"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "App registration failed"
                SCAN_FAILED_INTERNAL_ERROR -> "Internal error"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "BLE not supported"
                else -> "Unknown error: $errorCode"
            }
            Log.e(TAG, "Scan failed: $errorMessage")
            isScanning = false
            onScanningStateChanged?.invoke(false)
            onError?.invoke("Scan failed: $errorMessage")
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(deviceAddress: String) {
        stopScan()

        val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)
        if (device == null) {
            onError?.invoke("Device not found")
            return
        }

        disconnect()

        Log.d(TAG, "Connecting to ${device.name} ($deviceAddress)")

        bluetoothGatt = device.connectGatt(
            context,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceName = gatt.device.name ?: "Unknown"

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected to $deviceName")
                    Handler(Looper.getMainLooper()).post {
                        onConnectionStateChanged?.invoke(true, deviceName)
                    }
                    Handler(Looper.getMainLooper()).postDelayed({
                        gatt.discoverServices()
                    }, 500)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from $deviceName")
                    Handler(Looper.getMainLooper()).post {
                        onConnectionStateChanged?.invoke(false, deviceName)
                    }
                    gatt.close()
                    bluetoothGatt = null
                    commandCharacteristic = null
                    packetAssembler.reset()
                    stopTelemetryPolling()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val services = gatt.services
                Log.d(TAG, "Discovered ${services.size} services")

                for (service in services) {
                    Log.d(TAG, "Service: ${service.uuid}")
                    for (characteristic in service.characteristics) {
                        val properties = mutableListOf<String>()
                        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0)
                            properties.add("READ")
                        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
                            properties.add("WRITE")
                        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                            properties.add("NOTIFY")
                        Log.d(TAG, "  Char: ${characteristic.uuid} [${properties.joinToString(", ")}]")
                    }
                }

                Handler(Looper.getMainLooper()).post {
                    onServicesDiscovered?.invoke(services)
                }
                commandCharacteristic = findCommandCharacteristic(services)
                enableTelemetryCharacteristics(gatt, services)
                if (commandCharacteristic == null) {
                    Handler(Looper.getMainLooper()).post {
                        onError?.invoke("No writable BLE characteristic found for wheel commands")
                    }
                } else {
                    Handler(Looper.getMainLooper()).postDelayed({
                        sendCommand(InMotionProtocol.password())
                        startTelemetryPolling()
                    }, 1_000)
                }
            } else {
                Log.e(TAG, "Service discovery failed: $status")
                Handler(Looper.getMainLooper()).post {
                    onError?.invoke("Service discovery failed")
                }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                publishTelemetry(characteristic, value)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            Log.d(TAG, "Char changed: ${characteristic.uuid}")
            publishTelemetry(characteristic, value)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            isWritingCommand = false
            commandDelayMs = 80L
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Handler(Looper.getMainLooper()).post {
                    onError?.invoke("Command write failed: $status")
                }
            }
            handler.postDelayed({
                writeNextCommand()
            }, commandDelayMs)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableTelemetryCharacteristics(
        gatt: BluetoothGatt,
        services: List<BluetoothGattService>
    ) {
        for (service in services) {
            for (characteristic in service.characteristics) {
                if (shouldSubscribe(characteristic)) {
                    gatt.setCharacteristicNotification(characteristic, true)
                    characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)?.let { descriptor ->
                        writeDescriptor(
                            gatt,
                            descriptor,
                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        )
                    }
                } else if (characteristic.uuid == BATTERY_LEVEL_UUID &&
                    characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0
                ) {
                    gatt.readCharacteristic(characteristic)
                }
            }
        }
    }

    private fun shouldSubscribe(characteristic: BluetoothGattCharacteristic): Boolean {
        val canNotify = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        if (!canNotify) return false

        return characteristic.uuid == INMOTION_CLASSIC_NOTIFY_UUID ||
                characteristic.uuid == INMOTION_V2_NOTIFY_UUID ||
                characteristic.uuid == BATTERY_LEVEL_UUID
    }

    private fun findCommandCharacteristic(
        services: List<BluetoothGattService>
    ): BluetoothGattCharacteristic? {
        return services
            .flatMap { it.characteristics }
            .firstOrNull { it.uuid == INMOTION_CLASSIC_WRITE_UUID && it.canWriteCommand() }
            ?: services
                .flatMap { it.characteristics }
                .firstOrNull { it.uuid == INMOTION_V2_WRITE_UUID && it.canWriteCommand() }
            ?: services
                .flatMap { it.characteristics }
            .firstOrNull { characteristic ->
                characteristic.canWriteCommand()
            }
            ?: services
                .flatMap { it.characteristics }
                .firstOrNull { characteristic ->
                    characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
                }
    }

    private fun BluetoothGattCharacteristic.canWriteCommand(): Boolean {
        return properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
    }

    @SuppressLint("MissingPermission")
    private fun sendCommand(command: ByteArray, silent: Boolean = false): Boolean {
        if (commandCharacteristic == null || bluetoothGatt == null) {
            if (!silent) {
                onError?.invoke("Connect to the wheel before sending commands")
            }
            return false
        }
        commandQueue.addLast(command)
        writeNextCommand()
        return true
    }

    @SuppressLint("MissingPermission")
    private fun writeNextCommand() {
        val gatt = bluetoothGatt ?: return
        val characteristic = commandCharacteristic ?: return
        if (isWritingCommand || commandQueue.isEmpty()) return

        val command = commandQueue.removeFirst()
        characteristic.writeType =
            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
        isWritingCommand = characteristic.writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        val accepted = writeCharacteristic(
            gatt = gatt,
            characteristic = characteristic,
            value = command,
            writeType = characteristic.writeType
        )
        if (!accepted) {
            isWritingCommand = false
            commandQueue.addFirst(command)
            commandDelayMs = (commandDelayMs + 150L).coerceAtMost(1_000L)
            handler.postDelayed({
                writeNextCommand()
            }, commandDelayMs)
            Handler(Looper.getMainLooper()).postDelayed({
                onError?.invoke("Command was not accepted by Android BLE stack")
            }, 1_000L)
        } else if (characteristic.writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
            handler.postDelayed({
                isWritingCommand = false
                writeNextCommand()
            }, commandDelayMs)
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeDescriptor(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, value, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
    }

    private fun startTelemetryPolling() {
        stopTelemetryPolling()
        handler.post(telemetryPollRunnable)
    }

    private fun stopTelemetryPolling() {
        handler.removeCallbacks(telemetryPollRunnable)
        commandQueue.clear()
        isWritingCommand = false
    }

    fun setHeadlight(on: Boolean) {
        if (sendCommand(InMotionProtocol.setHeadlight(on))) {
            onCommandStatus?.invoke("Headlight ${if (on) "on" else "off"} command sent")
        }
    }

    fun setSideLights(on: Boolean) {
        if (sendCommand(InMotionProtocol.setSideLights(on))) {
            onCommandStatus?.invoke("Side lights ${if (on) "on" else "off"} command sent")
        }
    }

    fun playBell() {
        if (sendCommand(InMotionProtocol.playBell())) {
            handler.postDelayed({
                sendCommand(InMotionProtocol.playLegacyBell())
            }, 250)
            onCommandStatus?.invoke("Bell commands sent")
        }
    }

    fun blinkSideLights() {
        if (!isConnected()) {
            onError?.invoke("Connect to the wheel before blinking lights")
            return
        }

        repeat(6) { index ->
            handler.postDelayed({
                sendCommand(InMotionProtocol.setSideLights(index % 2 == 0))
            }, index * 300L)
        }
        handler.postDelayed({
            sendCommand(InMotionProtocol.setSideLights(true))
        }, 1_800L)
        onCommandStatus?.invoke("Blinker command sequence sent")
    }

    private fun publishTelemetry(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (characteristic.uuid == INMOTION_CLASSIC_NOTIFY_UUID ||
            characteristic.uuid == INMOTION_V2_NOTIFY_UUID
        ) {
            val packets = packetAssembler.add(value)
            if (packets.isEmpty()) {
                val fragment = TelemetryDecoder.decode(characteristic.uuid, value)
                Log.d(TAG, "Telemetry fragment ${characteristic.uuid}: ${fragment.rawHex}")
                Handler(Looper.getMainLooper()).post {
                    onTelemetryPacket?.invoke(fragment)
                }
            }

            for (assembled in packets) {
                val packet = TelemetryDecoder.decode(characteristic.uuid, assembled)
                Log.d(TAG, "Telemetry ${characteristic.uuid}: ${packet.rawHex}")
                Handler(Looper.getMainLooper()).post {
                    onTelemetryPacket?.invoke(packet)
                }
            }
        } else {
            val packet = TelemetryDecoder.decode(characteristic.uuid, value)
            Log.d(TAG, "Telemetry ${characteristic.uuid}: ${packet.rawHex}")
            Handler(Looper.getMainLooper()).post {
                onTelemetryPacket?.invoke(packet)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        bluetoothGatt?.let { gatt ->
            gatt.disconnect()
        }
    }

    fun isConnected(): Boolean {
        return bluetoothGatt != null
    }

    @SuppressLint("MissingPermission")
    fun cleanup() {
        stopScan()
        stopTelemetryPolling()
        bluetoothGatt?.let { gatt ->
            gatt.disconnect()
            gatt.close()
        }
        bluetoothGatt = null
        commandCharacteristic = null
        handler.removeCallbacksAndMessages(null)
    }
}
