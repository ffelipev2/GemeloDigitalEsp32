# GemeloDigitalEsp32

Proyecto de gemelo digital de orientación con un ESP32-C3 y un sensor BNO08x. El ESP32 crea la red Wi-Fi local `gemelo1`; desde un navegador en una tablet se muestra el modelo 3D de Cubone siguiendo la orientación medida por el sensor.

## ¿Para qué se utiliza un gemelo digital?

Un gemelo digital es una representación virtual que se actualiza con datos de un objeto o sistema físico. Permite observar su estado en tiempo real, probar ajustes y detectar diferencias entre el comportamiento esperado y el medido. En este proyecto, Cubone representa **la orientación del BNO08x**, no todos los movimientos ni las propiedades físicas de un objeto. Sirve para visualizar la rotación, practicar con cuaterniones y calibrar el montaje del sensor.

## Componentes

| Componente | Función |
| --- | --- |
| ESP32-C3 | Lee el sensor, crea el punto de acceso y sirve la página web. |
| IMU BNO08x (BNO080/BNO085/BNO086 compatible) | Entrega el vector de rotación para orientar el modelo. |
| Pantalla OLED SSD1306 de 128 × 64 por I²C | Muestra la dirección IP del punto de acceso. |
| Tablet Android con navegador compatible con WebGL | Muestra e interactúa con el gemelo digital. |
| Cables y alimentación para el ESP32 y los módulos | Conectan y alimentan el montaje. |

El BNO08x y la OLED comparten el bus I²C del ESP32-C3: **SDA = GPIO 5**, **SCL = GPIO 6**, a **400 kHz**. El programa también inicializa el **GPIO 8** para un LED, aunque actualmente no lo usa como indicador de estado.

## Tecnologías

- **Arduino C++ y núcleo ESP32:** firmware del ESP32-C3.
- **Wire/I²C y biblioteca SparkFun BNO08x:** comunicación con la IMU. Se usa `Game Rotation Vector` con un intervalo solicitado de 20 ms (50 Hz).
- **Wi-Fi en modo AP, ESPAsyncWebServer y AsyncTCP:** red local y servidor HTTP.
- **Server-Sent Events (SSE):** envío de los cuaterniones `x`, `y`, `z`, `w` al navegador por `/events`.
- **LittleFS:** guarda la página, las bibliotecas JavaScript y `cubone.glb` en la memoria flash.
- **ArduinoJson y U8g2:** serialización de datos del sensor y pantalla OLED.
- **HTML, CSS, JavaScript, Three.js y GLTFLoader:** interfaz y renderizado del modelo GLB. Los recursos web son locales; no requieren CDN ni internet.

## Flujo de datos

```text
BNO08x ──I²C──> ESP32-C3 ──SSE por Wi-Fi local──> navegador Android ──> Cubone 3D
```

El navegador aplica un suavizado de 40 ms, permite ajustar la rotación manualmente y tiene un botón **Calibrar** para tomar la orientación actual como referencia.

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

1. Instala el núcleo **ESP32** en Arduino IDE y selecciona una placa **ESP32-C3** compatible. El proyecto se ha compilado con `esp32:esp32:esp32c3`.
2. Instala las bibliotecas **ESPAsyncWebServer**, **AsyncTCP**, **ArduinoJson**, **U8g2** y **SparkFun BNO08x Cortex Based IMU**.
3. Conecta el BNO08x y la OLED al bus I²C indicado arriba, además de alimentación y tierra según tus módulos.
4. Carga `GemeloDigitalEsp32.ino` al ESP32-C3.
5. **Sube por separado el contenido de `data/` a LittleFS** con una herramienta de carga de sistema de archivos para ESP32. Subir solo el sketch no instala la página ni el modelo.
6. En la tablet, conecta a la red **`gemelo1`**, clave **`12345678`**. Si Android avisa que no hay internet, elige mantener o usar esa red: la aplicación funciona localmente.
7. Abre en el navegador la IP mostrada en la OLED o en el monitor serie (habitualmente `http://192.168.4.1`).

El AP no proporciona acceso a internet. El navegador debe permanecer conectado a `gemelo1` para recibir el movimiento en tiempo real.
