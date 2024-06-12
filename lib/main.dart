// ignore_for_file: avoid_print

import 'package:ble_app/models/bluetooth_service_model.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:logger/logger.dart';

final logger = Logger();

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return const MaterialApp(
      home: BleHomePage(),
    );
  }
}

class BleHomePage extends StatefulWidget {
  const BleHomePage({super.key});

  @override
  BleHomePageState createState() => BleHomePageState();
}

class BleHomePageState extends State<BleHomePage> {
  static const platform = MethodChannel('ble.flutter.dev/ble');
  List<Map<String, String>> devices = [];
  List<BluetoothService> services = [];
  List<Map<String, String>> characteristics = [];

  /// Scans for available Bluetooth devices and returns a list of their IDs and names.
  ///
  /// This method uses a platform channel to invoke the native Bluetooth functionality and retrieve a list of discovered devices.
  ///
  /// If the scan is successful, the method returns a list of maps, where each map contains the device ID and name.
  ///
  /// If there is an error scanning for devices, the method logs the error message using the [logger] instance and returns an empty list.
  ///
  /// Returns:
  /// A list of maps, where each map contains the device ID and name.
  Future<List<Map<String, String>>> scanForDevices() async {
    try {
      final List<dynamic> result =
          await platform.invokeMethod('scanForDevices');
      return result.map((e) => Map<String, String>.from(e)).toList();
    } on PlatformException catch (e) {
      logger.e("Failed to scan for devices: '${e.message}'.");
      return [];
    }
  }

  /// Connects to the Bluetooth device with the specified [deviceId] and retrieves the list of services and characteristics for that device.
  ///
  /// This method uses a platform channel to invoke the native Bluetooth functionality and retrieve the device's services and characteristics.
  ///
  /// If the connection is successful, the method updates the [services] list with the retrieved services and their characteristics. It then calls the [_showDeviceSettingsModal] method to display a modal bottom sheet with the device settings.
  ///
  /// If there is an error connecting to the device, the method logs the error message using the [logger] instance.
  ///
  /// Parameters:
  /// - [deviceId]: The ID of the Bluetooth device to connect to.
  Future<void> connectToDevice(String deviceId) async {
    try {
      final List<dynamic> result = await platform
          .invokeMethod('connectToDevice', {'deviceId': deviceId});

      services = result.map((service) {
        List<Characteristic> characteristicList =
            (service['characteristics'] as List).map((char) {
          return Characteristic(
            uuid: char['uuid'] ?? 'Unknown UUID',
            name: char['name'] ?? 'Unknown Characteristic',
            value: char['value'] ?? 'Unknown',
          );
        }).toList();

        return BluetoothService(
          uuid: service['uuid'] ?? 'Unknown UUID',
          name: service['name'] ?? 'Unknown Service',
          characteristics: characteristicList,
        );
      }).toList();
      _showDeviceSettingsModal(deviceId);
    } on PlatformException catch (e) {
      logger.e("Failed to connect to device: '${e.message}'.");
    }
  }

  /// Displays a modal bottom sheet with device settings, including the device ID and a list of services and their characteristics.
  ///
  /// The modal is displayed when the user taps the "arrow forward" icon on a device in the list of scanned devices.
  ///
  /// The [deviceId] parameter is the ID of the device to display the settings for.
  void _showDeviceSettingsModal(String deviceId) {
    showModalBottomSheet(
      context: context,
      builder: (context) {
        return Padding(
          padding: const EdgeInsets.all(16.0),
          child: ListView(
            children: [
              const Text('Device Settings',
                  style: TextStyle(fontSize: 24, fontWeight: FontWeight.bold)),
              const SizedBox(height: 16),
              Text('Device ID: $deviceId'),
              const Text('Services:',
                  style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
              ...services.map((service) {
                return Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    ListTile(
                      title: Text('Service name: ${service.name}',
                          style: const TextStyle(
                              fontSize: 16, fontWeight: FontWeight.bold)),
                      subtitle: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          const Text(
                            'Characteristics:',
                          ),
                          ...service.characteristics
                              .map<Widget>((characteristic) => ListTile(
                                    title: Text(characteristic.name),
                                    subtitle: Text(characteristic.value),
                                  )),
                        ],
                      ),
                    ),
                  ],
                );
              }),
            ],
          ),
        );
      },
    );
  }

  @override

  /// Builds the main app UI, which displays a list of scanned Bluetooth devices.
  ///
  /// The UI includes a loading spinner and error handling when scanning for devices.
  /// When a device is selected, the [_showDeviceSettingsModal] function is called to display
  /// the device's settings in a modal bottom sheet.
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('BLE App')),
      body: FutureBuilder<List<Map<String, String>>>(
        future: scanForDevices(),
        builder: (context, snapshot) {
          if (snapshot.connectionState == ConnectionState.waiting) {
            return const Center(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  CircularProgressIndicator(),
                  SizedBox(height: 16),
                  Text('Searching for devices'),
                ],
              ),
            );
          } else if (snapshot.hasError) {
            return Padding(
              padding: const EdgeInsets.all(8.0),
              child: Center(child: Text('Error: ${snapshot.error}')),
            );
          } else if (!snapshot.hasData || snapshot.data!.isEmpty) {
            return const Padding(
              padding: EdgeInsets.all(8.0),
              child: Center(child: Text('No devices found')),
            );
          } else {
            return ListView.builder(
              itemCount: snapshot.data!.length,
              itemBuilder: (context, index) {
                final device = snapshot.data![index];
                return ListTile(
                  leading: CircleAvatar(
                    child: Text('${index + 1}'),
                  ),
                  title: Text(device['name'] ?? 'Unknown Device'),
                  subtitle: Text(device['id'] ?? 'Unknown ID'),
                  trailing: IconButton(
                    icon: const Icon(Icons.arrow_forward),
                    onPressed: () => connectToDevice(device['id'] ?? ''),
                  ),
                );
              },
            );
          }
        },
      ),
    );
  }
}
