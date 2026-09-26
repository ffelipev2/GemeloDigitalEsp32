package com.gemelodigital.esp32;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/** Native recreation of the previous HTML dashboard. The SceneView remains attached in both viewport sizes. */
final class NativeDashboard extends FrameLayout {
    interface Actions { void connect(); void disconnect(); }

    private static final int WHITE = 0xfff5f8ff;
    private static final int MUTED = 0xff96a2b5;
    private final OrientationController orientation;
    final NativeSceneView scene;
    private final ScrollView scroll;
    private final LinearLayout content;
    private final FrameLayout modelCard;
    private final View modelPlace;
    private final LinearLayout modelViewport;
    private final LinearLayout wizard;
    private LinearLayout settings;
    private final LinearLayout calibrateButton, bleButton, zeroButton;
    private final TextView bleTitle, bleSubtitle, zeroTitle, calibrationBadge;
    private final TextView bluetoothBadge, bluetoothDevice, bluetoothDetail;
    private final TextView pitchValue, rollValue, yawValue;
    private final TextView wizardProgress, wizardTitle, wizardHelp, wizardError;
    private final TextView wizardNext, wizardCancel;
    private final TextView sensorBoard, directionArrow;
    private final View fullscreenButton;
    private boolean expanded;
    private Actions actions;
    private long lastTelemetryAt;
    private final int modelHeaderHeight;

    NativeDashboard(Context context, OrientationController orientation) {
        super(context);
        this.orientation = orientation;
        setClipChildren(false);
        // FrameLayout draws the same radial background below its native children.
        setWillNotDraw(false);

        modelHeaderHeight = dp(52);
        int viewportHeight = dp(220);

        scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        content = column();
        int pagePadding=dp(16);
        content.setPadding(pagePadding,dp(12),pagePadding,dp(16));
        scroll.addView(content,new ScrollView.LayoutParams(-1,-2));

        LinearLayout header=row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(header,spaced(-1,-2,0,dp(12)));
        FrameLayout brandIcon=tile(NativeIcon.Shape.CUBE,0xff39adff,0xff1b2e4b,0xff14233a,dp(42),dp(14));
        header.addView(brandIcon,new LinearLayout.LayoutParams(dp(42),dp(42)));
        LinearLayout brand=column();
        LinearLayout.LayoutParams brandParams=new LinearLayout.LayoutParams(0,-2,1);
        brandParams.leftMargin=dp(14);
        header.addView(brand,brandParams);
        brand.addView(label("Visor 3D",23,WHITE,true));
        brand.addView(label("Control y orientación",12,MUTED,false));
        View settingsButton=iconButton(NativeIcon.Shape.SETTINGS,0xffc2d0e2,dp(42));
        settingsButton.setContentDescription("Ver controles");
        header.addView(settingsButton,new LinearLayout.LayoutParams(dp(42),dp(42)));
        settingsButton.setOnClickListener(v -> settings.setVisibility(settings.getVisibility()==VISIBLE?GONE:VISIBLE));

        LinearLayout actionsRow=row();
        content.addView(actionsRow,spaced(-1,dp(78),0,dp(10)));
        calibrateButton=actionCard(NativeIcon.Shape.TARGET,"Calibrar orientación","Ajusta el sensor del dispositivo",
                0xff0870d7,0xff073a9c,0xff1281f1,0xff1982e4,0xffffffff);
        bleButton=actionCard(NativeIcon.Shape.BLUETOOTH,"Conectar Bluetooth","Vincula tu dispositivo",
                0xff009f64,0xff086a47,0xff00b66e,0xff12b77b,0xffffffff);
        bleTitle=(TextView)((LinearLayout)bleButton.getChildAt(1)).getChildAt(0);
        bleSubtitle=(TextView)((LinearLayout)bleButton.getChildAt(1)).getChildAt(1);
        LinearLayout.LayoutParams half1=new LinearLayout.LayoutParams(0,-1,1);
        half1.rightMargin=dp(5);
        actionsRow.addView(calibrateButton,half1);
        LinearLayout.LayoutParams half2=new LinearLayout.LayoutParams(0,-1,1);
        half2.leftMargin=dp(5);
        actionsRow.addView(bleButton,half2);
        calibrateButton.setOnClickListener(v -> { orientation.startWizard(); refresh(); });
        bleButton.setOnClickListener(v -> {
            if (actions==null) return;
            if (orientation.connected) actions.disconnect(); else actions.connect();
        });

        zeroButton=actionCard(NativeIcon.Shape.RESET,"Volver a cero","Restablece la orientación",
                0xff4b3118,0xff131c2a,0xff334052,0xff81501e,0xffffbb63);
        zeroTitle=(TextView)((LinearLayout)zeroButton.getChildAt(1)).getChildAt(0);
        zeroButton.setContentDescription("Coloca el sensor en la posición inicial guardada para volver a cero");
        content.addView(zeroButton,spaced(-1,dp(56),0,dp(10)));
        zeroButton.setOnClickListener(v -> { orientation.zero(); refresh(); });

        modelPlace=new View(context);
        content.addView(modelPlace,spaced(-1,modelHeaderHeight+viewportHeight,0,dp(10)));

        LinearLayout statusRow=row();
        content.addView(statusRow,new LinearLayout.LayoutParams(-1,dp(106)));
        LinearLayout orientationCard=statusCard();
        LinearLayout.LayoutParams statusLeft=new LinearLayout.LayoutParams(0,-1,1);
        statusLeft.rightMargin=dp(5);
        statusRow.addView(orientationCard,statusLeft);
        LinearLayout orientationHeading=row(); orientationHeading.setGravity(Gravity.CENTER_VERTICAL);
        orientationCard.addView(orientationHeading,new LinearLayout.LayoutParams(-1,dp(28)));
        orientationHeading.addView(tile(NativeIcon.Shape.ORIENTATION,0xff32a8ff,0xff17263d,0xff17263d,dp(24),dp(8)),
                new LinearLayout.LayoutParams(dp(24),dp(24)));
        TextView orientationName=label("Orientación",11,WHITE,true);
        orientationName.setSingleLine(true);
        orientationName.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams headingWeight=new LinearLayout.LayoutParams(0,-2,1); headingWeight.leftMargin=dp(4);
        orientationHeading.addView(orientationName,headingWeight);
        calibrationBadge=badge("Sin calibrar");
        orientationHeading.addView(calibrationBadge);
        LinearLayout angleRow=row(); angleRow.setGravity(Gravity.BOTTOM);
        LinearLayout.LayoutParams angleParams=spaced(-1,0,dp(12),0); angleParams.weight=1;
        orientationCard.addView(angleRow,angleParams);
        pitchValue=angleColumn(angleRow,"Pitch");
        rollValue=angleColumn(angleRow,"Roll");
        yawValue=angleColumn(angleRow,"Yaw");

        LinearLayout bluetoothCard=statusCard();
        LinearLayout.LayoutParams statusRight=new LinearLayout.LayoutParams(0,-1,1); statusRight.leftMargin=dp(5);
        statusRow.addView(bluetoothCard,statusRight);
        LinearLayout bluetoothHeading=row(); bluetoothHeading.setGravity(Gravity.CENTER_VERTICAL);
        bluetoothCard.addView(bluetoothHeading,new LinearLayout.LayoutParams(-1,dp(28)));
        bluetoothHeading.addView(tile(NativeIcon.Shape.BLUETOOTH,0xff32a8ff,0xff17263d,0xff17263d,dp(24),dp(8)),
                new LinearLayout.LayoutParams(dp(24),dp(24)));
        TextView bluetoothName=label("Bluetooth",11,WHITE,true);
        bluetoothName.setSingleLine(true);
        bluetoothName.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams btWeight=new LinearLayout.LayoutParams(0,-2,1); btWeight.leftMargin=dp(4);
        bluetoothHeading.addView(bluetoothName,btWeight);
        bluetoothBadge=badge("Desconectado");
        bluetoothHeading.addView(bluetoothBadge);
        bluetoothDevice=label("—",17,WHITE,true); bluetoothDevice.setGravity(Gravity.CENTER);
        bluetoothCard.addView(bluetoothDevice,spaced(-1,-2,dp(10),dp(6)));
        bluetoothDetail=label("Ningún dispositivo conectado",11,MUTED,false);
        bluetoothDetail.setGravity(Gravity.CENTER);
        bluetoothCard.addView(bluetoothDetail);

        modelCard=new FrameLayout(context);
        modelCard.setBackground(shape(0xff101a2a,0xff0b1320,0xff263852,dp(22)));
        modelCard.setClipToOutline(true);
        modelCard.setElevation(dp(9));
        LinearLayout modelContent=column();
        modelCard.addView(modelContent,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout modelHeader=row(); modelHeader.setGravity(Gravity.CENTER_VERTICAL);
        modelHeader.setPadding(dp(12),dp(4),dp(12),0);
        modelContent.addView(modelHeader,new LinearLayout.LayoutParams(-1,modelHeaderHeight));
        modelHeader.addView(tile(NativeIcon.Shape.CUBE,0xff39afff,0xff1b2b43,0xff1b2b43,dp(34),dp(10)),
                new LinearLayout.LayoutParams(dp(34),dp(34)));
        LinearLayout modelTitles=column();
        LinearLayout.LayoutParams modelWeight=new LinearLayout.LayoutParams(0,-2,1); modelWeight.leftMargin=dp(12);
        modelHeader.addView(modelTitles,modelWeight);
        modelTitles.addView(label("Vista 3D",16,WHITE,true));
        modelTitles.addView(label("Modelo en tiempo real",11,0xff93a3bb,false));
        fullscreenButton=iconButton(NativeIcon.Shape.EXPAND,0xffc2d0e2,dp(40));
        fullscreenButton.setContentDescription("Ampliar vista 3D");
        modelHeader.addView(fullscreenButton,new LinearLayout.LayoutParams(dp(40),dp(40)));
        fullscreenButton.setOnClickListener(v -> setExpanded(!expanded));

        modelViewport=column();
        modelViewport.setBackgroundColor(0xff0d1624);
        LinearLayout.LayoutParams viewportParams=new LinearLayout.LayoutParams(-1,0,1);
        modelContent.addView(modelViewport,viewportParams);
        FrameLayout sceneContainer=new FrameLayout(context);
        modelViewport.addView(sceneContainer,new LinearLayout.LayoutParams(-1,-1));
        scene=new NativeSceneView(context);
        sceneContainer.addView(scene,new FrameLayout.LayoutParams(-1,-1));
        addView(modelCard,new FrameLayout.LayoutParams(-1,modelHeaderHeight+viewportHeight));

        settings=column();
        settings.setPadding(dp(18),dp(18),dp(18),dp(18));
        settings.setBackground(shape(0xff142033,0xff142033,0xff30445e,dp(17)));
        settings.setElevation(dp(18));
        settings.addView(label("Controles",18,WHITE,true));
        settings.addView(label("Calibra el montaje una vez. Después, coloca el sensor en su posición inicial y pulsa Volver a cero cuando lo necesites.",14,0xffafbdd0,false),
                spaced(-1,-2,dp(10),dp(16)));
        TextView close=smallButton("Cerrar",0xff2466aa); settings.addView(close);
        close.setOnClickListener(v -> settings.setVisibility(GONE));
        FrameLayout.LayoutParams settingsParams=new FrameLayout.LayoutParams(
                Math.min(dp(320),getResources().getDisplayMetrics().widthPixels-dp(32)),
                -2,Gravity.TOP|Gravity.RIGHT);
        settingsParams.topMargin=dp(86); settingsParams.rightMargin=dp(16);
        addView(settings,settingsParams); settings.setVisibility(GONE);

        wizard=column();
        wizard.setPadding(dp(20),dp(20),dp(20),dp(20));
        wizard.setGravity(Gravity.CENTER_HORIZONTAL);
        wizard.setBackground(shape(0xf5101d2f,0xf5101d2f,0xff365778,dp(20)));
        wizard.setElevation(dp(24));
        wizardProgress=label("Paso 1 de 3",14,0xff9fc6e9,true);
        wizardProgress.setGravity(Gravity.CENTER); wizard.addView(wizardProgress);
        FrameLayout illustration=new FrameLayout(context);
        wizard.addView(illustration,new LinearLayout.LayoutParams(-1,dp(112)));
        sensorBoard=label("FRENTE ↑\nIMU",12,WHITE,true);
        sensorBoard.setGravity(Gravity.CENTER);
        sensorBoard.setBackground(shape(0xff254968,0xff254968,0xff82b8e9,dp(10)));
        FrameLayout.LayoutParams boardParams=new FrameLayout.LayoutParams(dp(90),dp(70),Gravity.TOP|Gravity.CENTER_HORIZONTAL);
        boardParams.topMargin=dp(18);
        illustration.addView(sensorBoard,boardParams);
        directionArrow=label("",35,0xff8fd28f,true);
        FrameLayout.LayoutParams arrowParams=new FrameLayout.LayoutParams(dp(45),dp(55),Gravity.TOP|Gravity.CENTER_HORIZONTAL);
        arrowParams.leftMargin=dp(135); arrowParams.topMargin=dp(35);
        illustration.addView(directionArrow,arrowParams);
        wizardTitle=label("",21,WHITE,true); wizardTitle.setGravity(Gravity.CENTER);
        wizard.addView(wizardTitle,spaced(-1,-2,dp(4),dp(9)));
        wizardHelp=label("",15,0xffd1dce8,false); wizardHelp.setGravity(Gravity.CENTER);
        wizard.addView(wizardHelp,spaced(-1,-2,0,dp(14)));
        wizardError=label("",14,0xffffb4a9,false); wizardError.setGravity(Gravity.CENTER);
        wizard.addView(wizardError,spaced(-1,-2,0,dp(10)));
        LinearLayout wizardActions=row(); wizardActions.setGravity(Gravity.CENTER);
        wizard.addView(wizardActions);
        wizardCancel=smallButton("Cancelar",0xff35475b);
        wizardNext=smallButton("Continuar",0xff28a745);
        LinearLayout.LayoutParams cancelParams=new LinearLayout.LayoutParams(dp(110),dp(44)); cancelParams.rightMargin=dp(10);
        wizardActions.addView(wizardCancel,cancelParams);
        wizardActions.addView(wizardNext,new LinearLayout.LayoutParams(dp(110),dp(44)));
        wizardCancel.setOnClickListener(v -> { orientation.cancelWizard(); refresh(); });
        wizardNext.setOnClickListener(v -> { orientation.continueWizard(); refresh(); });
        FrameLayout.LayoutParams wizardParams=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
        wizardParams.leftMargin=dp(14); wizardParams.rightMargin=dp(14); wizardParams.bottomMargin=dp(18);
        addView(wizard,wizardParams);

        modelPlace.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> post(this::positionModelCard));
        scroll.setOnScrollChangeListener((View.OnScrollChangeListener)(v,sx,sy,osx,osy) -> positionModelCard());
        addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> post(() -> {
            fitViewportToScreen();
            positionModelCard();
        }));
        refresh();
    }

    void setActions(Actions actions) { this.actions=actions; }

    void refresh() {
        long now=SystemClock.uptimeMillis();
        boolean busy=orientation.statusText.startsWith("Buscando") || orientation.statusText.startsWith("Conectando")
                || orientation.statusText.startsWith("Leyendo");
        bleButton.setEnabled(orientation.connected || !busy);
        if (orientation.connected) {
            bleTitle.setText("Desconectar Bluetooth");
            bleSubtitle.setText(orientation.lastSampleTime==0?"Esperando sensor":orientation.stale?"Sin datos del sensor":"Dispositivo conectado");
            bluetoothBadge.setText(orientation.stale?"Sin datos":"Conectado");
            badgeState(bluetoothBadge,orientation.stale?0xff52351a:0xff064c39,orientation.stale?0xffffc174:0xff28dc92);
            bluetoothDevice.setText("CuboneESP32");
            bluetoothDetail.setText(orientation.lastSampleTime!=0 && !orientation.stale?"Orientación en tiempo real":orientation.statusText);
        } else {
            bleTitle.setText(busy?"Conectando…":orientation.statusText.startsWith("Activa Bluetooth")?"Activar Bluetooth":
                    orientation.statusText.startsWith("Permiso")?"Permiso BLE · Reintentar":
                    orientation.statusText.equals("Bluetooth desconectado") || orientation.statusText.equals("Pulsa Conectar Bluetooth")?
                    "Conectar Bluetooth":"Reintentar Bluetooth");
            bleSubtitle.setText(busy?"Buscando tu dispositivo":"Vincula tu dispositivo");
            bluetoothBadge.setText(busy?"Conectando":"Desconectado");
            badgeState(bluetoothBadge,busy?0xff22395d:0xff273344,busy?0xff79b9ff:0xffabb6c6);
            bluetoothDevice.setText("—");
            bluetoothDetail.setText(orientation.statusText.equals("Bluetooth desconectado") || orientation.statusText.equals("Pulsa Conectar Bluetooth")?
                    "Ningún dispositivo conectado":orientation.statusText);
        }
        calibrationBadge.setText(orientation.calibration==null?"Sin calibrar":"Calibrada");
        badgeState(calibrationBadge,orientation.calibration==null?0xff52351a:0xff064c39,
                orientation.calibration==null?0xffffc174:0xff28dc92);
        calibrateButton.setEnabled(orientation.wizardStep<0);
        zeroButton.setEnabled(orientation.calibration!=null && orientation.wizardStep<0 && orientation.hasFreshSample());
        zeroTitle.setText(orientation.zeroFeedback);
        int step=orientation.wizardStep;
        wizard.setVisibility(step<0?GONE:VISIBLE);
        if (step>=0) {
            String[] titles={"Coloca el sensor en posición inicial","Inclina el sensor hacia adelante",
                    "Inclina el sensor hacia la derecha","Calibración completada"};
            String[] helps={"Conecta Bluetooth. Mantén el dispositivo quieto en la postura que quieres ver como neutra. Elige cuál es su frente.",
                    "Sin cambiar el frente elegido, baja la parte delantera entre 20° y 85° y mantén la posición.",
                    "Vuelve a la posición inicial. Después baja el lado derecho entre 20° y 85° y mantén la posición.",
                    "El modelo ya responde a la orientación del dispositivo. Puedes repetir la calibración si cambias el montaje del IMU."};
            wizardProgress.setText(step==3?"3 de 3 pasos":"Paso "+(step+1)+" de 3");
            wizardTitle.setText(titles[step]); wizardHelp.setText(helps[step]);
            wizardError.setText(orientation.wizardError==null?"":orientation.wizardError);
            wizardError.setVisibility(orientation.wizardError==null?GONE:VISIBLE);
            wizardNext.setText(step==3?"Listo":"Continuar");
            wizardNext.setEnabled(step==3 || orientation.hasFreshSample());
            wizardCancel.setText(orientation.calibration==null?"Reiniciar":"Cancelar");
            wizardCancel.setVisibility(step==3 || (orientation.calibration==null && step==0)?GONE:VISIBLE);
            sensorBoard.setRotationX(step==1?-42:0); sensorBoard.setRotation(step==2?28:0);
            directionArrow.setText(step==1?"↑":step==2?"→":step==3?"✓":"");
        }
    }

    void updateAngles(OrientationCore.Quat q, long now) {
        if (now-lastTelemetryAt<100) return;
        double[] e=q.eulerYXZ();
        pitchValue.setText(angle(e[0])); rollValue.setText(angle(-e[1])); yawValue.setText(angle(e[2]));
        lastTelemetryAt=now;
    }

    boolean handleBack() {
        if (settings.getVisibility()==VISIBLE) { settings.setVisibility(GONE); return true; }
        if (expanded) { setExpanded(false); return true; }
        return false;
    }

    private void setExpanded(boolean value) {
        expanded=value;
        scroll.setVisibility(value?INVISIBLE:VISIBLE);
        modelCard.setBackground(shape(0xff101a2a,0xff0b1320,0xff263852,value?0:dp(22)));
        modelCard.setClipToOutline(!value);
        fullscreenButton.setContentDescription(value?"Reducir vista 3D":"Ampliar vista 3D");
        positionModelCard();
    }

    private void positionModelCard() {
        if (expanded) {
            FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(getWidth(),getHeight());
            modelCard.setLayoutParams(p);
            modelCard.setElevation(dp(30));
            return;
        }
        int[] place=new int[2], root=new int[2];
        modelPlace.getLocationOnScreen(place); getLocationOnScreen(root);
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(modelPlace.getWidth(),modelPlace.getHeight());
        p.leftMargin=place[0]-root[0]; p.topMargin=place[1]-root[1];
        modelCard.setLayoutParams(p);
        modelCard.setElevation(dp(9));
    }

    private void fitViewportToScreen() {
        if (getWidth()==0 || getHeight()==0) return;
        // Reserve the measured controls and status cards before sizing the 3D viewport.
        // Use the actual app height, which already excludes the system bars.
        int fixedHeight=content.getPaddingTop()+content.getPaddingBottom();
        for (int i=0;i<content.getChildCount();i++) {
            View child=content.getChildAt(i);
            LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)child.getLayoutParams();
            fixedHeight+=p.topMargin+p.bottomMargin;
            if (child!=modelPlace) fixedHeight+=child.getMeasuredHeight();
        }
        // The model gets all remaining height; the status cards stay above the bottom padding.
        int height=modelHeaderHeight+Math.max(dp(120),getHeight()-fixedHeight-modelHeaderHeight);
        ViewGroup.LayoutParams p=modelPlace.getLayoutParams();
        if (p.height!=height) {
            p.height=height;
            modelPlace.setLayoutParams(p);
        }
    }

    private String angle(double radians) {
        double degrees=radians*180/Math.PI;
        return String.format(Locale.US,"%.1f°",Math.abs(degrees)<.05?0:degrees);
    }

    @Override protected void onDraw(Canvas canvas) {
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new RadialGradient(getWidth()/2f,0,Math.max(getWidth(),getHeight())*.8f,
                new int[]{0xff111b2c,0xff090f1a,0xff050a12},null,Shader.TileMode.CLAMP));
        canvas.drawRect(0,0,getWidth(),getHeight(),paint);
        super.onDraw(canvas);
    }

    private int dp(float value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout v=new LinearLayout(getContext()); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v=new LinearLayout(getContext()); v.setOrientation(LinearLayout.HORIZONTAL); return v; }
    private LinearLayout.LayoutParams spaced(int w,int h,int top,int bottom) {
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w,h); p.topMargin=top; p.bottomMargin=bottom; return p;
    }
    private TextView label(String text,float size,int color,boolean bold) {
        TextView v=new TextView(getContext()); v.setText(text); v.setTextColor(color); v.setTextSize(size);
        if (bold) v.setTypeface(null,1); v.setIncludeFontPadding(false); return v;
    }
    private GradientDrawable shape(int top,int bottom,int stroke,int radius) {
        GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{top,bottom});
        d.setCornerRadius(radius); d.setStroke(dp(1),stroke); return d;
    }
    private FrameLayout tile(NativeIcon.Shape icon,int iconColor,int top,int bottom,int size,int radius) {
        FrameLayout frame=new FrameLayout(getContext()); frame.setBackground(shape(top,bottom,top,radius));
        int inset=dp(size>=48?10:4);
        NativeIcon image=new NativeIcon(getContext(),icon,iconColor);
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(size-inset,size-inset,Gravity.CENTER);
        frame.addView(image,p); return frame;
    }
    private View iconButton(NativeIcon.Shape icon,int color,int size) {
        FrameLayout frame=tile(icon,color,0xff172235,0xff172235,size,dp(15));
        frame.setBackground(shape(0xff172235,0xff172235,0xff243146,dp(15))); return frame;
    }
    private LinearLayout actionCard(NativeIcon.Shape icon,String title,String subtitle,
                                    int top,int bottom,int border,int tileColor,int iconColor) {
        LinearLayout card=row(); card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(10),dp(8),dp(8),dp(8));
        card.setBackground(shape(top,bottom,border,dp(17)));
        card.setElevation(dp(6));
        card.addView(tile(icon,iconColor,tileColor,tileColor,dp(32),dp(10)),new LinearLayout.LayoutParams(dp(32),dp(32)));
        // Keep the label views attached so status updates never rebuild a card.
        TextView titleView=label(title,13,WHITE,true);
        LinearLayout info=column();
        info.addView(titleView);
        info.addView(label(subtitle,10,0xffc4d4e9,false),spaced(-1,-2,dp(4),0));
        LinearLayout.LayoutParams copy=new LinearLayout.LayoutParams(0,-2,1); copy.leftMargin=dp(7);
        card.addView(info,copy);
        return card;
    }
    private LinearLayout statusCard() {
        LinearLayout card=column(); card.setPadding(dp(8),dp(10),dp(8),dp(10));
        card.setBackground(shape(0xff101a2a,0xff0b1320,0xff263852,dp(17)));
        card.setElevation(dp(6)); return card;
    }
    private TextView badge(String text) {
        TextView v=label(text,9,0xffabb6c6,false);
        v.setSingleLine(true);
        v.setPadding(dp(4),dp(4),dp(4),dp(4)); v.setGravity(Gravity.CENTER);
        v.setBackground(shape(0xff273344,0xff273344,0xff273344,dp(30))); return v;
    }
    private void badgeState(TextView badge,int bg,int fg) {
        int state=31*bg+fg;
        if (badge.getTag() instanceof Integer && (Integer)badge.getTag()==state) return;
        badge.setTag(state);
        badge.setTextColor(fg); badge.setBackground(shape(bg,bg,bg,dp(30)));
    }
    private TextView angleColumn(LinearLayout parent,String name) {
        LinearLayout col=column(); col.setGravity(Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
        parent.addView(col,new LinearLayout.LayoutParams(0,-1,1));
        TextView caption=label(name,11,0xff9ca9bc,false); caption.setGravity(Gravity.CENTER);
        col.addView(caption);
        TextView value=label("0.0°",17,WHITE,false); value.setGravity(Gravity.CENTER);
        col.addView(value,spaced(-1,-2,dp(6),0));
        return value;
    }
    private TextView smallButton(String text,int color) {
        TextView button=label(text,16,WHITE,true); button.setGravity(Gravity.CENTER);
        button.setPadding(dp(15),dp(10),dp(15),dp(10));
        button.setBackground(shape(color,color,color,dp(8))); return button;
    }
}
