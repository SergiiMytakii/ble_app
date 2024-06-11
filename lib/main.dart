// ignore_for_file: avoid_print

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
  List<Map<String, String>> services = [];
  List<Map<String, String>> characteristics = [];

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

  Future<void> connectToDevice(String deviceId) async {
    try {
      final Map<Object?, Object?> result = await platform
          .invokeMethod('connectToDevice', {'deviceId': deviceId});
      services = (result['services'] as List)
          .map((e) => Map<String, String>.from(e))
          .toList();
      characteristics = (result['characteristics'] as List)
          .map((e) => Map<String, String>.from(e))
          .toList();
      _showDeviceSettingsModal(deviceId);
    } on PlatformException catch (e) {
      logger.e("Failed to connect to device: '${e.message}'.");
    }
  }

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
              ...services.map((service) => ListTile(
                    title: Text(service['name'] ?? 'Unknown Service'),
                    subtitle: Text(service['uuid'] ?? ''),
                  )),
              const Text('Characteristics:',
                  style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
              ...characteristics.map((characteristic) => ListTile(
                    title: Text(
                        characteristic['name'] ?? 'Unknown Characteristic'),
                    subtitle: Text(characteristic['uuid'] ?? ''),
                  ))
            ],
          ),
        );
      },
    );
  }

  @override
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
