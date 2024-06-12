package com.example.ble_app

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
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
import com.example.ble_app.BluetoothUtils

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
                println("Permission denyed")
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
        val scanner = bluetoothAdapter.bluetoothLeScanner

        val scannedDevices = mutableListOf<Map<String, String>>()

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val deviceInfo = mapOf(
                    "id" to device.address,
                    "name" to (device.name ?: "Unknown Device")
                )
                if (!scannedDevices.any { it["id"] == device.address }) {
                    scannedDevices.add(deviceInfo)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                println("Scan failed with error: $errorCode")
            }
        }

        scanner.startScan(scanCallback)

        Handler(Looper.getMainLooper()).postDelayed({
            scanner.stopScan(scanCallback)
            result.success(scannedDevices)
        }, SCAN_PERIOD)
    } else {
        result.error("PERMISSION_DENIED", "Bluetooth permissions are not granted", null)
    }
}


   private fun connectToDevice(deviceId: String, result: MethodChannel.Result) {
    if (allPermissionsGranted()) {
        val device = bluetoothAdapter.getRemoteDevice(deviceId)
        var retryCount = 0
        val maxRetries = 3

        fun connect() {
            bluetoothGatt = device.connectGatt(this, false, object : BluetoothGattCallback() {
                val servicesList = mutableListOf<Map<String, Any>>()
                var characteristicsToRead = mutableListOf<BluetoothGattCharacteristic>()
                var currentCharacteristicIndex = 0

                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    println("onConnectionStateChange: newState = $newState, status = $status")
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        println("Connected to GATT server. Discovering services...")
                        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        gatt.requestMtu(512) // Request higher MTU size
                        gatt.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        println("Disconnected from GATT server.")
                        if (status == 133 && retryCount < maxRetries) {
                            println("Connection failed with status 133. Retrying... ($retryCount/$maxRetries)")
                            retryCount++
                            connect()
                        } else {
                            if (currentCharacteristicIndex < characteristicsToRead.size) {
                                result.error("DISCONNECTED", "Disconnected before reading all characteristics", null)
                            }
                        }
                    }
                }

                override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                    println("onMtuChanged: mtu = $mtu, status = $status")
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        println("MTU size successfully changed to $mtu")
                    } else {
                        println("Failed to change MTU size")
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    println("onServicesDiscovered: status = $status")
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        for (service in gatt.services) {
                            val serviceInfo = mutableMapOf<String, Any>()
                            serviceInfo["uuid"] = service.uuid.toString()
                            serviceInfo["name"] = BluetoothUtils.getServiceName(service.uuid)

                            val characteristicsList = mutableListOf<Map<String, Any>>()
                            serviceInfo["characteristics"] = characteristicsList
                            servicesList.add(serviceInfo)

                            for (characteristic in service.characteristics) {
                                if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                                    characteristicsToRead.add(characteristic)
                                }
                            }
                        }
                        if (characteristicsToRead.isNotEmpty()) {
                            gatt.readCharacteristic(characteristicsToRead[currentCharacteristicIndex])
                        } else {
                            println("No characteristics to read. Returning services list.")
                            result.success(servicesList)
                            gatt.disconnect()
                        }
                    } else {
                        result.error("SERVICE_DISCOVERY_FAILED", "Failed to discover services", null)
                        gatt.disconnect()
                    }
                }

                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    println("onCharacteristicRead: status = $status, characteristic value = ${characteristic.value}")
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        val characteristicInfo = mutableMapOf<String, Any>()
                        characteristicInfo["uuid"] = characteristic.uuid.toString()
                        characteristicInfo["name"] = BluetoothUtils.getCharacteristicName(characteristic.uuid)
                        characteristicInfo["value"] = characteristic.getStringValue(0)
                    println("characteristicInfo")
                    println(characteristicInfo)
                        // Add characteristic info to the respective service's characteristics list
                        servicesList.forEach { service ->
                            if (service["uuid"] == characteristic.service.uuid.toString()) {
                                val characteristicsList = service["characteristics"] as MutableList<Map<String, Any>>
                                characteristicsList.add(characteristicInfo)
                            }
                        }
                    } else {
                        println("Failed to read characteristic: ${characteristic.uuid}")
                    }

                    currentCharacteristicIndex++
                                            println("currentCharacteristicIndex: $currentCharacteristicIndex")
                    if (currentCharacteristicIndex < characteristicsToRead.size) {
                        gatt.readCharacteristic(characteristicsToRead[currentCharacteristicIndex])
                    } else {
                        println("All characteristics read. Returning services list.")
                        result.success(servicesList)
                        gatt.disconnect()
                    }
                }
            })
        }

        connect()
    } else {
        result.error("PERMISSION_DENIED", "Bluetooth permissions are not granted", null)
    }
}

}
