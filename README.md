# GemeloDigitalEsp32

Proyecto de gemelo digital de orientación con un ESP32-C3 y un sensor BNO08x. Puedes ver el modelo 3D de Cubone en la app Android por Bluetooth Low Energy (BLE) o en un navegador mediante la red Wi-Fi local `gemelo1`.

## ¿Para qué se utiliza un gemelo digital?

Un gemelo digital es una representación virtual que se actualiza con datos de un objeto o sistema físico. Permite observar su estado en tiempo real, probar ajustes y detectar diferencias entre el comportamiento esperado y el medido. En este proyecto, Cubone representa **la orientación del BNO08x**, no todos los movimientos ni las propiedades físicas de un objeto. Sirve para visualizar la rotación, practicar con cuaterniones y calibrar el montaje del sensor.

## Componentes

| Componente | Función |
| --- | --- |
| ESP32-C3 | Lee el sensor, crea el punto de acceso y sirve la página web. |
| IMU BNO08x (BNO080/BNO085/BNO086 compatible) | Entrega el vector de rotación para orientar el modelo. |
| Pantalla OLED SSD1306 de 128 × 64 por I²C | Muestra la dirección IP del punto de acceso. |
| Tablet Android con BLE y WebView compatible con WebGL, o navegador compatible con WebGL | Muestra e interactúa con el gemelo digital. |
| Cables y alimentación para el ESP32 y los módulos | Conectan y alimentan el montaje. |

El BNO08x y la OLED comparten el bus I²C del ESP32-C3: **SDA = GPIO 5**, **SCL = GPIO 6**, a **400 kHz**. El programa también inicializa el **GPIO 8** para un LED, aunque actualmente no lo usa como indicador de estado.

## Tecnologías

- **Arduino C++ y núcleo ESP32:** firmware del ESP32-C3.
- **Wire/I²C y biblioteca SparkFun BNO08x:** comunicación con la IMU. Se usa `Game Rotation Vector` con un intervalo solicitado de 20 ms (50 Hz).
- **Wi-Fi en modo AP, ESPAsyncWebServer y AsyncTCP:** red local y servidor HTTP.
- **Server-Sent Events (SSE):** envío de los cuaterniones `x`, `y`, `z`, `w` al navegador por `/events`.
- **Bluetooth Low Energy (BLE):** envío de los mismos cuaterniones directamente a la app Android. El servicio es `6a59d32b-158a-4c76-8c7e-7a4a5ab48152`; la característica de notificaciones es `6a59d32b-158a-4c76-8c7e-7a4a5ab48153`. Cada muestra contiene cuatro `float32` little-endian en orden `x, y, z, w` (16 bytes).
- **LittleFS:** guarda la página, las bibliotecas JavaScript y `cubone.glb` en la memoria flash.
- **ArduinoJson y U8g2:** serialización de datos del sensor y pantalla OLED.
- **HTML, CSS, JavaScript, Three.js y GLTFLoader:** interfaz y renderizado del modelo GLB. Los recursos web son locales; no requieren CDN ni internet.

## Flujo de datos

```text
BNO08x ──I²C──> ESP32-C3 ──SSE por Wi-Fi local──> navegador Android ──> Cubone 3D
                   └──────BLE──────────────> app Android ──────────> Cubone 3D
```

El navegador aplica un suavizado de 40 ms, permite ajustar la rotación manualmente y tiene un botón **Calibrar** para tomar la orientación actual como referencia. Apoya la placa acostada y pulsa **Calibrar** para que Cubone mire hacia la derecha de la pantalla. La calibración conserva la correspondencia entre los ejes del sensor y los de la escena para que Cubone siga los giros posteriores. La conexión con el sensor comienza después de dibujar el modelo por primera vez; cada cuadro usa la muestra más reciente y la lectura numérica se actualiza a menor frecuencia.

La **dirección de inclinación** comienza en **115,5°**. Tras calibrar, los sentidos de **adelante/atrás** e **izquierda/derecha** se invierten por separado para que coincidan con la placa; el giro vertical conserva su sentido. La página muestra en vivo cuánto movimiento corresponde a «frente» y a «lado». Si cambia el montaje y la inclinación hacia adelante aparece diagonal, mueve la placa unos 25° en la dirección que debería inclinar a Cubone hacia adelante y pulsa **Aprender inclinación adelante**. También puedes afinar el eje con **−5°** y **+5°**, girarlo **±90°** cuando los ejes estén cruzados, cambiar cada sentido con su botón, o escribir un valor. **Restaurar ajuste** vuelve a 115,5° con ambas inclinaciones invertidas. **Copiar diagnóstico** genera los cuaterniones de la postura neutra y la inclinada, junto con el ángulo y los sentidos aplicados, para compartirlos y revisar el montaje. En navegadores que no permitan copiar automáticamente desde HTTP, el texto queda seleccionado en el recuadro para copiarlo manualmente.

## Archivos

```text
GemeloDigitalEsp32/
├── GemeloDigitalEsp32.ino   # Firmware Arduino
├── README.md
└── data/                    # Contenido que se sube a LittleFS
    ├── index.html
    ├── styles.css
    ├── app.js
    ├── three.min.js
    ├── GLTFLoader.js
    └── cubone.glb
```

## Puesta en marcha

1. Instala el núcleo **ESP32** en Arduino IDE y selecciona una placa **ESP32-C3** compatible con al menos 4 MB de flash. En **Partition Scheme**, elige **No OTA (2MB APP/2MB SPIFFS)**. El proyecto se ha compilado con `esp32:esp32:esp32c3:PartitionScheme=no_ota`.
2. Instala las bibliotecas **ESPAsyncWebServer**, **AsyncTCP**, **ArduinoJson**, **U8g2** y **SparkFun BNO08x Cortex Based IMU**.
3. Conecta el BNO08x y la OLED al bus I²C indicado arriba, además de alimentación y tierra según tus módulos.
4. Carga `GemeloDigitalEsp32.ino` al ESP32-C3.
5. **Sube por separado el contenido de `data/` a LittleFS** con una herramienta de carga de sistema de archivos para ESP32. Subir solo el sketch no instala la página ni el modelo.
6. En la tablet, conecta a la red **`gemelo1`**, clave **`12345678`**. Si Android avisa que no hay internet, elige mantener o usar esa red: la aplicación funciona localmente.
7. Abre en el navegador la IP mostrada en la OLED o en el monitor serie (habitualmente `http://192.168.4.1`).

El AP no proporciona acceso a internet. El navegador debe permanecer conectado a `gemelo1` para recibir el movimiento en tiempo real.

## App Android por Bluetooth

El APK instalable está en [`android/GemeloDigitalBLE-debug.apk`](android/GemeloDigitalBLE-debug.apk). Necesitas **actualizar el firmware del ESP32-C3** con este proyecto: el firmware anterior solo transmite por Wi-Fi. Para compilarlo en Arduino IDE selecciona **ESP32C3 Dev Module → Partition Scheme → No OTA (2MB APP/2MB SPIFFS)**. La partición predeterminada de 1,2 MB no alcanza para Wi-Fi y BLE juntos. Al cambiar la tabla de particiones vuelve a subir `data/` a LittleFS si también usarás el navegador. Este ajuste requiere una placa con al menos 4 MB de flash.

1. Instala el APK en la tablet Android y activa Bluetooth.
2. Abre **Gemelo Digital**, pulsa **Conectar Bluetooth** y concede el permiso solicitado. En Android 11 o anterior, activa también la ubicación del sistema para permitir la búsqueda BLE.
3. Deja la placa acostada mientras conectas. Al recibir la primera muestra, la app calibra automáticamente a Cubone para que mire a la derecha. En Android solo se muestran el modelo y un botón que cambia entre **Conectar Bluetooth** y **Desconectar**; los controles de ajuste siguen disponibles en el navegador.

La conexión BLE no necesita la red `gemelo1` ni internet. El ESP32 sigue ofreciendo Wi-Fi para el navegador. La app busca el servicio BLE de este proyecto, se suscribe a las notificaciones y muestra la muestra más reciente. Si se desconecta, pulsa **Conectar Bluetooth** para volver a buscarlo.

Para reconstruir el APK en este equipo, ejecuta `powershell -ExecutionPolicy Bypass -File .\android\build-apk.ps1` desde la raíz del proyecto. Requiere Android SDK (plataforma 35 y build-tools 34.0.0 y 36.0.0) y JDK de Android Studio. El script genera un APK de desarrollo firmado y conserva la clave en `android/.signing/` para que las siguientes compilaciones puedan instalarse como actualización. También se incluye el proyecto Android Gradle en `android/`.
