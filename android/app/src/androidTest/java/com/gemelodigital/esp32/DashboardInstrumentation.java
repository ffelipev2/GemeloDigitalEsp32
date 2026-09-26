package com.gemelodigital.esp32;

import android.app.Instrumentation;
import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.ScanCallback;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Device/emulator regression checks. Simulated sensor state; no ESP32 connection is claimed. */
public final class DashboardInstrumentation extends Instrumentation {
    private MainActivity activity;
    private NativeDashboard dashboard;
    private OrientationController orientation;
    private String scope;

    @Override public void onCreate(Bundle arguments) {
        scope=arguments==null?null:arguments.getString("scope"); super.onCreate(arguments); start();
    }

    @Override public void onStart() {
        Bundle result=new Bundle();
        int resultCode=0;
        SharedPreferences preferences=getTargetContext().getSharedPreferences("orientation",0);
        String previous=preferences.getString(OrientationController.STORAGE_KEY,null);
        SharedPreferences viewerPreferences=getTargetContext().getSharedPreferences("viewer",0);
        boolean hadZoom=viewerPreferences.contains("zoom.v1");
        float previousZoom=viewerPreferences.getFloat("zoom.v1",1f);
        try {
            if("controls-motion".equals(scope)) {
                checkControlsAndUncalibratedMotion(preferences);
                result.putString("stream","PASS: settings actions fully visible and fixed while scrolling/rotating; first-connection uncalibrated quaternion motion reaches the native model in portrait/landscape; reconnect, cancellation, calibration and saved mounting remain functional\n");
            } else {
            checkOptionalFirstLaunch(preferences);
            preferences.edit().putString(OrientationController.STORAGE_KEY,
                    "{\"version\":1,\"sensorToModel\":[0,0,0,1],\"neutralUp\":[0,0,1]}").commit();
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            dashboard=(NativeDashboard)field(activity,"dashboard");
            orientation=(OrientationController)field(activity,"orientation");
            rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            checkLayout(); screenshot("portrait.png");
            rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            checkLayout(); screenshot("landscape.png");
            progress("Portrait/landscape layout passed");

            // The same live orientation, neutral reference and renderer survive both rotations.
            runOnMainSync(() -> {
                orientation.connected=true;
                orientation.sample(0,0,0,1);
            });
            Object reference=orientation.reference;
            Object calibration=orientation.calibration;
            Object scene=dashboard.scene;
            rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            require(!activity.isDestroyed(),"Rotation destroyed the BLE-owning Activity");
            require(field(activity,"dashboard")==dashboard && dashboard.scene==scene,"Rotation reloaded the scene");
            require(field(activity,"orientation")==orientation && orientation.connected,"Rotation lost connection state");
            require(orientation.reference==reference && orientation.calibration==calibration,"Rotation reset calibration/reference");
            checkZero(); rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT); checkZero();

            runOnMainSync(() -> {
                orientation.sample(0,0,0,1); orientation.startWizard(); orientation.continueWizard(); dashboard.refresh();
            });
            Object sample=orientation.samples[0];
            rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            require(orientation.wizardStep==1 && orientation.samples[0]==sample,"Rotation reset the calibration wizard");
            rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            require(orientation.wizardStep==1 && orientation.samples[0]==sample,"Landscape reset the calibration wizard");
            Rect next=bounds((View)field(dashboard,"wizardNext"));
            require(next.bottom<=dashboard.getHeight(),"Wizard Continue is outside the window");
            screenshot("wizard-landscape.png");
            runOnMainSync(() -> { orientation.cancelWizard(); orientation.disconnected(); dashboard.refresh(); });

            runOnMainSync(() -> { for(int i=0;i<20;i++) dashboard.scene.zoomBy(.12f); });
            require(((Number)field(dashboard.scene,"zoom")).floatValue()<=1.5f,"Unbounded zoom");
            runOnMainSync(() -> dashboard.scene.resetZoom());
            require(((Number)field(dashboard.scene,"zoom")).floatValue()==1f,"View reset did not restore zoom");
            runOnMainSync(() -> {
                try { ((View)field(dashboard,"axesButton")).performClick(); } catch(Exception e) { throw new RuntimeException(e); }
            });
            screenshot("axes-landscape.png");
            runOnMainSync(() -> {
                try { ((View)field(dashboard,"axesButton")).performClick(); ((View)field(dashboard,"fullscreenButton")).performClick(); }
                catch(Exception e) { throw new RuntimeException(e); }
            });
            screenshot("expanded-landscape.png");
            rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT); screenshot("expanded-portrait.png");
            runOnMainSync(() -> {
                try { ((View)field(dashboard,"fullscreenButton")).performClick(); } catch(Exception e) { throw new RuntimeException(e); }
            });
            checkLayout();
            checkViewButtonAndSavedZoom(viewerPreferences);
            checkBleRetry();
            result.putString("stream","PASS: optional first launch, nonmodal/cancellable calibration, equivalent portrait/landscape controls and zero, retained Activity/scene/connection/wizard, zoom persistence after relaunch, axes, Vista 3D, scan/GATT timeouts, cancel/retry, stale callbacks\n");
            }
            resultCode=-1;
        } catch(Throwable error) {
            result.putString("stream","FAIL: "+android.util.Log.getStackTraceString(error));
        } finally {
            SharedPreferences.Editor edit=preferences.edit();
            if(previous==null) edit.remove(OrientationController.STORAGE_KEY); else edit.putString(OrientationController.STORAGE_KEY,previous);
            edit.commit();
            SharedPreferences.Editor viewerEdit=viewerPreferences.edit();
            if(hadZoom) viewerEdit.putFloat("zoom.v1",previousZoom); else viewerEdit.remove("zoom.v1");
            viewerEdit.commit();
        }
        finish(resultCode,result);
    }

    private void checkControlsAndUncalibratedMotion(SharedPreferences preferences) throws Exception {
        preferences.edit().remove(OrientationController.STORAGE_KEY).commit();
        activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        dashboard=(NativeDashboard)field(activity,"dashboard"); orientation=(OrientationController)field(activity,"orientation");
        require(orientation.calibration==null && orientation.wizardStep==-1,"Uncalibrated launch opened wizard");
        OrientationCore.Vec3 x=new OrientationCore.Vec3(1,0,0),y=new OrientationCore.Vec3(0,1,0),z=new OrientationCore.Vec3(0,0,1);
        OrientationCore.Quat neutral=turn(z,.4).mul(turn(x,.6));
        runOnMainSync(() -> { orientation.updateBleStatus("Conectado",true); sample(neutral); });
        equal(orientation.target,OrientationCore.Quat.identity(),"First tilted reading did not establish temporary neutral");
        Object initialReference=orientation.reference;
        OrientationCore.Quat[] motions={turn(x,.55),turn(y,-.4),turn(z,.7),turn(z,-.3).mul(turn(x,.4)).mul(turn(y,.2))};
        OrientationCore.Quat[] expected={turn(x,.55),turn(z,.4),turn(y,.7),turn(y,-.3).mul(turn(x,.4)).mul(turn(z,-.2))};
        for(int requested:new int[]{ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}) {
            rotate(requested); checkLayout();
            require(orientation.connected && orientation.reference==initialReference,"Rotation lost uncalibrated connection/reference");
            click("settingsButton"); checkSettingsActions();
            Rect closeBefore=bounds((View)field(dashboard,"settingsClose"));
            runOnMainSync(() -> ((android.widget.ScrollView)uncheckedField(dashboard,"settingsScroll")).fullScroll(View.FOCUS_DOWN));
            checkSettingsActions();
            require(closeBefore.equals(bounds((View)field(dashboard,"settingsClose"))),"Scrolling moved Close outside panel");
            screenshot(requested==ActivityInfo.SCREEN_ORIENTATION_PORTRAIT?"controls-portrait.png":"controls-landscape.png");
            rotate(requested==ActivityInfo.SCREEN_ORIENTATION_PORTRAIT?ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            checkSettingsActions(); rotate(requested); checkSettingsActions();
            click("settingsClose"); require(((View)field(dashboard,"settings")).getVisibility()==View.GONE,"Close did not dismiss controls");
            require(((View)field(dashboard,"modalScrim")).getVisibility()==View.GONE,"Close left modal scrim active");
            for(int pose=0;pose<motions.length;pose++) {
                OrientationCore.Quat reading=neutral.mul(motions[pose]);
                for(int frame=0;frame<10;frame++) { runOnMainSync(() -> sample(reading)); SystemClock.sleep(40); }
                equal(orientation.target,expected[pose],"Uncalibrated axis/mixed quaternion mapping");
                Object pivot=field(dashboard.scene,"modelPivot");
                Object rendered=pivot.getClass().getMethod("getQuaternion").invoke(pivot);
                OrientationCore.Quat renderedQ=new OrientationCore.Quat(component(rendered,"X"),component(rendered,"Y"),component(rendered,"Z"),component(rendered,"W")).normalized();
                require(renderedQ.angleTo(expected[pose])<.025,"Uncalibrated motion did not reach native model");
            }
            require(orientation.calibration==null && !preferences.contains(OrientationController.STORAGE_KEY),"Temporary motion saved a fake calibration");
            require(((TextView)field(dashboard,"calibrationBadge")).getText().toString().equals("Sin calibrar"),"Temporary motion presented as calibrated");
            screenshot(requested==ActivityInfo.SCREEN_ORIENTATION_PORTRAIT?"uncalibrated-motion-portrait.png":"uncalibrated-motion-landscape.png");
        }
        progress("Close/Bluetooth remain inside panel while scrolling/rotating; all uncalibrated axes and mixed motion reach native scene in both layouts");
        OrientationCore.Quat nextNeutral=turn(y,.8).mul(turn(x,-.4));
        runOnMainSync(() -> { orientation.disconnected(); orientation.updateBleStatus("Conectado",true); sample(nextNeutral); });
        equal(orientation.target,OrientationCore.Quat.identity(),"Reconnect retained previous temporary neutral");
        Object reference=orientation.reference;
        runOnMainSync(() -> { orientation.startWizard(); orientation.continueWizard(); orientation.cancelWizard(); sample(nextNeutral.mul(turn(z,.5))); dashboard.refresh(); });
        require(orientation.wizardStep==-1 && orientation.reference==reference,"Cancelling optional calibration lost temporary movement");
        equal(orientation.target,turn(y,.5),"Movement stopped after cancelling initial calibration");
        runOnMainSync(() -> {
            orientation.startWizard(); sample(nextNeutral); orientation.continueWizard();
            sample(nextNeutral.mul(turn(x,.6))); orientation.continueWizard();
            sample(nextNeutral.mul(turn(z,-.6))); orientation.continueWizard(); dashboard.refresh();
        });
        require(orientation.wizardStep==3 && orientation.calibration!=null && preferences.contains(OrientationController.STORAGE_KEY),"Optional calibration failed after temporary motion");
        equal(orientation.calibration.sensorToModel,OrientationCore.Quat.identity(),"Existing calibration axis solver changed");
        runOnMainSync(() -> { orientation.continueWizard(); sample(nextNeutral.mul(turn(y,.5))); dashboard.refresh(); });
        equal(orientation.target,turn(y,.5),"Saved calibration did not replace temporary axes");
        OrientationController reloaded=new OrientationController(getTargetContext(),preferences);
        require(reloaded.calibration!=null && reloaded.wizardStep==-1,"Saved calibration no longer restores optionally");
        equal(reloaded.calibration.sensorToModel,orientation.calibration.sensorToModel,"Reload lost mounting calibration");
        progress("Reconnect and cancellation preserve immediate movement; completing calibration replaces nominal axes and restores from storage");
    }

    private void checkSettingsActions() throws Exception {
        SystemClock.sleep(300); waitForIdleSync();
        View panel=(View)field(dashboard,"settings"); require(panel.isShown(),"Controls panel disappeared");
        Rect panelBounds=bounds(panel);
        require(panelBounds.left>=0 && panelBounds.top>=0 && panelBounds.right<=dashboard.getWidth() && panelBounds.bottom<=dashboard.getHeight(),"Controls panel outside screen");
        for(String name:new String[]{"bleButton","settingsClose"}) {
            View view=(View)field(dashboard,name); Rect visible=new Rect();
            require(view.isShown() && panelBounds.contains(bounds(view)),name+" outside controls window");
            require(view.getGlobalVisibleRect(visible) && visible.width()==view.getWidth() && visible.height()==view.getHeight(),name+" clipped in controls window");
        }
    }

    private void sample(OrientationCore.Quat q) { orientation.sample((float)q.x,(float)q.y,(float)q.z,(float)q.w); }
    private static OrientationCore.Quat turn(OrientationCore.Vec3 axis,double angle) { return OrientationCore.Quat.fromAxisAngle(axis,angle); }
    private static void equal(OrientationCore.Quat actual,OrientationCore.Quat expected,String message) { require(actual.angleTo(expected)<.0001,message); }
    private static double component(Object quaternion,String name) throws Exception { return ((Number)quaternion.getClass().getMethod("get"+name).invoke(quaternion)).doubleValue(); }

    private void checkOptionalFirstLaunch(SharedPreferences preferences) throws Exception {
        preferences.edit().remove(OrientationController.STORAGE_KEY).commit();
        activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        dashboard=(NativeDashboard)field(activity,"dashboard"); orientation=(OrientationController)field(activity,"orientation");
        for(int requested:new int[]{ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}) {
            rotate(requested); checkLayout();
            require(orientation.calibration==null && orientation.wizardStep==-1,"First launch forced calibration");
            require(((View)field(dashboard,"wizard")).getVisibility()==View.GONE,"First launch opened wizard");
            require(((View)field(dashboard,"modalScrim")).getVisibility()==View.GONE,"First launch blocked controls");
            require(!(Boolean)field(activity,"scanning") && field(activity,"activeGatt")==null,"First launch started Bluetooth");
            require(!((View)field(dashboard,"zeroButton")).isEnabled(),"Zero enabled before calibration");
            screenshot(requested==ActivityInfo.SCREEN_ORIENTATION_PORTRAIT?"first-launch-portrait.png":"first-launch-landscape.png");
        }
        getUiAutomation().grantRuntimePermission(getTargetContext().getPackageName(),Manifest.permission.BLUETOOTH_SCAN);
        getUiAutomation().grantRuntimePermission(getTargetContext().getPackageName(),Manifest.permission.BLUETOOTH_CONNECT);
        for(int requested:new int[]{ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}) {
            rotate(requested);
            // Connecting and browsing the model do not require opening or completing calibration.
            click("bluetoothCard"); require((Boolean)field(activity,"scanning"),"Optional connect failed");
            require(orientation.wizardStep==-1,"Connecting forced calibration");
            click("bluetoothCard");
            click("calibrateButton"); checkOptionalWizard();
            screenshot(requested==ActivityInfo.SCREEN_ORIENTATION_PORTRAIT?"optional-wizard-portrait.png":"optional-wizard-landscape.png");
            click("bluetoothCard"); require((Boolean)field(activity,"scanning"),"Wizard blocked Bluetooth");
            click("bluetoothCard"); require(orientation.wizardStep==0,"Cancelling connection closed optional wizard");
            float zoom=((Number)field(dashboard.scene,"zoom")).floatValue();
            runOnMainSync(() -> ((android.widget.LinearLayout)uncheckedField(dashboard,"zoomControls")).getChildAt(0).performClick());
            require(((Number)field(dashboard.scene,"zoom")).floatValue()!=zoom || zoom>=1.5f,"Wizard blocked zoom");
            click("axesButton"); require((Boolean)field(dashboard,"axesVisible"),"Wizard blocked axes"); click("axesButton");
            click("settingsButton");
            require(((View)field(dashboard,"settings")).getVisibility()==View.VISIBLE,"Wizard blocked settings");
            require(((View)field(dashboard,"wizard")).getVisibility()==View.GONE && orientation.wizardStep==0,"Settings lost wizard state");
            runOnMainSync(() -> require(dashboard.handleBack(),"Back did not close settings")); checkOptionalWizard();
            click("viewButton"); require((Boolean)field(dashboard,"expanded"),"Wizard blocked Vista 3D");
            require(((View)field(dashboard,"wizard")).getVisibility()==View.GONE && orientation.wizardStep==0,"Vista 3D lost wizard state");
            click("fullscreenButton"); checkOptionalWizard();
            click("wizardCancel"); require(orientation.wizardStep==-1 && orientation.calibration==null,"Cancel forced initial calibration");
            require(((View)field(dashboard,"wizard")).getVisibility()==View.GONE,"Cancel did not close wizard");
            click("calibrateButton");
            runOnMainSync(() -> {
                orientation.connected=true; orientation.sample(0,0,0,1); orientation.continueWizard();
                orientation.sample((float)Math.sin(.3),0,0,(float)Math.cos(.3)); orientation.continueWizard(); dashboard.refresh();
            });
            require(orientation.wizardStep==2,"Simulated wizard did not reach third pose");
            OrientationController sameController=orientation; Object firstSample=orientation.samples[0];
            rotate(requested==ActivityInfo.SCREEN_ORIENTATION_PORTRAIT?ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            checkOptionalWizard();
            require(field(activity,"orientation")==sameController && orientation.samples[0]==firstSample,"Rotation lost uncalibrated wizard sample");
            runOnMainSync(() -> require(dashboard.handleBack(),"Back did not cancel initial calibration"));
            require(orientation.wizardStep==-1 && orientation.samples[0]==null && orientation.calibration==null,"Back did not abandon unsaved calibration");
            runOnMainSync(() -> { orientation.disconnected(); dashboard.refresh(); });
        }
        runOnMainSync(() -> activity.finish()); waitForIdleSync();
        activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        dashboard=(NativeDashboard)field(activity,"dashboard"); orientation=(OrientationController)field(activity,"orientation");
        require(orientation.calibration==null && orientation.wizardStep==-1,"Relaunch forced unfinished calibration");
        require(!(Boolean)field(activity,"scanning") && field(activity,"activeGatt")==null,"Relaunch forced connection");
        runOnMainSync(() -> activity.finish()); waitForIdleSync();
        progress("First launch optional; calibration can be cancelled and all viewer controls work in both orientations");
    }

    private void checkOptionalWizard() throws Exception {
        checkLayout();
        require(((View)field(dashboard,"wizard")).getVisibility()==View.VISIBLE,"Optional wizard not visible");
        require(((View)field(dashboard,"modalScrim")).getVisibility()==View.GONE,"Calibration blocked surrounding controls");
        Rect panel=bounds((View)field(dashboard,"wizard"));
        require(panel.left>=0 && panel.top>=0 && panel.right<=dashboard.getWidth() && panel.bottom<=dashboard.getHeight(),"Wizard outside window");
        for(String name:new String[]{"header","topTools","tools","zoomControls","axesButton","orientationCard","bluetoothCard"})
            require(!Rect.intersects(panel,bounds((View)field(dashboard,name))),"Wizard covers "+name);
        for(String name:new String[]{"wizardTitle","wizardCancel","wizardNext"}) {
            View view=(View)field(dashboard,name);
            require(view.isShown() && panel.contains(bounds(view)),name+" inaccessible in wizard");
        }
        require(((View)field(dashboard,"wizardCancel")).isEnabled(),"Cancel disabled before saved calibration");
    }

    private void checkZero() {
        runOnMainSync(() -> {
            orientation.sample(0,0,0,1); dashboard.refresh();
            View zero=(View)uncheckedField(dashboard,"zeroButton");
            require(zero.isShown() && zero.isEnabled(),"Zero unavailable with calibrated sample");
            zero.performClick(); require(orientation.zeroFeedback.equals("Cero actualizado"),"Zero failed");
        });
    }

    private static Object uncheckedField(Object object,String name) {
        try { return field(object,name); } catch(Exception error) { throw new RuntimeException(error); }
    }

    private void checkViewButtonAndSavedZoom(SharedPreferences preferences) throws Exception {
        click("viewButton");
        require((Boolean)field(dashboard,"expanded"),"Vista 3D did not open expanded view");
        require(((View)field(dashboard,"tools")).getVisibility()==View.GONE,"Vista 3D did not hide controls");
        require(dashboard.scene.getParent()==dashboard,"Vista 3D detached the native scene");
        click("fullscreenButton");
        runOnMainSync(() -> { dashboard.scene.resetZoom(); dashboard.scene.zoomBy(.12f); dashboard.scene.zoomBy(.12f); });
        float chosen=((Number)field(dashboard.scene,"zoom")).floatValue();
        require(Math.abs(preferences.getFloat("zoom.v1",0)-chosen)<.00001f,"Zoom not saved");
        click("viewButton"); click("fullscreenButton");
        require(((Number)field(dashboard.scene,"zoom")).floatValue()==chosen,"Vista 3D lost the chosen zoom");
        runOnMainSync(() -> activity.finish()); waitForIdleSync();
        activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        dashboard=(NativeDashboard)field(activity,"dashboard"); orientation=(OrientationController)field(activity,"orientation");
        rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        require(((Number)field(dashboard.scene,"zoom")).floatValue()==chosen,"Relaunch lost zoom");
        rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        require(((Number)field(dashboard.scene,"zoom")).floatValue()==chosen,"Rotation lost zoom");
        progress("Vista 3D, saved zoom and activity relaunch passed");
    }

    private void checkBleRetry() throws Exception {
        getUiAutomation().grantRuntimePermission(getTargetContext().getPackageName(),Manifest.permission.BLUETOOTH_SCAN);
        getUiAutomation().grantRuntimePermission(getTargetContext().getPackageName(),Manifest.permission.BLUETOOTH_CONNECT);
        BluetoothManager manager=(BluetoothManager)getTargetContext().getSystemService(android.content.Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter=manager.getAdapter(); require(adapter!=null && adapter.isEnabled(),"Enable emulator Bluetooth before running");
        click("bluetoothCard");
        require((Boolean)field(activity,"scanning"),"Scan did not start");
        require(((View)field(dashboard,"bluetoothCard")).isEnabled(),"Busy Bluetooth card disabled");
        require(((TextView)field(dashboard,"bleButton")).getText().toString().equals("Cancelar conexión"),"Cancel action not shown");
        Object oldScanCallback=field(activity,"activeScanCallback");
        waitUntilReleased("scanning",false,18000);
        require(orientation.statusText.startsWith("No se encontró"),"Scan timeout did not show retry message");
        click("bluetoothCard");
        require((Boolean)field(activity,"scanning") && field(activity,"activeScanCallback")!=oldScanCallback,"Cannot retry after scan timeout");
        Object currentScan=field(activity,"activeScanCallback");
        runOnMainSync(() -> ((ScanCallback)oldScanCallback).onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR));
        require((Boolean)field(activity,"scanning") && field(activity,"activeScanCallback")==currentScan,"Late old scan failure stopped retry");
        click("bluetoothCard");
        require(!(Boolean)field(activity,"scanning"),"Cancel did not stop scan");
        progress("ESP32 absent: scan timeout and immediate retry/cancel passed");

        BluetoothDevice absent=adapter.getRemoteDevice("02:00:00:00:FF:01");
        invoke("connectDevice",new Class<?>[]{BluetoothDevice.class},absent);
        BluetoothGatt oldGatt=(BluetoothGatt)field(activity,"activeGatt");
        require(oldGatt!=null,"GATT attempt did not start");
        waitUntilReleased("activeGatt",null,20000);
        require(!orientation.connected && !orientation.statusText.startsWith("Conectando"),"GATT timeout stuck connecting");
        invoke("connectDevice",new Class<?>[]{BluetoothDevice.class},absent);
        BluetoothGatt newGatt=(BluetoothGatt)field(activity,"activeGatt");
        require(newGatt!=null && newGatt!=oldGatt,"Cannot retry after GATT timeout");
        String status=orientation.statusText;
        Object deadline=field(activity,"connectionTimeout");
        BluetoothGattCallback callback=(BluetoothGattCallback)field(activity,"gattCallback");
        runOnMainSync(() -> callback.onConnectionStateChange(oldGatt,BluetoothGatt.GATT_SUCCESS,BluetoothProfile.STATE_CONNECTED));
        require(field(activity,"activeGatt")==newGatt && field(activity,"connectionTimeout")==deadline
                && orientation.statusText.equals(status),"Late old GATT callback corrupted retry");
        click("bluetoothCard");
        require(field(activity,"activeGatt")==null && field(activity,"connectionTimeout")==null,"Cancel did not free GATT/deadline");
        invoke("connectDevice",new Class<?>[]{BluetoothDevice.class},absent);
        require(field(activity,"activeGatt")!=null,"Cannot retry after cancelling GATT");
        click("bluetoothCard");
        progress("Absent peripheral: GATT timeout, retry, cancellation and stale callback passed");
    }

    private void waitUntilReleased(String name,Object value,long timeout) throws Exception {
        long deadline=SystemClock.uptimeMillis()+timeout;
        while(SystemClock.uptimeMillis()<deadline) {
            waitForIdleSync(); Object current=field(activity,name);
            if(value==null?current==null:value.equals(current)) return;
            SystemClock.sleep(100);
        }
        throw new AssertionError(name+" did not release within timeout");
    }

    private void click(String name) {
        runOnMainSync(() -> {
            try { require(((View)field(dashboard,name)).performClick(),name+" not clickable"); }
            catch(Exception error) { throw new RuntimeException(error); }
        });
    }

    private void invoke(String name,Class<?>[] types,Object... arguments) {
        runOnMainSync(() -> {
            try { Method method=MainActivity.class.getDeclaredMethod(name,types); method.setAccessible(true); method.invoke(activity,arguments); }
            catch(Exception error) { throw new RuntimeException(error); }
        });
    }

    private void progress(String message) { Bundle status=new Bundle(); status.putString("stream",message+"\n"); sendStatus(1,status); }

    private void rotate(int requested) throws Exception {
        runOnMainSync(() -> activity.setRequestedOrientation(requested));
        boolean landscape=requested==ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        long deadline=SystemClock.uptimeMillis()+20000;
        while(SystemClock.uptimeMillis()<deadline) {
            if(activity.isDestroyed()) throw new AssertionError("Activity recreated during rotation");
            if((dashboard.getWidth()>dashboard.getHeight())==landscape && dashboard.getWidth()>0) {
                SystemClock.sleep(800); waitForIdleSync(); return;
            }
            SystemClock.sleep(100);
        }
        throw new AssertionError("Rotation timed out");
    }

    private void checkLayout() throws Exception {
        // Visibility/FrameLayout parameter changes are committed on the following frame.
        SystemClock.sleep(400); waitForIdleSync();
        String[] names={"header","topTools","tools","zoomControls","axesButton","orientationCard","bluetoothCard"};
        Rect[] rectangles=new Rect[names.length];
        for(int i=0;i<names.length;i++) {
            rectangles[i]=bounds((View)field(dashboard,names[i]));
            Rect r=rectangles[i];
            require(r.left>=0 && r.top>=0 && r.right<=dashboard.getWidth() && r.bottom<=dashboard.getHeight(),names[i]+" outside window");
            for(int j=0;j<i;j++) require(!Rect.intersects(r,rectangles[j]),names[i]+" overlaps "+names[j]);
        }
    }

    private Rect bounds(View view) {
        int[] a=new int[2],b=new int[2]; view.getLocationOnScreen(a); dashboard.getLocationOnScreen(b);
        return new Rect(a[0]-b[0],a[1]-b[1],a[0]-b[0]+view.getWidth(),a[1]-b[1]+view.getHeight());
    }

    private void screenshot(String name) throws Exception {
        SystemClock.sleep(1200);
        Bitmap image=getUiAutomation().takeScreenshot(); require(image!=null,"Screenshot unavailable");
        File directory=new File(getTargetContext().getExternalFilesDir(null),"ui-review"); directory.mkdirs();
        try(FileOutputStream output=new FileOutputStream(new File(directory,name))) { image.compress(Bitmap.CompressFormat.PNG,100,output); }
        image.recycle();
    }

    private static Object field(Object object,String name) throws Exception {
        Field field=object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static void require(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
}
