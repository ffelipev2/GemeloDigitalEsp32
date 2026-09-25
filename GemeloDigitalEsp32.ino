// ===========================
//   LIBRERÍAS
// ===========================
#include <Arduino.h>
#include <Wire.h>
#include <WiFi.h>
#include <ESPAsyncWebServer.h>
#include <LittleFS.h>
#include <ArduinoJson.h>
#include <U8g2lib.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLE2902.h>

#include "SparkFun_BNO08x_Arduino_Library.h"
BNO08x myIMU;


// ===========================
//   PINES I2C ESP32-C3
// ===========================
#define SDA_PIN 5
#define SCL_PIN 6


// ===========================
//   OLED
// ===========================
U8G2_SSD1306_128X64_NONAME_F_HW_I2C u8g2(
  U8G2_R0, U8X8_PIN_NONE, SCL_PIN, SDA_PIN
);

const int xOffset = 27;
const int yOffset = 20;


// ===========================
//   LED AZUL
// ===========================
#define LED_PIN 8


// ===========================
//   WIFI (MODO AP)
// ===========================
AsyncWebServer server(80);
AsyncEventSource events("/events");
const char* AP_SSID = "gemelo1";
const char* AP_PASSWORD = "12345678";
bool imuReady = false;
bool webServerReady = false;

// Servicio BLE para la app Android. Cada notificación contiene x,y,z,w
// como cuatro float32 little-endian (16 bytes en total).
const char* BLE_SERVICE_UUID = "6a59d32b-158a-4c76-8c7e-7a4a5ab48152";
const char* BLE_QUAT_UUID = "6a59d32b-158a-4c76-8c7e-7a4a5ab48153";
const uint32_t bleIntervalMs = 40; // 25 Hz; evita acumular notificaciones.
BLECharacteristic* bleQuaternion = nullptr;
volatile bool bleConnected = false;
uint32_t lastBleSendMs = 0;

class CuboneBleCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* server) override {
    bleConnected = true;
  }

  void onDisconnect(BLEServer* server) override {
    bleConnected = false;
    BLEDevice::startAdvertising();
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
  const float identity[4] = {0.0f, 0.0f, 0.0f, 1.0f};
  bleQuaternion->setValue(reinterpret_cast<const uint8_t*>(identity), sizeof(identity));
  service->start();
  BLEAdvertising* advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(BLE_SERVICE_UUID);
  advertising->setScanResponse(true);
  BLEDevice::startAdvertising();
  Serial.println("BLE listo: CuboneESP32");
}


// ===========================
//   FRECUENCIA DEL SENSOR
// ===========================
const uint16_t sensorIntervalMs = 20; // 50 Hz


// ===========================
//   OLED — PANTALLAS
// ===========================
void drawAPScreen()
{
  u8g2.clearBuffer();
  u8g2.setFont(u8g2_font_5x8_tr);

  u8g2.setCursor(xOffset+5, yOffset + 10);
  u8g2.print("WiFi AP listo");

  u8g2.setCursor(xOffset +7, yOffset + 22);
  u8g2.print(WiFi.softAPIP().toString());

  u8g2.sendBuffer();
}


// ===========================
//   SETUP
// ===========================
void setup() {
  Serial.begin(115200);
  delay(200);

  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, LOW);

  u8g2.begin();
  u8g2.setContrast(255);

  Wire.begin(SDA_PIN, SCL_PIN, 400000);

  startBle();

  // ---- WIFI MODO AP ----
  WiFi.mode(WIFI_AP);
  const bool wifiReady = WiFi.softAP(AP_SSID, AP_PASSWORD);
  if (!wifiReady) {
    Serial.println("ERROR al iniciar el AP");
  } else {
    Serial.print("Conectate a: ");
    Serial.println(AP_SSID);
    Serial.print("Abre: http://");
    Serial.println(WiFi.softAPIP());
    drawAPScreen();

    // ---- LittleFS y servidor web ----
    if (!LittleFS.begin(true)) {
      Serial.println("ERROR LittleFS: no se puede servir la pagina");
    } else {
      server.serveStatic("/", LittleFS, "/").setDefaultFile("index.html");
      events.onConnect([](AsyncEventSourceClient* client) {
        Serial.println("Cliente SSE conectado");
      });
      server.addHandler(&events);
      server.begin();
      webServerReady = true;
      Serial.println("Servidor listo");
    }
  }

  // ---- BNO08X ----
  Serial.println("Iniciando IMU...");
  if (myIMU.begin()) {
    imuReady = myIMU.enableGameRotationVector(sensorIntervalMs);
  }
  Serial.println(imuReady ? "IMU lista" : "IMU no detectada o sin vector de rotacion");
}


// ===========================
//   LOOP PRINCIPAL
// ===========================
void loop() {
  if (!imuReady) {
    delay(100);
    return;
  }

  // Leer el BNO08x en cada vuelta evita acumular reportes antiguos.
  if (!myIMU.getSensorEvent()) {
    delay(1);
    return;
  }
  if (myIMU.sensorValue.sensorId != SH2_GAME_ROTATION_VECTOR) return;

  if (webServerReady) {
    StaticJsonDocument<128> doc;
    doc["x"] = myIMU.sensorValue.un.gameRotationVector.i;
    doc["y"] = myIMU.sensorValue.un.gameRotationVector.j;
    doc["z"] = myIMU.sensorValue.un.gameRotationVector.k;
    doc["w"] = myIMU.sensorValue.un.gameRotationVector.real;

    char buffer[128];
    serializeJson(doc, buffer, sizeof(buffer));
    events.send(buffer, "quat", millis());
  }

  const uint32_t now = millis();
  if (bleConnected && bleQuaternion && now - lastBleSendMs >= bleIntervalMs) {
    const float quaternion[4] = {
      myIMU.sensorValue.un.gameRotationVector.i,
      myIMU.sensorValue.un.gameRotationVector.j,
      myIMU.sensorValue.un.gameRotationVector.k,
      myIMU.sensorValue.un.gameRotationVector.real
    };
    bleQuaternion->setValue(reinterpret_cast<const uint8_t*>(quaternion), sizeof(quaternion));
    bleQuaternion->notify();
    lastBleSendMs = now;
  }
}
