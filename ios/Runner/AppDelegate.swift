/// The `AppDelegate` class is the main entry point for the iOS app. It handles the initialization of the Bluetooth Central Manager, sets up a Flutter method channel, and implements the necessary delegate methods to handle Bluetooth device discovery, connection, and characteristic updates.
///
/// The class provides the following functionality:
/// - Scans for Bluetooth devices and returns a list of discovered devices
/// - Connects to a specific Bluetooth device by its ID
/// - Discovers services and characteristics of the connected device
/// - Reads the values of the discovered characteristics
///
/// The class uses the `CBCentralManager` and `CBPeripheralDelegate` protocols to interact with the Bluetooth stack on iOS.
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
  
  /// Scans for Bluetooth devices and returns a list of discovered devices.
  ///
  /// This method initiates a Bluetooth scan and waits for 10 seconds before stopping the scan. The discovered devices are then mapped to a list of dictionaries, where each dictionary contains the device ID and name. The list of devices is then returned to the caller via the `scanResult` callback.
  ///
  /// - Note: This method assumes that the necessary Bluetooth permissions have been granted by the user.
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
  
  /// Connects to the Bluetooth device with the specified ID.
  ///
  /// This method first checks if the device with the given ID has been discovered during the previous scan. If the device is found, it connects to the device using the `CBCentralManager` instance. If the device is not found, it calls the `connectResult` callback with a `FlutterError` indicating that the device was not found.
  ///
  /// - Parameter deviceId: The unique identifier of the Bluetooth device to connect to.
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

/// This extension implements the `CBPeripheralDelegate` protocol to handle events related to the discovered Bluetooth services and characteristics.
///
/// - `peripheral(_:didDiscoverServices:)`: Handles the discovery of services on the connected Bluetooth peripheral. It iterates through the discovered services, creates a dictionary for each service, and adds it to the `servicesList` array. It then calls `discoverCharacteristics(_:for:)` to discover the characteristics for each service.
///
/// - `peripheral(_:didDiscoverCharacteristicsFor:error:)`: Handles the discovery of characteristics for a service. It iterates through the discovered characteristics, checks if they allow reading, and reads the value of the characteristic. The characteristic information, including the UUID, name, and value, is then added to the corresponding service in the `servicesList` array.
///
/// - `peripheral(_:didUpdateValueFor:error:)`: Handles the update of a characteristic's value. It converts the characteristic value to a hexadecimal string and adds the characteristic information, including the UUID, name, and value, to the corresponding service in the `servicesList` array. Finally, it calls the `connectResult` callback with the updated `servicesList` and disconnects from the peripheral.
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
