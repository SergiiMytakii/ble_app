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
import com.example.ble_app.BluetoothUtils
import kotlin.collections.mutableMapOf 


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
        var retryCount = 0
        val maxRetries = 3

        fun connect() {
            bluetoothGatt = device.connectGatt(this, false, object : BluetoothGattCallback() {
                val servicesList = mutableListOf<Map<String, Any>>()
                var characteristicsToRead = 0
                var characteristicsRead = 0
                val servicesListMap = mutableMapOf<String, mutableMapOf<String, Any>>()

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
                            if (characteristicsRead != characteristicsToRead) {
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
/*
{0000200-20220: 
    {uuid: 0000200-20220,
    name: service,
    characteristics: [
        {uuid:00180-000,
        name: device name,
        value: iphone},
         {uuid:00180-000,
        name: battery,
        value: 80%},
    ]},
 0000-10110:   {uuid: 0000-10110,
    name: service,
    characteristics: [
        uuid:00180-000,
        name: device name,
        value: iphone
    }

            }
 */ 

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    println("onServicesDiscovered: status = $status")
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        for (service in gatt.services) {
                            val serviceInfo = mutableMapOf<String, Any>()
                            serviceInfo["uuid"] = service.uuid.toString()
                            serviceInfo["name"] = BluetoothUtils.getServiceName(service.uuid)

                            val characteristicsList = mutableListOf<Map<String, Any>>()
                            for (characteristic in service.characteristics) {
                                println("Reading characteristic: ${characteristic.uuid}")
                                characteristicsToRead++
                                gatt.readCharacteristic(characteristic)
                            }
                            serviceInfo["characteristics"] = characteristicsList //empty now
                            // servicesList.add(serviceInfo)
                            servicesListMap[service.uuid.toString()] = serviceInfo
                        }
                        println(" servicesList after Reading characteristic init'")
                   

                        if (characteristicsToRead == 0) {
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
                    println("onCharacteristicRead: status = $status, characteristic = ${characteristic.uuid}")
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        val characteristicInfo = mutableMapOf<String, Any>()
                        characteristicInfo["uuid"] = characteristic.uuid.toString()
                        characteristicInfo["name"] = BluetoothUtils.getCharacteristicName(characteristic.uuid)
                        characteristicInfo["value"] = characteristic.value

                        // Add characteristic info to the respective service's characteristics list
                        val serviceUuid = characteristic.service.uuid.toString()
                        servicesListMap[serviceUuid]["characteristics"]?.add(characteristicInfo)
                    } else {
                        println("Failed to read characteristic: ${characteristic.uuid}")
                    }

                    characteristicsRead++
                    println("Characteristics read: $characteristicsRead/$characteristicsToRead")
                    if (characteristicsRead == characteristicsToRead) {
                        println("All characteristics read. Returning services list.")
                        result.success(servicesListMap.values.toList())
                        gatt.disconnect()
                    }
                }

                override fun onDescriptorRead(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
                    println("onDescriptorRead: status = $status, descriptor = ${descriptor?.uuid}")
                }

                override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
                    println("onDescriptorWrite: status = $status, descriptor = ${descriptor?.uuid}")
                }

                override fun onReliableWriteCompleted(gatt: BluetoothGatt?, status: Int) {
                    println("onReliableWriteCompleted: status = $status")
                }

                override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
                    println("onReadRemoteRssi: rssi = $rssi, status = $status")
                }
            })
        }

        connect()
    } else {
        result.error("PERMISSION_DENIED", "Bluetooth permissions are not granted", null)
    }
}




}
