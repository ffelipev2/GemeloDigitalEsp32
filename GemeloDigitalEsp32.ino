#include <Arduino.h>
#include <Wire.h>
#include <U8g2lib.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLE2902.h>
#include "SparkFun_BNO08x_Arduino_Library.h"

constexpr int SDA_PIN = 5;
constexpr int SCL_PIN = 6;
constexpr uint16_t SENSOR_INTERVAL_MS = 20; // 50 Hz
constexpr uint32_t BLE_INTERVAL_MS = 40;     // 25 Hz
constexpr uint32_t IMU_RETRY_MS = 5000;
constexpr uint32_t IMU_STALE_MS = 3000;

const char* BLE_SERVICE_UUID = "6a59d32b-158a-4c76-8c7e-7a4a5ab48152";
const char* BLE_QUAT_UUID = "6a59d32b-158a-4c76-8c7e-7a4a5ab48153";

// 18 bytes: x,y,z,w float32 little-endian y contador uint16 little-endian.
// El contador permite distinguir una lectura nueva de una muestra detenida.
struct __attribute__((packed)) OrientationPacket {
  float x;
  float y;
  float z;
  float w;
  uint16_t sequence;
};
static_assert(sizeof(OrientationPacket) == 18, "Paquete BLE inesperado");

BNO08x myIMU;
U8G2_SSD1306_128X64_NONAME_F_HW_I2C u8g2(
  U8G2_R0, U8X8_PIN_NONE, SCL_PIN, SDA_PIN
);
BLECharacteristic* bleQuaternion = nullptr;
volatile bool bleConnected = false;
volatile bool restartAdvertising = false;
volatile uint32_t restartAdvertisingAt = 0;
bool imuReady = false;
uint16_t sequence = 0;
uint32_t lastImuSampleMs = 0;
uint32_t lastImuRetryMs = 0;
uint32_t lastReportRetryMs = 0;
uint32_t lastBleSendMs = 0;
uint32_t lastDisplayMs = 0;
uint32_t lastSerialMs = 0;

class CuboneBleCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* server) override {
    bleConnected = true;
    restartAdvertising = false;
  }

  void onDisconnect(BLEServer* server) override {
    bleConnected = false;
    restartAdvertisingAt = millis() + 250;
    restartAdvertising = true;
  }
};

void startBle() {
  BLEDevice::init("CuboneESP32");
  BLEServer* server = BLEDevice::createServer();
  server->setCallbacks(new CuboneBleCallbacks());
  BLEService* service = server->createService(BLE_SERVICE_UUID);
  bleQuaternion = service->createCharacteristic(
    BLE_QUAT_UUID,
    BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY
  );
  bleQuaternion->addDescriptor(new BLE2902());
  const OrientationPacket initial = {0, 0, 0, 1, 0};
  bleQuaternion->setValue(reinterpret_cast<const uint8_t*>(&initial), sizeof(initial));
  service->start();
  BLEAdvertising* advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(BLE_SERVICE_UUID);
  advertising->setScanResponse(true);
  BLEDevice::startAdvertising();
  Serial.println("BLE listo: CuboneESP32");
}

void startImu() {
  lastImuRetryMs = millis();
  imuReady = myIMU.begin() && myIMU.enableGameRotationVector(SENSOR_INTERVAL_MS);
  lastImuSampleMs = millis();
  Serial.println(imuReady ? "BNO08x listo" : "BNO08x no disponible; reintentando");
}

void drawStatus(uint32_t now) {
  if (now - lastDisplayMs < 1000) return;
  lastDisplayMs = now;
  u8g2.clearBuffer();
  u8g2.setFont(u8g2_font_6x10_tr);
  u8g2.drawStr(0, 12, "CuboneESP32 BLE");
  u8g2.drawStr(0, 28, bleConnected ? "App conectada" : "Esperando app");
  if (!imuReady) {
    u8g2.drawStr(0, 44, "BNO08x: error");
  } else if (sequence == 0 || now - lastImuSampleMs > IMU_STALE_MS) {
    u8g2.drawStr(0, 44, "BNO08x: sin datos");
  } else {
    u8g2.setCursor(0, 44);
    u8g2.print("Muestras: ");
    u8g2.print(sequence);
  }
  u8g2.sendBuffer();
}

void setup() {
  Serial.begin(115200);
  Wire.begin(SDA_PIN, SCL_PIN, 400000);
  u8g2.begin();
  u8g2.setContrast(255);
  startImu();
  startBle();
}

void loop() {
  const uint32_t now = millis();
  if (restartAdvertising && static_cast<int32_t>(now - restartAdvertisingAt) >= 0) {
    restartAdvertising = false;
    BLEDevice::startAdvertising();
  }

  if (!imuReady) {
    if (now - lastImuRetryMs >= IMU_RETRY_MS) startImu();
    drawStatus(now);
    delay(5);
    return;
  }

  if (myIMU.wasReset()) {
    imuReady = myIMU.enableGameRotationVector(SENSOR_INTERVAL_MS);
    lastReportRetryMs = now;
    Serial.println("BNO08x reiniciado; reportes reactivados");
  }
  if (now - lastImuSampleMs > IMU_STALE_MS && now - lastReportRetryMs > IMU_STALE_MS) {
    imuReady = myIMU.enableGameRotationVector(SENSOR_INTERVAL_MS);
    lastReportRetryMs = now;
    Serial.println("BNO08x sin muestras; reportes reactivados");
  }

  if (imuReady && myIMU.getSensorEvent()
      && myIMU.sensorValue.sensorId == SH2_GAME_ROTATION_VECTOR) {
    lastImuSampleMs = now;
    if (++sequence == 0) ++sequence;
    const OrientationPacket sample = {
      myIMU.sensorValue.un.gameRotationVector.i,
      myIMU.sensorValue.un.gameRotationVector.j,
      myIMU.sensorValue.un.gameRotationVector.k,
      myIMU.sensorValue.un.gameRotationVector.real,
      sequence
    };
    // Actualizar también sin cliente permite que la app lea el valor más reciente.
    bleQuaternion->setValue(reinterpret_cast<const uint8_t*>(&sample), sizeof(sample));
    if (bleConnected && now - lastBleSendMs >= BLE_INTERVAL_MS) {
      bleQuaternion->notify();
      lastBleSendMs = now;
    }
    if (now - lastSerialMs >= 2000) {
      Serial.printf("IMU #%u BLE=%d q=%.3f,%.3f,%.3f,%.3f\n",
        sequence, bleConnected, sample.x, sample.y, sample.z, sample.w);
      lastSerialMs = now;
    }
  }

  drawStatus(now);
  delay(1);
}
