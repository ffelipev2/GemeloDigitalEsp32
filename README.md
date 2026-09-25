# GemeloDigitalEsp32

Gemelo digital de orientación para un ESP32-C3 y un BNO08x. El ESP32 envía las muestras **solo por Bluetooth Low Energy (BLE)**. La app Android incluye a Cubone, Three.js y los demás archivos necesarios; no hay red Wi-Fi, servidor web ni carga de LittleFS.

## Instalar y usar

1. En Arduino IDE, instala el núcleo **ESP32** y las bibliotecas **SparkFun BNO08x Cortex Based IMU** y **U8g2**. Selecciona una placa ESP32-C3 compatible y carga [`GemeloDigitalEsp32.ino`](GemeloDigitalEsp32.ino).
2. Conecta BNO08x y la pantalla OLED SSD1306 al bus I²C: **SDA GPIO 5**, **SCL GPIO 6**, alimentación y tierra según tus módulos. La OLED muestra si la app está conectada y si avanzan las muestras del sensor.
3. Instala [`android/GemeloDigitalBLE-debug.apk`](android/GemeloDigitalBLE-debug.apk) en la tablet, activa Bluetooth y abre **Gemelo Digital**.
4. Deja la placa acostada, pulsa **Conectar Bluetooth** y concede el permiso solicitado. La primera muestra calibra la postura neutra y Cubone mira hacia la derecha. El mismo botón permite desconectar y volver a calibrar al conectar otra vez.

En Android 11 o anterior, activa también la ubicación del sistema para permitir el escaneo BLE. No necesitas emparejar el ESP32 en los ajustes de Android ni conectar la tablet a una red Wi-Fi.

## Si Cubone deja de moverse

- Si el botón dice **Sin datos**, revisa la OLED. **BNO08x: sin datos** indica que el sensor dejó de entregar orientación; revisa alimentación y los cables I²C. El firmware intenta reactivar los reportes automáticamente.
- Si la OLED muestra **Muestras**, el número debe avanzar. La app usa notificaciones BLE y, si se detienen, lee directamente el último valor del sensor. Desconecta y conecta otra vez con la placa acostada para tomar una nueva referencia.
- El monitor serie a **115200 baudios** muestra cada dos segundos el número de muestra y el cuaternión transmitido. Esto permite distinguir un sensor detenido de un problema de recepción en Android.

## Archivos

```text
GemeloDigitalEsp32.ino               Firmware BLE del ESP32-C3
android/GemeloDigitalBLE-debug.apk   App Android instalable
android/app/src/main/assets/         Modelo 3D, HTML, CSS y JavaScript dentro de la app
android/app/src/main/java/           Cliente BLE de Android
android/build-apk.ps1                Compilación local del APK
```

La app conserva la orientación ajustada para este montaje: eje de inclinación **115,5°**, inclinaciones frontal y lateral invertidas, y giro vertical sin invertir. Su vista muestra solo el modelo y el botón Bluetooth.

## Protocolo BLE

El ESP32 anuncia **CuboneESP32** con el servicio `6a59d32b-158a-4c76-8c7e-7a4a5ab48152`. La característica `6a59d32b-158a-4c76-8c7e-7a4a5ab48153` permite lectura y notificaciones. Cada muestra nueva contiene **18 bytes**: cuatro `float32` little-endian (`x, y, z, w`) y un contador `uint16` little-endian. El sensor solicita reportes cada 20 ms; las notificaciones se limitan a 25 Hz. La app también acepta paquetes anteriores de 16 bytes.

## Reconstruir la app

Puedes abrir `android/` en Android Studio. En este equipo también puedes ejecutar `powershell -ExecutionPolicy Bypass -File .\android\build-apk.ps1` desde la raíz. Ese script requiere Android SDK (plataforma 35, build-tools 34.0.0 y 36.0.0) y JDK de Android Studio; deja el APK firmado en `android/GemeloDigitalBLE-debug.apk`. La clave de desarrollo se guarda localmente en `android/.signing/` y no se publica.
