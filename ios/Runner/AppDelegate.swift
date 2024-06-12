import UIKit
import Flutter
import CoreBluetooth

@main
@objc class AppDelegate: FlutterAppDelegate {
  private var centralManager: CBCentralManager?
  private var peripheral: CBPeripheral?
  private var methodChannel: FlutterMethodChannel?
  private var scanResult: FlutterResult?
  private var connectResult: FlutterResult?
  private var discoveredPeripherals: [String: CBPeripheral] = [:]
  private var servicesList: [[String: Any]] = []

  override func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
  ) -> Bool {
    let controller = window?.rootViewController as! FlutterViewController
    methodChannel = FlutterMethodChannel(name: "ble.flutter.dev/ble", binaryMessenger: controller.binaryMessenger)
    
    centralManager = CBCentralManager(delegate: self, queue: nil)
    
    methodChannel?.setMethodCallHandler { [weak self] call, result in
      guard let self = self else { return }
      switch call.method {
      case "scanForDevices":
        self.scanResult = result
        self.scanForDevices()
      case "connectToDevice":
          
          let deviceId = call.arguments as? [String: String]
          if deviceId != nil{
          self.connectResult = result
              self.connectToDevice(deviceId: deviceId?["deviceId"] ?? "")
        } else {
          result(FlutterError(code: "INVALID_ARGUMENT", message: "Device ID is null", details: nil))
        }
      default:
        result(FlutterMethodNotImplemented)
      }
    }
    
    return super.application(application, didFinishLaunchingWithOptions: launchOptions)
  }
  
  private func scanForDevices() {
    discoveredPeripherals.removeAll()
    centralManager?.scanForPeripherals(withServices: nil, options: nil)
    DispatchQueue.main.asyncAfter(deadline: .now() + 10) {
      self.centralManager?.stopScan()
      let devices = self.discoveredPeripherals.map { ["id": $0.key, "name": $0.value.name ?? "Unknown Device"] }
      self.scanResult?(devices)
      self.scanResult = nil
    }
  }
  
  private func connectToDevice(deviceId: String) {
    if let peripheral = discoveredPeripherals[deviceId] {
      self.servicesList.removeAll()
      centralManager?.connect(peripheral, options: nil)
    } else {
      connectResult?(FlutterError(code: "DEVICE_NOT_FOUND", message: "Device not found", details: nil))
      connectResult = nil
    }
  }
}

extension AppDelegate: CBCentralManagerDelegate {
  func centralManagerDidUpdateState(_ central: CBCentralManager) {
    if central.state != .poweredOn {
      scanResult?(FlutterError(code: "BLUETOOTH_NOT_AVAILABLE", message: "Bluetooth is not available", details: nil))
      scanResult = nil
    }
  }
  
  func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String : Any], rssi RSSI: NSNumber) {
    discoveredPeripherals[peripheral.identifier.uuidString] = peripheral
  }
  
  func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
    self.peripheral = peripheral
    peripheral.delegate = self
    peripheral.discoverServices(nil)
  }
}

extension AppDelegate: CBPeripheralDelegate {
  func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
    if let error = error {
      connectResult?(FlutterError(code: "SERVICE_DISCOVERY_FAILED", message: error.localizedDescription, details: nil))
      connectResult = nil
      return
    }
    
    guard let services = peripheral.services else { return }
    
    for service in services {
      var serviceInfo = [String: Any]()
      serviceInfo["uuid"] = service.uuid.uuidString
      serviceInfo["name"] = service.uuid.uuidString // Placeholder for the actual service name
      serviceInfo["characteristics"] = [[String: Any]]() // Initialize with an empty list
      
      servicesList.append(serviceInfo)
      peripheral.discoverCharacteristics(nil, for: service)
    }
  }
  
  func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
    if let error = error {
      print("Failed to discover characteristics: \(error.localizedDescription)")
      return
    }
    
    guard let characteristics = service.characteristics else { return }
    
    for characteristic in characteristics {
     if characteristic.properties.contains(.read) {
      peripheral.readValue(for: characteristic)
         print("read characteristic\(characteristic.uuid.uuidString)")
    } else {
      print("Characteristic \(characteristic.uuid.uuidString) does not allow reading.")
    }
    }
  }
  
  func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
    if let error = error {
      print("Failed to read characteristic: \(error.localizedDescription)")
      return
    }
    
    guard let value = characteristic.value else { return }
     
      let hexString = value.map { String(format: "%02hhx", $0) }.joined()
      print(hexString)
    let characteristicInfo: [String: Any] = [
      "uuid": characteristic.uuid.uuidString,
      "name": characteristic.uuid.uuidString, // Placeholder for the actual characteristic name (not imlemented)
      "value": hexString
    ]
      print(characteristicInfo)
    
    for (index, service) in servicesList.enumerated() {
      if service["uuid"] as? String == characteristic.service?.uuid.uuidString {
        var characteristicsList = service["characteristics"] as? [[String: Any]] ?? []
        characteristicsList.append(characteristicInfo)
        servicesList[index]["characteristics"] = characteristicsList
      }
    }

                  connectResult?(servicesList)
                  connectResult = nil
                  centralManager?.cancelPeripheralConnection(peripheral)
              
  }
}
