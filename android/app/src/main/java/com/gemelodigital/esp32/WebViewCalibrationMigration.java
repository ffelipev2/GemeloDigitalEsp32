package com.gemelodigital.esp32;

import android.content.Context;
import android.util.Log;

import org.iq80.leveldb.DB;
import org.iq80.leveldb.DBIterator;
import org.iq80.leveldb.Options;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.iq80.leveldb.impl.Iq80DBFactory.factory;

/** Reads the previous WebView localStorage once; the native app never creates a WebView. */
final class WebViewCalibrationMigration {
    private static final String TAG = "CalibrationMigration";
    private WebViewCalibrationMigration() { }

    static String read(Context context) {
        File webview = new File(context.getApplicationInfo().dataDir,"app_webview");
        for (String path : new String[]{"Default/Local Storage/leveldb","Local Storage/leveldb"}) {
            File directory = new File(webview,path);
            if (!directory.isDirectory()) continue;
            try (DB db = factory.open(directory,new Options().createIfMissing(false));
                 DBIterator entries = db.iterator()) {
                for (entries.seekToFirst(); entries.hasNext();) {
                    Map.Entry<byte[],byte[]> entry = entries.next();
                    String key = new String(entry.getKey(),StandardCharsets.ISO_8859_1);
                    if (!key.contains("gemelo.local") ||
                            !(contains(entry.getKey(),OrientationController.STORAGE_KEY.getBytes(StandardCharsets.ISO_8859_1))
                                    || contains(entry.getKey(),OrientationController.STORAGE_KEY.getBytes(StandardCharsets.UTF_16LE)))) continue;
                    byte[] value = entry.getValue();
                    if (value.length < 2) continue;
                    String stored = value[0] == 0
                            ? new String(value,1,value.length-1,StandardCharsets.UTF_16LE)
                            : value[0] == 1
                            ? new String(value,1,value.length-1,StandardCharsets.ISO_8859_1)
                            : new String(value,StandardCharsets.UTF_8);
                    int start = stored.indexOf('{'), end = stored.lastIndexOf('}');
                    if (start >= 0 && end > start) return stored.substring(start,end+1);
                }
            } catch (Exception error) {
                Log.w(TAG,"No se pudo leer la calibración previa",error);
            }
        }
        return null;
    }

    private static boolean contains(byte[] source, byte[] needle) {
        for (int i=0;i<=source.length-needle.length;i++) {
            int j=0;
            while (j<needle.length && source[i+j]==needle[j]) j++;
            if (j==needle.length) return true;
        }
        return false;
    }
}
