package com.gemelodigital.esp32;

import android.Manifest;
import androidx.activity.ComponentActivity;
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
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.UUID;

public final class MainActivity extends ComponentActivity {
    private static final UUID SERVICE_UUID = UUID.fromString("6a59d32b-158a-4c76-8c7e-7a4a5ab48152");
    private static final UUID QUAT_UUID = UUID.fromString("6a59d32b-158a-4c76-8c7e-7a4a5ab48153");
    private static final UUID CLIENT_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final int PERMISSION_REQUEST = 10;
    private static final long SCAN_TIMEOUT_MS = 12000;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private OrientationController orientation;
    private NativeDashboard dashboard;
    private long lastDashboardRefresh;
    private boolean scanning;
    private BluetoothLeScanner scanner;
    private volatile BluetoothGatt activeGatt;
    private volatile BluetoothGattCharacteristic quaternionCharacteristic;
    private volatile long lastSampleAtMs;
    private volatile long lastNotificationAtMs;
    private volatile boolean readPending;
    private volatile int lastSequence = -1;
    private long feedStartedAtMs;
    private boolean noDataShown;
    private volatile String disconnectReason;

    // Si las notificaciones se detienen, leer el último valor BLE en forma periódica.
    private final Runnable sampleWatchdog = new Runnable() {
        @Override
        public void run() {
            BluetoothGatt gatt = activeGatt;
            BluetoothGattCharacteristic characteristic = quaternionCharacteristic;
            if (gatt == null || characteristic == null) return;
            long now = SystemClock.elapsedRealtime();
            if (!noDataShown && now - feedStartedAtMs > 2500
                    && (lastSampleAtMs == 0 || now - lastSampleAtMs > 2500)) {
                noDataShown = true;
                showStatus("Conectado sin datos del sensor", true);
            }
            if (now - lastNotificationAtMs > 500 && !readPending) {
                try {
                    readPending = gatt.readCharacteristic(characteristic);
                } catch (SecurityException error) {
                    showStatus("Falta permiso Bluetooth", false);
                }
            }
            mainHandler.postDelayed(this, 250);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        orientation = new OrientationController(this,getSharedPreferences("orientation", MODE_PRIVATE));
        dashboard = new NativeDashboard(this, orientation);
        dashboard.setActions(new NativeDashboard.Actions() {
            @Override public void connect() { connectRequested(); }
            @Override public void disconnect() { disconnectRequested(); }
        });
        dashboard.scene.setFrameListener(time -> {
            long now = SystemClock.uptimeMillis();
            OrientationCore.Quat displayed = orientation.frame(now);
            dashboard.scene.applyOrientation((float)displayed.x,(float)displayed.y,
                    (float)displayed.z,(float)displayed.w);
            dashboard.updateAngles(displayed, now);
            if (now-lastDashboardRefresh >= 100) {
                dashboard.refresh();
                lastDashboardRefresh = now;
            }
        });
        setContentView(dashboard);
        showStatus("Pulsa Conectar Bluetooth", false);
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
            quaternionCharacteristic = null;
            lastSampleAtMs = 0;
            lastNotificationAtMs = 0;
            lastSequence = -1;
            readPending = false;
            disconnectReason = null;
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
                    boolean wasActive = activeGatt == gatt;
                    if (wasActive) {
                        activeGatt = null;
                        quaternionCharacteristic = null;
                        readPending = false;
                        mainHandler.removeCallbacks(sampleWatchdog);
                    }
                    gatt.close();
                    if (wasActive) {
                        String reason = disconnectReason;
                        disconnectReason = null;
                        notifyDisconnected();
                        showStatus(reason == null ? "Bluetooth desconectado" : reason, false);
                    }
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
            if (characteristic == null) {
                failConnection(gatt, "Firmware BLE incompatible: falta orientación");
                return;
            }
            quaternionCharacteristic = characteristic;
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CLIENT_CONFIG_UUID);
            if (descriptor == null) {
                startDataFeed(gatt, false);
                return;
            }
            try {
                if (!gatt.setCharacteristicNotification(characteristic, true)) {
                    startDataFeed(gatt, false);
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
                if (!started) startDataFeed(gatt, false);
            } catch (SecurityException error) {
                failConnection(gatt, "Falta permiso Bluetooth");
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (!CLIENT_CONFIG_UUID.equals(descriptor.getUuid())) return;
            startDataFeed(gatt, status == BluetoothGatt.GATT_SUCCESS);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            handleQuaternion(gatt, characteristic.getUuid(), characteristic.getValue(), true);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic, byte[] value) {
            handleQuaternion(gatt, characteristic.getUuid(), value, true);
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic, int status) {
            readPending = false;
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleQuaternion(gatt, characteristic.getUuid(), characteristic.getValue(), false);
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic, byte[] value, int status) {
            readPending = false;
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleQuaternion(gatt, characteristic.getUuid(), value, false);
            }
        }
    };

    private void startDataFeed(BluetoothGatt gatt, boolean notificationsEnabled) {
        runOnUiThread(() -> {
            if (activeGatt != gatt || quaternionCharacteristic == null) return;
            feedStartedAtMs = SystemClock.elapsedRealtime();
            lastNotificationAtMs = notificationsEnabled ? feedStartedAtMs : 0;
            noDataShown = false;
            showStatus("Esperando datos del sensor", true);
            mainHandler.removeCallbacks(sampleWatchdog);
            mainHandler.post(sampleWatchdog);
        });
    }

    private void handleQuaternion(BluetoothGatt gatt, UUID characteristicUuid,
                                  byte[] bytes, boolean notification) {
        if (activeGatt != gatt || !QUAT_UUID.equals(characteristicUuid)
                || bytes == null || (bytes.length != 18 && bytes.length != 16)) return;
        long now = SystemClock.elapsedRealtime();
        if (notification) lastNotificationAtMs = now;
        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float x = data.getFloat(), y = data.getFloat(), z = data.getFloat(), w = data.getFloat();
        if (Float.isNaN(x) || Float.isNaN(y) || Float.isNaN(z) || Float.isNaN(w)
                || Float.isInfinite(x) || Float.isInfinite(y) || Float.isInfinite(z) || Float.isInfinite(w)) return;
        if (bytes.length == 18) {
            int sequence = data.getShort() & 0xffff;
            if (sequence == 0 || sequence == lastSequence) return;
            lastSequence = sequence;
        }
        long previous = lastSampleAtMs;
        lastSampleAtMs = now;
        runOnUiThread(() -> {
            if (activeGatt == gatt) {
                orientation.sample(x, y, z, w);
                dashboard.refresh();
            }
        });
        if (previous == 0 || now - previous > 2500) {
            runOnUiThread(() -> {
                if (activeGatt == gatt) {
                    noDataShown = false;
                    showStatus("CuboneESP32 conectado", true);
                }
            });
        }
    }

    private void failConnection(BluetoothGatt gatt, String message) {
        disconnectReason = message;
        try {
            gatt.disconnect();
        } catch (SecurityException error) {
            gatt.close();
            runOnUiThread(() -> {
                if (activeGatt == gatt) {
                    activeGatt = null;
                    quaternionCharacteristic = null;
                    mainHandler.removeCallbacks(sampleWatchdog);
                    notifyDisconnected();
                    showStatus(message, false);
                }
            });
        }
    }

    private void disconnectRequested() {
        stopScan();
        disconnectReason = null;
        if (activeGatt == null) {
            showStatus("Bluetooth desconectado", false);
            notifyDisconnected();
            return;
        }
        try {
            activeGatt.disconnect();
        } catch (SecurityException error) {
            activeGatt.close();
            activeGatt = null;
            showStatus("Bluetooth desconectado", false);
            notifyDisconnected();
        }
    }

    private void showStatus(String message, boolean connected) {
        runOnUiThread(() -> {
            if (orientation != null) {
                orientation.updateBleStatus(message, connected);
                dashboard.refresh();
            }
        });
    }

    private void notifyDisconnected() {
        runOnUiThread(() -> {
            if (orientation != null) {
                orientation.disconnected();
                dashboard.refresh();
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (dashboard != null && dashboard.handleBack()) return;
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        stopScan();
        mainHandler.removeCallbacks(sampleWatchdog);
        if (activeGatt != null) {
            try { activeGatt.disconnect(); } catch (SecurityException ignored) { }
            activeGatt.close();
            activeGatt = null;
        }
        if (dashboard != null) dashboard.scene.destroy();
        super.onDestroy();
    }
}
