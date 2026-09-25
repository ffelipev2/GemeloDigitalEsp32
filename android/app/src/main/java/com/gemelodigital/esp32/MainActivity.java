package com.gemelodigital.esp32;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class MainActivity extends Activity {
    private static final String APP_HOST = "gemelo.local";
    private static final String APP_URL = "https://" + APP_HOST + "/index.html?app=ble";
    private static final UUID SERVICE_UUID = UUID.fromString("6a59d32b-158a-4c76-8c7e-7a4a5ab48152");
    private static final UUID QUAT_UUID = UUID.fromString("6a59d32b-158a-4c76-8c7e-7a4a5ab48153");
    private static final UUID CLIENT_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final int PERMISSION_REQUEST = 10;
    private static final long SCAN_TIMEOUT_MS = 12000;
    private static final Set<String> ASSETS = new HashSet<>(Arrays.asList(
            "index.html", "styles.css", "app.js", "three.min.js", "GLTFLoader.js", "cubone.glb"
    ));

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WebView webView;
    private boolean pageReady;
    private boolean scanning;
    private BluetoothLeScanner scanner;
    private BluetoothGatt activeGatt;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(false);
        webView.getSettings().setAllowContentAccess(false);
        webView.addJavascriptInterface(new BleBridge(), "AndroidBle");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!"https".equals(uri.getScheme()) || !APP_HOST.equals(uri.getHost())) return null;
                String path = uri.getPath();
                String asset = path == null || path.equals("/") ? "index.html" : path.substring(1);
                if (!ASSETS.contains(asset)) return null;
                try {
                    String mime = asset.endsWith(".js") ? "application/javascript"
                            : asset.endsWith(".css") ? "text/css"
                            : asset.endsWith(".glb") ? "model/gltf-binary" : "text/html";
                    return new WebResourceResponse(mime, "UTF-8", getAssets().open(asset));
                } catch (IOException error) {
                    return null;
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !APP_HOST.equals(request.getUrl().getHost());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                showStatus("Pulsa Conectar Bluetooth", false);
            }
        });
        setContentView(webView);
        webView.loadUrl(APP_URL);
    }

    public final class BleBridge {
        @JavascriptInterface
        public void connect() {
            runOnUiThread(MainActivity.this::connectRequested);
        }

        @JavascriptInterface
        public void disconnect() {
            runOnUiThread(MainActivity.this::disconnectRequested);
        }
    }

    private boolean hasBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void connectRequested() {
        if (!hasBlePermissions()) {
            String[] permissions = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}
                    : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
            requestPermissions(permissions, PERMISSION_REQUEST);
            return;
        }
        startScan();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != PERMISSION_REQUEST) return;
        for (int result : results) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                showStatus("Permiso Bluetooth denegado", false);
                return;
            }
        }
        if (results.length == 0) {
            showStatus("Permiso Bluetooth no disponible", false);
            return;
        }
        startScan();
    }

    private void startScan() {
        if (scanning || activeGatt != null) return;
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) {
            showStatus("Este dispositivo no tiene Bluetooth", false);
            return;
        }
        try {
            if (!adapter.isEnabled()) {
                showStatus("Activa Bluetooth y vuelve a pulsar Conectar", false);
                return;
            }
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) {
                showStatus("No se pudo iniciar el escáner Bluetooth", false);
                return;
            }
            ScanFilter filter = new ScanFilter.Builder()
                    .setServiceUuid(new ParcelUuid(SERVICE_UUID)).build();
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            scanning = true;
            showStatus("Buscando CuboneESP32...", false);
            scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
            mainHandler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS);
        } catch (SecurityException error) {
            scanning = false;
            showStatus("Falta permiso Bluetooth", false);
        }
    }

    private final Runnable scanTimeout = () -> {
        if (!scanning) return;
        stopScan();
        showStatus("No se encontró CuboneESP32. Revisa energía y Bluetooth.", false);
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (device != null) runOnUiThread(() -> {
                if (!scanning) return;
                stopScan();
                connectDevice(device);
            });
        }

        @Override
        public void onScanFailed(int errorCode) {
            runOnUiThread(() -> {
                stopScan();
                showStatus("Error al buscar Bluetooth (" + errorCode + ")", false);
            });
        }
    };

    private void stopScan() {
        mainHandler.removeCallbacks(scanTimeout);
        if (!scanning) return;
        scanning = false;
        try {
            if (scanner != null) scanner.stopScan(scanCallback);
        } catch (SecurityException ignored) {
            // El usuario pudo revocar el permiso durante el escaneo.
        }
    }

    private void connectDevice(BluetoothDevice device) {
        try {
            showStatus("Conectando con CuboneESP32...", false);
            activeGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            if (activeGatt == null) showStatus("No se pudo conectar", false);
        } catch (SecurityException error) {
            showStatus("Falta permiso para conectar por Bluetooth", false);
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                showStatus("Leyendo servicio de orientación...", false);
                try {
                    if (!gatt.discoverServices()) failConnection(gatt, "No se pudo leer el servicio BLE");
                } catch (SecurityException error) {
                    failConnection(gatt, "Falta permiso Bluetooth");
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread(() -> {
                    if (activeGatt == gatt) activeGatt = null;
                    gatt.close();
                    showStatus("Bluetooth desconectado", false);
                    runScript("window.onBleDisconnected()");
                });
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failConnection(gatt, "No se encontró el servicio BLE");
                return;
            }
            android.bluetooth.BluetoothGattService service = gatt.getService(SERVICE_UUID);
            BluetoothGattCharacteristic characteristic = service == null ? null : service.getCharacteristic(QUAT_UUID);
            BluetoothGattDescriptor descriptor = characteristic == null
                    ? null : characteristic.getDescriptor(CLIENT_CONFIG_UUID);
            if (descriptor == null) {
                failConnection(gatt, "Firmware BLE incompatible: falta orientación");
                return;
            }
            try {
                if (!gatt.setCharacteristicNotification(characteristic, true)) {
                    failConnection(gatt, "No se pudo activar la orientación");
                    return;
                }
                boolean started;
                if (Build.VERSION.SDK_INT >= 33) {
                    started = gatt.writeDescriptor(descriptor,
                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS;
                } else {
                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    started = gatt.writeDescriptor(descriptor);
                }
                if (!started) failConnection(gatt, "No se pudo suscribir al sensor");
            } catch (SecurityException error) {
                failConnection(gatt, "Falta permiso Bluetooth");
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (!CLIENT_CONFIG_UUID.equals(descriptor.getUuid())) return;
            if (status == BluetoothGatt.GATT_SUCCESS) showStatus("CuboneESP32 conectado", true);
            else failConnection(gatt, "Falló la suscripción al sensor");
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            handleQuaternion(characteristic.getUuid(), characteristic.getValue());
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic, byte[] value) {
            handleQuaternion(characteristic.getUuid(), value);
        }
    };

    private void handleQuaternion(UUID characteristicUuid, byte[] bytes) {
        if (!QUAT_UUID.equals(characteristicUuid) || bytes == null || bytes.length != 16) return;
        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float x = data.getFloat(), y = data.getFloat(), z = data.getFloat(), w = data.getFloat();
        if (Float.isNaN(x) || Float.isNaN(y) || Float.isNaN(z) || Float.isNaN(w)
                || Float.isInfinite(x) || Float.isInfinite(y) || Float.isInfinite(z) || Float.isInfinite(w)) return;
        runScript("window.receiveBleQuaternion(" + x + "," + y + "," + z + "," + w + ")");
    }

    private void failConnection(BluetoothGatt gatt, String message) {
        showStatus(message, false);
        try {
            gatt.disconnect();
        } catch (SecurityException error) {
            gatt.close();
        }
    }

    private void disconnectRequested() {
        stopScan();
        if (activeGatt == null) {
            showStatus("Bluetooth desconectado", false);
            runScript("window.onBleDisconnected()");
            return;
        }
        try {
            activeGatt.disconnect();
        } catch (SecurityException error) {
            activeGatt.close();
            activeGatt = null;
            showStatus("Bluetooth desconectado", false);
            runScript("window.onBleDisconnected()");
        }
    }

    private void showStatus(String message, boolean connected) {
        runScript("window.updateBleStatus(" + JSONObject.quote(message) + "," + connected + ")");
    }

    private void runScript(String script) {
        runOnUiThread(() -> {
            if (pageReady && webView != null) webView.evaluateJavascript(script, null);
        });
    }

    @Override
    protected void onDestroy() {
        stopScan();
        if (activeGatt != null) {
            try { activeGatt.disconnect(); } catch (SecurityException ignored) { }
            activeGatt.close();
            activeGatt = null;
        }
        pageReady = false;
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
