package com.gemelodigital.esp32;

import android.content.SharedPreferences;
import android.content.Context;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** State transitions and messages translated directly from the former app.js wizard. */
final class OrientationController {
    static final String STORAGE_KEY = "orientationCalibration.v1";
    private final SharedPreferences preferences;
    final OrientationCore.Timeline timeline = new OrientationCore.Timeline();
    OrientationCore.Calibration calibration;
    OrientationCore.Quat reference;
    OrientationCore.Quat sensor = OrientationCore.Quat.identity();
    OrientationCore.Quat target = OrientationCore.Quat.identity();
    final OrientationCore.Quat[] samples = new OrientationCore.Quat[3];
    int wizardStep = -1;
    String wizardError = null;
    String zeroFeedback = "Volver a cero";
    long zeroFeedbackUntil;
    boolean connected, stale;
    String statusText = "Bluetooth desconectado";
    long lastSampleTime;

    OrientationController(Context context, SharedPreferences preferences) {
        this.preferences = preferences;
        String stored = preferences.getString(STORAGE_KEY, null);
        if (stored == null) {
            stored = WebViewCalibrationMigration.read(context);
            if (deserialize(stored) != null) preferences.edit().putString(STORAGE_KEY,stored).commit();
        }
        calibration = deserialize(stored);
        if (calibration == null) wizardStep = 0;
    }

    void updateBleStatus(String message, boolean isConnected) {
        statusText = message;
        connected = isConnected;
        if (!connected) { lastSampleTime = 0; stale = false; }
    }

    void disconnected() {
        connected = false;
        reference = null;
        timeline.clear();
        lastSampleTime = 0;
        stale = false;
        statusText = "Bluetooth desconectado";
        if (wizardStep > 0 && wizardStep < 3) {
            clearSamples(); wizardStep = 0;
            wizardError = "Se perdió la conexión. Repite los pasos desde la posición inicial.";
        }
    }

    void sample(float x, float y, float z, float w) {
        double lengthSquared = x*x + y*y + z*z + w*w;
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z) || !Float.isFinite(w)
                || lengthSquared < 0.5 || lengthSquared > 1.5) return;
        sensor = new OrientationCore.Quat(x,y,z,w).normalized();
        long now = SystemClock.uptimeMillis();
        updateModelOrientation(now);
        lastSampleTime = now;
        stale = false;
    }

    private void updateModelOrientation(long now) {
        if (calibration == null) return;
        if (reference == null) reference = OrientationCore.referenceForConnection(sensor, calibration.neutralUp);
        target = OrientationCore.modelMotion(sensor, reference, calibration.sensorToModel);
        timeline.push(target, now);
    }

    OrientationCore.Quat frame(long now) {
        if (connected && lastSampleTime != 0 && !stale && now-lastSampleTime > 2500) {
            stale = true;
            statusText = "Conectado sin nuevas muestras del sensor";
        }
        if (zeroFeedbackUntil != 0 && now >= zeroFeedbackUntil) {
            zeroFeedback = "Volver a cero";
            zeroFeedbackUntil = 0;
        }
        return timeline.at(now);
    }

    boolean hasFreshSample() {
        return connected && lastSampleTime != 0 && SystemClock.uptimeMillis()-lastSampleTime <= 600;
    }

    void startWizard() { clearSamples(); wizardStep = 0; wizardError = null; }

    void cancelWizard() {
        clearSamples(); wizardStep = calibration == null ? 0 : -1; wizardError = null;
    }

    void continueWizard() {
        if (wizardStep == 3) { wizardStep = -1; wizardError = null; return; }
        if (!hasFreshSample()) {
            wizardError = "Espera una muestra nueva del sensor antes de continuar.";
            return;
        }
        OrientationCore.Quat sample = sensor;
        try {
            if (wizardStep == 1) OrientationCore.axisFromPose(samples[0], sample);
            if (wizardStep == 2) {
                OrientationCore.Calibration result = OrientationCore.derive(samples[0],samples[1],sample);
                calibration = result;
                reference = samples[0];
                boolean saved = preferences.edit().putString(STORAGE_KEY,serialize(result)).commit();
                samples[2] = sample;
                wizardStep = 3;
                wizardError = saved ? null : "Calibración aplicada, pero no se pudo guardar para la próxima vez.";
                updateModelOrientation(SystemClock.uptimeMillis());
                return;
            }
            samples[wizardStep] = sample;
            wizardStep++;
            wizardError = null;
        } catch (IllegalArgumentException error) {
            wizardError = error.getMessage();
        }
    }

    void zero() {
        if (calibration == null || !hasFreshSample()) return;
        try {
            reference = OrientationCore.referenceAtSavedNeutral(sensor,calibration.neutralUp);
            updateModelOrientation(SystemClock.uptimeMillis());
            timeline.snap(target);
            zeroFeedback = "Cero actualizado";
        } catch (IllegalArgumentException error) {
            zeroFeedback = error.getMessage();
        }
        zeroFeedbackUntil = SystemClock.uptimeMillis()+2200;
    }

    private void clearSamples() { for (int i=0;i<samples.length;i++) samples[i]=null; }

    private static String serialize(OrientationCore.Calibration value) {
        try {
            JSONObject json = new JSONObject();
            json.put("version",1);
            JSONArray q = new JSONArray();
            q.put(value.sensorToModel.x); q.put(value.sensorToModel.y);
            q.put(value.sensorToModel.z); q.put(value.sensorToModel.w);
            json.put("sensorToModel",q);
            JSONArray up = new JSONArray();
            up.put(value.neutralUp.x); up.put(value.neutralUp.y); up.put(value.neutralUp.z);
            json.put("neutralUp",up);
            return json.toString();
        } catch (JSONException error) { throw new IllegalStateException(error); }
    }

    private static OrientationCore.Calibration deserialize(String stored) {
        if (stored == null) return null;
        try {
            JSONObject json = new JSONObject(stored);
            if (json.getInt("version") != 1) return null;
            JSONArray q = json.getJSONArray("sensorToModel"), up = json.getJSONArray("neutralUp");
            if (q.length()!=4 || up.length()!=3) return null;
            OrientationCore.Quat axes = new OrientationCore.Quat(q.getDouble(0),q.getDouble(1),q.getDouble(2),q.getDouble(3));
            OrientationCore.Vec3 vertical = new OrientationCore.Vec3(up.getDouble(0),up.getDouble(1),up.getDouble(2));
            if (!Double.isFinite(axes.lengthSquared()) || Math.abs(axes.lengthSquared()-1)>0.05
                    || !Double.isFinite(vertical.length()) || Math.abs(vertical.length()-1)>0.05) return null;
            return new OrientationCore.Calibration(axes.normalized(),vertical.normalized());
        } catch (JSONException error) { return null; }
    }
}
