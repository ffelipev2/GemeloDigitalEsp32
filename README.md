# GemeloDigitalEsp32

Gemelo digital de orientación para un ESP32-C3 y un BNO08x. El ESP32 envía las muestras **solo por Bluetooth Low Energy (BLE)**. La app Android dibuja a Cubone de forma nativa con SceneView y Filament; no hay red Wi-Fi, servidor web ni carga de LittleFS.

## Instalar y usar

1. En Arduino IDE, instala el núcleo **ESP32** y las bibliotecas **SparkFun BNO08x Cortex Based IMU** y **U8g2**. Selecciona una placa ESP32-C3 compatible y carga [`GemeloDigitalEsp32.ino`](GemeloDigitalEsp32.ino).
2. Conecta BNO08x y la pantalla OLED SSD1306 al bus I²C: **SDA GPIO 5**, **SCL GPIO 6**, alimentación y tierra según tus módulos. La OLED muestra si la app está conectada y si avanzan las muestras del sensor.
3. Instala [`android/GemeloDigitalBLE-debug.apk`](android/GemeloDigitalBLE-debug.apk) en la tablet, activa Bluetooth y abre **Gemelo Digital**.
4. Pulsa **Conectar Bluetooth** y concede el permiso solicitado. La primera vez, el asistente pedirá tres posturas del dispositivo: neutra, inclinado hacia adelante e inclinado hacia la derecha. Mantén cada postura quieta y pulsa **Continuar**. Antes de inclinarlo a la derecha, vuelve a la postura neutra.
5. Al terminar, Cubone mirará al frente y responderá con los ejes y sentidos del montaje real del BNO08x, incluso si está girado o boca abajo. La app guarda esta calibración. Usa **Calibrar orientación** si cambias el montaje físico.

Para recuperar el cero durante el uso, coloca el dispositivo en la posición inicial que guardaste y pulsa **Volver a cero**. Esto corrige el desfase de orientación sin repetir el asistente. Si está demasiado inclinado respecto a esa posición, la app te pedirá volver a ella primero.

**Instalación del APK de este repositorio:** requiere Android 7.0 (API 24) o superior. Usa la clave de desarrollo local en `android/.signing/`. El APK anterior guardado en Git tiene otra firma: Android no permitirá actualizarlo directamente con este APK. Para conservar la calibración previa hace falta firmar la nueva versión con la clave original; si se puede actualizar, la app intenta importar la calibración guardada en la antigua WebView.

En Android 11 o anterior, activa también la ubicación del sistema para permitir el escaneo BLE. No necesitas emparejar el ESP32 en los ajustes de Android ni conectar la tablet a una red Wi-Fi.

## Si Cubone deja de moverse

- Si el botón dice **Sin datos**, revisa la OLED. **BNO08x: sin datos** indica que el sensor dejó de entregar orientación; revisa alimentación y los cables I²C. El firmware intenta reactivar los reportes automáticamente.
- Si la OLED muestra **Muestras**, el número debe avanzar. La app usa notificaciones BLE y, si se detienen, lee directamente el último valor del sensor. Desconecta y conecta otra vez para restablecer la conexión.
- El monitor serie a **115200 baudios** muestra cada dos segundos el número de muestra y el cuaternión transmitido. Esto permite distinguir un sensor detenido de un problema de recepción en Android.

## Archivos

```text
GemeloDigitalEsp32.ino               Firmware BLE del ESP32-C3
android/GemeloDigitalBLE-debug.apk   App Android instalable
android/app/src/main/assets/         Modelo Cubone y guía del suelo en GLB
android/app/src/main/java/           Cliente BLE, calibración y visor nativo
android/build-apk.ps1                Compilación local del APK
```

La app guarda la transformación 3D de los ejes del sensor y la dirección de gravedad de la postura neutra en las preferencias de Android. Al reconectar recupera la inclinación neutra; el rumbo inicial de cada conexión se toma como cero porque el **Game Rotation Vector** del BNO08x no tiene referencia absoluta de rumbo. La animación interpola las muestras BLE con el suavizado anterior al filtro de vibración. El panel principal muestra los controles de calibración, Bluetooth y retorno a cero, el visor 3D ampliable y tarjetas con el estado y los ángulos de orientación.

## Protocolo BLE

El ESP32 anuncia **CuboneESP32** con el servicio `6a59d32b-158a-4c76-8c7e-7a4a5ab48152`. La característica `6a59d32b-158a-4c76-8c7e-7a4a5ab48153` permite lectura y notificaciones. Cada muestra nueva contiene **18 bytes**: cuatro `float32` little-endian (`x, y, z, w`) y un contador `uint16` little-endian. El sensor solicita reportes cada 20 ms; las notificaciones se limitan a 25 Hz. La app también acepta paquetes anteriores de 16 bytes.

## Reconstruir la app

Puedes abrir `android/` en Android Studio. En este equipo también puedes ejecutar `powershell -ExecutionPolicy Bypass -File .\android\build-apk.ps1` desde la raíz. El script usa Gradle y deja el APK firmado en `android/GemeloDigitalBLE-debug.apk`. La clave de desarrollo se guarda localmente en `android/.signing/` y no se publica.

Para ejecutarla desde Android Studio, abre la carpeta `android/` como proyecto, espera la sincronización de Gradle, selecciona la configuración **app** y el teléfono Android conectado, y pulsa **Run**. En este equipo, la configuración local de Gradle usa JDK 21 y `local.properties` apunta al SDK instalado. La configuración `app` instala sin borrar el almacenamiento de la calibración.

El modelo actual procede de `nuevo_modelo/source/BLENDER_Cubone.obj` y su textura `nuevo_modelo/textures/Material_Base_Color.png`. Para regenerar `android/app/src/main/assets/cubone.glb`, ejecuta `python android/tools/import-cubone-model.py` y reconstruye el APK. El conversor conserva la malla de contorno negro y ajusta la dirección inicial del modelo para que mire al frente. No necesita paquetes adicionales de Python.
