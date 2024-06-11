class BluetoothService {
  final String uuid;
  final String name;
  final List<Characteristic> characteristics;

  BluetoothService({
    required this.uuid,
    required this.name,
    required this.characteristics,
  });
}

class Characteristic {
  final String uuid;
  final String name;

  Characteristic({
    required this.uuid,
    required this.name,
  });
}
