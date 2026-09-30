package com.one.robotassistant.feature.robot.transport

abstract class Esp32Transport : RobotTransport

// TODO(PHASE_2): implement a framed, acknowledged protocol for ESP32-S3.
abstract class BluetoothEsp32Transport : Esp32Transport()

// TODO(PHASE_2): obtain the address from settings/discovery; never hardcode it.
abstract class WifiEsp32Transport : Esp32Transport()
