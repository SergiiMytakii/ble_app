package com.example.ble_app

import android.Manifest
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.NonNull
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.util.*

class MainActivity : FlutterActivity() {
    private val CHANNEL = "ble.flutter.dev/ble"
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothGatt: BluetoothGatt? = null
    private val REQUEST_CODE_PERMISSIONS = 1
    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.BLUETOOTH,
        Manifest.permission.BLUETOOTH_ADMIN,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )
    private val SCAN_PERIOD: Long = 10000 // 10 seconds

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (allPermissionsGranted()) {
            configureFlutterEngine(flutterEngine!!)
        } else {
            ActivityCompat.requestPermissions(
                this,
                REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS
            )
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            this, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                configureFlutterEngine(flutterEngine!!)
            } else {
                // TODO: Handle the permission denial
            }
        }
    }

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "scanForDevices" -> scanForDevices(result)
                "connectToDevice" -> {
                    val deviceId = call.argument<String>("deviceId")
                    if (deviceId != null) {
                        connectToDevice(deviceId, result)
                    } else {
                        result.error("INVALID_ARGUMENT", "Device ID is null", null)
                    }
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun scanForDevices(result: MethodChannel.Result) {
        if (allPermissionsGranted()) {
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bluetoothAdapter = bluetoothManager.adapter

            val scannedDevices = mutableListOf<Map<String, String>>()

            val leScanCallback = BluetoothAdapter.LeScanCallback { device, _, _ ->
                val deviceInfo = mapOf(
                    "id" to device.address,
                    "name" to (device.name ?: "Unknown Device")
                )
                if (!scannedDevices.any { it["id"] == device.address }) {
                    scannedDevices.add(deviceInfo)
                }
            }

            bluetoothAdapter.startLeScan(leScanCallback)

            // Stop the scan after the SCAN_PERIOD
            Handler(Looper.getMainLooper()).postDelayed({
                bluetoothAdapter.stopLeScan(leScanCallback)
                result.success(scannedDevices)
            }, SCAN_PERIOD)
        } else {
            result.error("PERMISSION_DENIED", "Bluetooth permissions are not granted", null)
        }
    }

    private fun connectToDevice(deviceId: String, result: MethodChannel.Result) {
    if (allPermissionsGranted()) {
        val device = bluetoothAdapter.getRemoteDevice(deviceId)
        bluetoothGatt = device.connectGatt(this, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    gatt.discoverServices()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val servicesList = mutableListOf<Map<String, Any>>()

                for (service in gatt.services) {
                    val serviceInfo = mutableMapOf<String, Any>()
                    serviceInfo["uuid"] = service.uuid.toString()
                    serviceInfo["name"] = getServiceName(service.uuid)

                    val characteristicsList = mutableListOf<Map<String, String>>()
                    for (characteristic in service.characteristics) {
                        val characteristicInfo = mutableMapOf<String, String>()
                        characteristicInfo["uuid"] = characteristic.uuid.toString()
                        characteristicInfo["name"] = getCharacteristicName(characteristic.uuid)
                        characteristicsList.add(characteristicInfo)
                    }
                    serviceInfo["characteristics"] = characteristicsList
                    servicesList.add(serviceInfo)
                }

                result.success(servicesList)
            }
        })
    } else {
        result.error("PERMISSION_DENIED", "Bluetooth permissions are not granted", null)
    }
}


    private fun getServiceName(uuid: UUID): String {
        return when (uuid) {
            UUID.fromString("00001800-0000-1000-8000-00805f9b34fb") -> "Generic Access"
            UUID.fromString("00001801-0000-1000-8000-00805f9b34fb") -> "Generic Attribute"
            UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb") -> "Device Information"

            else -> "Unknown Service"
        }
    }

    private fun getCharacteristicName(uuid: UUID): String {
        return when (uuid) {
            UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb") -> "Device Name"
            UUID.fromString("00002a01-0000-1000-8000-00805f9b34fb") -> "Appearance"
            UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb") -> "Manufacturer Name String"
            else -> "Unknown Characteristic"
        }
    }
}
