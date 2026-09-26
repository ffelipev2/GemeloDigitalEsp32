package com.gemelodigital.esp32;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/** One persistent scene with an overlay that adapts to portrait, landscape and window resizing. */
final class NativeDashboard extends FrameLayout {
    interface Actions { void connect(); void disconnect(); }

    private static final int WHITE=0xfff5f8ff, MUTED=0xff96a8bf, BLUE=0xff2ab4ff;
    private final OrientationController orientation;
    final NativeSceneView scene;
    private final LinearLayout header, topTools, tools, zoomControls;
    private final LinearLayout orientationCard, bluetoothCard, wizard;
    private final LinearLayout wizardActions;
    private final FrameLayout wizardIllustration;
    private final LinearLayout settings;
    private final ScrollView settingsScroll;
    private final View modalScrim;
    private final LinearLayout calibrateButton, zeroButton, viewButton;
    private final View settingsButton, fullscreenButton, axesButton;
    private final TextView bleButton, calibrationBadge, bluetoothBadge, bluetoothDevice, bluetoothDetail;
    private final TextView settingsClose;
    private final TextView pitchValue, rollValue, yawValue;
    private final TextView wizardProgress, wizardTitle, wizardHelp, wizardError;
    private final TextView wizardNext, wizardCancel, wizardConnect, sensorBoard, directionArrow;
    private boolean expanded, axesVisible;
    private Actions actions;
    private long lastTelemetryAt;

    NativeDashboard(Context context, OrientationController orientation) {
        super(context);
        this.orientation=orientation;
        setBackgroundColor(0xff07111f);

        // Keep the SurfaceView attached and load the model once, including across rotations.
        scene=new NativeSceneView(context);
        addView(scene,new FrameLayout.LayoutParams(-1,-1));

        header=row(); header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(tile(NativeIcon.Shape.CUBE,BLUE,0xff123c5c,0xff082239,dp(44),dp(14)),
                new LinearLayout.LayoutParams(dp(44),dp(44)));
        LinearLayout brand=column();
        LinearLayout.LayoutParams brandParams=new LinearLayout.LayoutParams(0,-2,1); brandParams.leftMargin=dp(12);
        header.addView(brand,brandParams);
        TextView brandTitle=label("Visor 3D",22,WHITE,true); singleLine(brandTitle);
        TextView brandSubtitle=label("Control y orientación",12,MUTED,false); singleLine(brandSubtitle);
        brand.addView(brandTitle); brand.addView(brandSubtitle,spaced(-1,-2,dp(3),0));
        addView(header);

        topTools=row(); topTools.setGravity(Gravity.CENTER_VERTICAL);
        settingsButton=roundButton(NativeIcon.Shape.SETTINGS,0xffbfcee0,dp(44));
        settingsButton.setContentDescription("Ver controles y conexión Bluetooth");
        topTools.addView(settingsButton,new LinearLayout.LayoutParams(dp(44),dp(44)));
        fullscreenButton=roundButton(NativeIcon.Shape.EXPAND,0xffc7d3e5,dp(44));
        fullscreenButton.setContentDescription("Ampliar vista 3D");
        LinearLayout.LayoutParams expandParams=new LinearLayout.LayoutParams(dp(44),dp(44)); expandParams.leftMargin=dp(10);
        topTools.addView(fullscreenButton,expandParams);
        fullscreenButton.setOnClickListener(v -> setExpanded(!expanded));
        addView(topTools);

        tools=column();
        calibrateButton=toolCard(NativeIcon.Shape.TARGET,"Calibrar",0xffffffff,0xff0b7eea,0xff073ba5,0xff1684f5);
        calibrateButton.setContentDescription("Calibrar orientación");
        zeroButton=toolCard(NativeIcon.Shape.RESET,"Cero",0xfff9a34b,0xff182436,0xff0c1726,0xff493019);
        zeroButton.setContentDescription("Volver a cero según la posición inicial guardada");
        viewButton=toolCard(NativeIcon.Shape.CUBE,"Vista 3D",BLUE,0xff182436,0xff0c1726,0xff102a40);
        viewButton.setContentDescription("Abrir vista 3D en pantalla completa");
        tools.addView(calibrateButton); tools.addView(zeroButton); tools.addView(viewButton);
        addView(tools);
        zeroButton.setOnClickListener(v -> {
            orientation.zero(); refresh();
            Toast.makeText(context,orientation.zeroFeedback,Toast.LENGTH_SHORT).show();
        });
        viewButton.setOnClickListener(v -> setExpanded(true));

        zoomControls=column();
        zoomControls.setBackground(shape(0xee172639,0xee0b1525,0xff243d56,dp(24)));
        zoomControls.setPadding(dp(4),dp(4),dp(4),dp(4));
        TextView zoomIn=label("+",25,WHITE,false); zoomIn.setGravity(Gravity.CENTER);
        zoomIn.setContentDescription("Acercar modelo"); clickable(zoomIn,dp(20));
        TextView zoomOut=label("−",25,WHITE,false); zoomOut.setGravity(Gravity.CENTER);
        zoomOut.setContentDescription("Alejar modelo"); clickable(zoomOut,dp(20));
        zoomControls.addView(zoomIn,new LinearLayout.LayoutParams(-1,0,1));
        zoomControls.addView(zoomOut,new LinearLayout.LayoutParams(-1,0,1));
        zoomIn.setOnClickListener(v -> scene.zoomBy(0.12f));
        zoomOut.setOnClickListener(v -> scene.zoomBy(-0.12f));
        addView(zoomControls);

        axesButton=roundButton(NativeIcon.Shape.AXES,0xffc6d7e9,dp(48));
        axesButton.setContentDescription("Mostrar ejes del modelo");
        axesButton.setOnClickListener(v -> {
            axesVisible=!axesVisible; scene.setAxesVisible(axesVisible);
            axesButton.setSelected(axesVisible);
            axesButton.setContentDescription(axesVisible?"Ocultar ejes del modelo":"Mostrar ejes del modelo");
            axesButton.setBackground(shape(axesVisible?0xff153b58:0xff172638,0xff0b1525,
                    axesVisible?0xff2385b5:0xff243d56,dp(24)));
        });
        addView(axesButton);

        orientationCard=statusCard();
        LinearLayout orientationHeading=statusHeading(orientationCard,NativeIcon.Shape.TARGET,"Orientación",BLUE);
        calibrationBadge=badge("Sin calibrar"); orientationHeading.addView(calibrationBadge);
        LinearLayout angleRow=row(); angleRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams angleParams=spaced(-1,0,dp(12),0); angleParams.weight=1;
        orientationCard.addView(angleRow,angleParams);
        pitchValue=angleColumn(angleRow,"Pitch"); rollValue=angleColumn(angleRow,"Roll"); yawValue=angleColumn(angleRow,"Yaw");
        addView(orientationCard);

        bluetoothCard=statusCard();
        LinearLayout bluetoothHeading=statusHeading(bluetoothCard,NativeIcon.Shape.BLUETOOTH,"Bluetooth",0xff00b9d7);
        bluetoothBadge=badge("Desconectado"); bluetoothHeading.addView(bluetoothBadge);
        bluetoothDevice=label("—",18,WHITE,true); bluetoothDevice.setGravity(Gravity.CENTER); singleLine(bluetoothDevice);
        bluetoothCard.addView(bluetoothDevice,spaced(-1,-2,dp(10),dp(6)));
        bluetoothDetail=label("Ningún dispositivo conectado",10,MUTED,false); bluetoothDetail.setGravity(Gravity.CENTER);
        bluetoothDetail.setMaxLines(2); bluetoothDetail.setEllipsize(TextUtils.TruncateAt.END);
        bluetoothCard.addView(bluetoothDetail,new LinearLayout.LayoutParams(-1,0,1));
        clickable(bluetoothCard,dp(18)); bluetoothCard.setOnClickListener(v -> requestBleAction());
        addView(bluetoothCard);

        modalScrim=new View(context); modalScrim.setBackgroundColor(0x99030910);
        addView(modalScrim,new FrameLayout.LayoutParams(-1,-1)); modalScrim.setVisibility(GONE);

        settings=column(); settings.setPadding(dp(18),dp(18),dp(18),dp(18));
        settings.setBackground(shape(0xff112339,0xff0b1728,0xff315477,dp(18)));
        settings.addView(label("Controles",18,WHITE,true));
        // Only the help text scrolls; both actions stay inside the panel in either orientation.
        settingsScroll=new ScrollView(context); settingsScroll.setFillViewport(false);
        settings.addView(settingsScroll,spaced(-1,0,dp(12),0));
        LinearLayout.LayoutParams helpParams=(LinearLayout.LayoutParams)settingsScroll.getLayoutParams(); helpParams.weight=1;
        settingsScroll.setLayoutParams(helpParams);
        LinearLayout settingsBody=column(); settingsScroll.addView(settingsBody,new ScrollView.LayoutParams(-1,-2));
        settingsBody.addView(label("Toca la tarjeta Bluetooth para conectar o desconectar el ESP32. El modelo se mueve al recibir datos, incluso sin calibrar. Calibrar adapta los ejes al montaje; Cero usa la posición inicial guardada.",13,0xffb7c8dc,false),
                spaced(-1,-2,0,dp(12)));
        settingsBody.addView(label("+ y − acercan o alejan el modelo y guardan el tamaño para la próxima apertura. Vista 3D abre el modelo en pantalla completa. El botón de ejes muestra la referencia espacial.",13,0xffb7c8dc,false),
                spaced(-1,-2,0,0));
        bleButton=smallButton("Conectar Bluetooth",0xff087f79); settings.addView(bleButton,spaced(-1,dp(44),dp(12),dp(10)));
        bleButton.setOnClickListener(v -> requestBleAction());
        settingsClose=smallButton("Cerrar",0xff23456a); settings.addView(settingsClose,new LinearLayout.LayoutParams(-1,dp(44)));
        settingsClose.setOnClickListener(v -> { settings.setVisibility(GONE); refreshModalScrim(); });
        addView(settings); settings.setVisibility(GONE);
        settingsButton.setOnClickListener(v -> {
            settings.setVisibility(settings.getVisibility()==VISIBLE?GONE:VISIBLE); refreshModalScrim();
        });

        wizard=column(); wizard.setPadding(dp(18),dp(16),dp(18),dp(16));
        wizard.setBackground(shape(0xff112339,0xff0b1728,0xff315477,dp(20)));
        wizardProgress=label("Paso 1 de 3",13,0xff92c7ed,true); wizardProgress.setGravity(Gravity.CENTER);
        wizard.addView(wizardProgress,spaced(-1,-2,0,dp(10)));
        // Keep the current instruction and exit/continue actions visible in short landscape windows.
        wizardTitle=label("",20,WHITE,true); wizardTitle.setGravity(Gravity.CENTER);
        wizard.addView(wizardTitle,spaced(-1,-2,0,dp(10)));
        ScrollView wizardScroll=new ScrollView(context); wizardScroll.setFillViewport(false); wizardScroll.setVerticalScrollBarEnabled(false);
        wizard.addView(wizardScroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout wizardBody=column(); wizardScroll.addView(wizardBody,new ScrollView.LayoutParams(-1,-2));
        wizardHelp=label("",14,0xffcad9e9,false); wizardHelp.setGravity(Gravity.CENTER);
        wizardBody.addView(wizardHelp,spaced(-1,-2,0,dp(12)));
        wizardIllustration=new FrameLayout(context); wizardBody.addView(wizardIllustration,new LinearLayout.LayoutParams(-1,dp(100)));
        sensorBoard=label("FRENTE ↑\nIMU",12,WHITE,true); sensorBoard.setGravity(Gravity.CENTER);
        sensorBoard.setBackground(shape(0xff254968,0xff254968,0xff82b8e9,dp(10)));
        FrameLayout.LayoutParams boardParams=new FrameLayout.LayoutParams(dp(90),dp(70),Gravity.TOP|Gravity.CENTER_HORIZONTAL); boardParams.topMargin=dp(12);
        wizardIllustration.addView(sensorBoard,boardParams);
        directionArrow=label("",32,0xff8fd28f,true);
        FrameLayout.LayoutParams arrowParams=new FrameLayout.LayoutParams(dp(45),dp(55),Gravity.TOP|Gravity.CENTER_HORIZONTAL);
        arrowParams.leftMargin=dp(128); arrowParams.topMargin=dp(28); wizardIllustration.addView(directionArrow,arrowParams);
        wizardConnect=smallButton("Conectar Bluetooth",0xff087f79);
        wizardConnect.setOnClickListener(v -> requestBleAction()); wizardBody.addView(wizardConnect,spaced(-1,dp(44),0,dp(12)));
        wizardError=label("",13,0xffffb4a9,false); wizardError.setGravity(Gravity.CENTER);
        wizardBody.addView(wizardError,spaced(-1,-2,0,dp(10)));
        wizardActions=row(); wizardActions.setGravity(Gravity.CENTER);
        wizard.addView(wizardActions,spaced(-1,dp(44),dp(12),0));
        wizardCancel=smallButton("Cancelar",0xff304760); wizardNext=smallButton("Continuar",0xff168952);
        LinearLayout.LayoutParams cancelParams=new LinearLayout.LayoutParams(0,-1,1); cancelParams.rightMargin=dp(8);
        wizardActions.addView(wizardCancel,cancelParams); wizardActions.addView(wizardNext,new LinearLayout.LayoutParams(0,-1,1));
        wizardCancel.setOnClickListener(v -> { orientation.cancelWizard(); refresh(); });
        wizardNext.setOnClickListener(v -> { orientation.continueWizard(); refresh(); });
        addView(wizard);

        calibrateButton.setOnClickListener(v -> {
            setExpanded(false); settings.setVisibility(GONE); orientation.startWizard(); refresh();
        });
        modalScrim.setOnClickListener(v -> { settings.setVisibility(GONE); refreshModalScrim(); });

        addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            if (r-l!=or-ol || b-t!=ob-ot) post(this::layoutChrome);
        });
        refresh();
    }

    void setActions(Actions actions) { this.actions=actions; }
    void onWindowChanged() { post(this::layoutChrome); }

    private void requestBleAction() {
        if (actions==null) return;
        if (orientation.connected) actions.disconnect(); else actions.connect();
    }

    void refresh() {
        boolean busy=orientation.statusText.startsWith("Buscando") || orientation.statusText.startsWith("Conectando")
                || orientation.statusText.startsWith("Leyendo");
        if (orientation.connected) {
            text(bleButton,"Desconectar Bluetooth");
            text(bluetoothBadge,orientation.stale?"Sin datos":"Conectado");
            badgeState(bluetoothBadge,orientation.stale?0xff52351a:0xff064c39,orientation.stale?0xffffc174:0xff28dc92);
            text(bluetoothDevice,"CuboneESP32");
            text(bluetoothDetail,orientation.lastSampleTime!=0 && !orientation.stale?"Orientación en tiempo real":orientation.statusText);
            bluetoothCard.setContentDescription("Bluetooth conectado. Toca para desconectar el ESP32");
        } else {
            String connectionTitle=busy?"Cancelar conexión":orientation.statusText.startsWith("Activa Bluetooth")?"Activar Bluetooth":
                    orientation.statusText.startsWith("Permiso")?"Permiso BLE · Reintentar":"Conectar Bluetooth";
            text(bleButton,connectionTitle); text(wizardConnect,connectionTitle);
            text(bluetoothBadge,busy?"Conectando":"Desconectado");
            badgeState(bluetoothBadge,busy?0xff22395d:0xff202e43,busy?0xff79b9ff:0xffabb6c6);
            text(bluetoothDevice,"—");
            text(bluetoothDetail,orientation.statusText.equals("Bluetooth desconectado") || orientation.statusText.equals("Pulsa Conectar Bluetooth")?
                    "Ningún dispositivo conectado":orientation.statusText);
            bluetoothCard.setContentDescription(busy?"Conectando. Toca para cancelar y volver a intentar":"Bluetooth desconectado. Toca para conectar el ESP32");
        }
        text(calibrationBadge,orientation.calibration==null?"Sin calibrar":"Calibrada");
        badgeState(calibrationBadge,orientation.calibration==null?0xff52351a:0xff064c39,orientation.calibration==null?0xffffc174:0xff28dc92);
        calibrateButton.setEnabled(orientation.wizardStep<0);
        zeroButton.setEnabled(orientation.calibration!=null && orientation.wizardStep<0 && orientation.hasFreshSample());
        int step=orientation.wizardStep;
        if (step>=0) {
            String[] titles={"Coloca el sensor en posición inicial","Inclina el sensor hacia adelante","Inclina el sensor hacia la derecha","Calibración completada"};
            String[] helps={"Conecta Bluetooth. Mantén el dispositivo quieto en la postura que quieres ver como neutra. Elige cuál es su frente.",
                    "Sin cambiar el frente elegido, baja la parte delantera entre 20° y 85° y mantén la posición.",
                    "Vuelve a la posición inicial. Después baja el lado derecho entre 20° y 85° y mantén la posición.",
                    "El modelo ya responde a la orientación del dispositivo. Puedes repetir la calibración si cambias el montaje del IMU."};
            text(wizardProgress,step==3?"3 de 3 pasos":"Paso "+(step+1)+" de 3");
            text(wizardTitle,titles[step]); text(wizardHelp,helps[step]);
            text(wizardError,orientation.wizardError==null?"":orientation.wizardError);
            wizardError.setVisibility(orientation.wizardError==null?GONE:VISIBLE);
            text(wizardNext,step==3?"Listo":"Continuar"); wizardNext.setEnabled(step==3 || orientation.hasFreshSample());
            text(wizardCancel,"Cancelar");
            wizardCancel.setVisibility(step==3?GONE:VISIBLE);
            wizardConnect.setVisibility(orientation.connected || step==3?GONE:VISIBLE);
            sensorBoard.setRotationX(step==1?-42:0); sensorBoard.setRotation(step==2?28:0);
            text(directionArrow,step==1?"↑":step==2?"→":step==3?"✓":"");
        }
        refreshModalScrim();
    }

    void updateAngles(OrientationCore.Quat q,long now) {
        if (now-lastTelemetryAt<100) return;
        double[] e=q.eulerYXZ();
        text(pitchValue,angle(e[0])); text(rollValue,angle(-e[1])); text(yawValue,angle(e[2])); lastTelemetryAt=now;
    }

    boolean handleBack() {
        if (settings.getVisibility()==VISIBLE) { settings.setVisibility(GONE); refreshModalScrim(); return true; }
        if (expanded) { setExpanded(false); return true; }
        if (orientation.wizardStep>=0) { orientation.cancelWizard(); refresh(); return true; }
        return false;
    }

    private void refreshModalScrim() {
        boolean settingsOpen=settings.getVisibility()==VISIBLE;
        modalScrim.setVisibility(settingsOpen?VISIBLE:GONE);
        // The calibration panel never intercepts input to the surrounding viewer controls.
        // Temporarily hide it in settings/fullscreen, retaining the selected step and samples.
        wizard.setVisibility(orientation.wizardStep>=0 && !expanded && !settingsOpen?VISIBLE:GONE);
    }

    private void setExpanded(boolean value) {
        expanded=value;
        header.setVisibility(value?GONE:VISIBLE); settingsButton.setVisibility(value?GONE:VISIBLE);
        tools.setVisibility(value?GONE:VISIBLE); zoomControls.setVisibility(value?GONE:VISIBLE);
        axesButton.setVisibility(value?GONE:VISIBLE); orientationCard.setVisibility(value?GONE:VISIBLE); bluetoothCard.setVisibility(value?GONE:VISIBLE);
        fullscreenButton.setContentDescription(value?"Reducir vista 3D":"Ampliar vista 3D");
        if (value) settings.setVisibility(GONE);
        refreshModalScrim();
        layoutChrome();
    }

    private void layoutChrome() {
        int w=getWidth(),h=getHeight(); if (w==0 || h==0) return;
        boolean landscape=w>h;
        int margin=dp(14), gap=dp(12), headerHeight=dp(44);
        int cardHeight=landscape?Math.min(dp(88),h/4):dp(104);
        int cardWidth=landscape?Math.min(dp(260),(w-3*margin)/3):(w-2*margin-gap)/2;
        int footerTop=h-margin-cardHeight;
        int cardPadding=dp(landscape?8:10);
        orientationCard.setPadding(cardPadding,cardPadding,cardPadding,cardPadding);
        bluetoothCard.setPadding(cardPadding,cardPadding,cardPadding,cardPadding);
        LinearLayout.LayoutParams angleParams=(LinearLayout.LayoutParams)orientationCard.getChildAt(1).getLayoutParams();
        angleParams.topMargin=dp(landscape?4:12); orientationCard.getChildAt(1).setLayoutParams(angleParams);
        LinearLayout.LayoutParams deviceParams=(LinearLayout.LayoutParams)bluetoothDevice.getLayoutParams();
        deviceParams.topMargin=dp(landscape?6:10); deviceParams.bottomMargin=dp(landscape?2:6);
        bluetoothDevice.setLayoutParams(deviceParams);
        place(header,margin,dp(12),Math.min(dp(300),w-2*margin-dp(108)),headerHeight);
        place(topTools,w-margin-dp(expanded?44:98),dp(12),dp(expanded?44:98),headerHeight);
        LinearLayout.LayoutParams expandParams=(LinearLayout.LayoutParams)fullscreenButton.getLayoutParams();
        int expandGap=expanded?0:dp(10); if (expandParams.leftMargin!=expandGap) { expandParams.leftMargin=expandGap; fullscreenButton.setLayoutParams(expandParams); }
        place(orientationCard,margin,footerTop,cardWidth,cardHeight);
        place(bluetoothCard,w-margin-cardWidth,footerTop,cardWidth,cardHeight);
        int zoomHeight=dp(landscape?48:76), zoomWidth=dp(42), railWidth=dp(54), railTop=dp(landscape?64:72);
        for(int i=0;i<zoomControls.getChildCount();i++) ((TextView)zoomControls.getChildAt(i)).setTextSize(landscape?18:25);
        int zoomTop=footerTop-dp(12)-zoomHeight;
        int toolHeight=Math.max(dp(26),Math.min(dp(64),(zoomTop-dp(8)-railTop-dp(12))/3));
        for (int i=0;i<tools.getChildCount();i++) {
            LinearLayout control=(LinearLayout)tools.getChildAt(i);
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,toolHeight); p.bottomMargin=i<2?dp(6):0;
            control.setLayoutParams(p); sizeTool(control,toolHeight);
        }
        place(tools,w-margin-railWidth,railTop,railWidth,3*toolHeight+dp(12));
        place(zoomControls,w-margin-zoomWidth,zoomTop,zoomWidth,zoomHeight);
        place(axesButton,margin,footerTop-dp(12)-dp(48),dp(48),dp(48));
        place(settings,Math.max(margin,w-margin-dp(300)),dp(64),Math.min(dp(300),w-2*margin),Math.min(dp(292),h-dp(80)));
        // Place the optional panel inside the scene, leaving the header, side menu,
        // Bluetooth/status cards, zoom and axes accessible in either orientation.
        int wizardLeft=margin+(landscape?dp(60):0),wizardRight=w-margin-railWidth-dp(12);
        int wizardTop=dp(64),wizardBottom=footerTop-dp(landscape?12:72);
        int wizardWidth=Math.min(dp(landscape?480:360),Math.max(1,wizardRight-wizardLeft));
        int wizardHeight=Math.min(dp(440),Math.max(1,wizardBottom-wizardTop));
        place(wizard,(wizardLeft+wizardRight-wizardWidth)/2,(wizardTop+wizardBottom-wizardHeight)/2,wizardWidth,wizardHeight);
        wizard.setPadding(dp(18),dp(landscape?12:16),dp(18),dp(landscape?12:16));
        wizardProgress.setTextSize(landscape?12:13); wizardTitle.setTextSize(landscape?16:20);
        wizardHelp.setTextSize(landscape?13:14);
        LinearLayout.LayoutParams progressParams=(LinearLayout.LayoutParams)wizardProgress.getLayoutParams();
        progressParams.bottomMargin=dp(landscape?6:10); wizardProgress.setLayoutParams(progressParams);
        LinearLayout.LayoutParams titleParams=(LinearLayout.LayoutParams)wizardTitle.getLayoutParams();
        titleParams.bottomMargin=dp(landscape?6:10); wizardTitle.setLayoutParams(titleParams);
        LinearLayout.LayoutParams actionParams=(LinearLayout.LayoutParams)wizardActions.getLayoutParams();
        actionParams.height=dp(landscape?40:44); actionParams.topMargin=dp(landscape?8:12);
        wizardActions.setLayoutParams(actionParams);
        wizardIllustration.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(landscape?58:100)));
        FrameLayout.LayoutParams boardParams=(FrameLayout.LayoutParams)sensorBoard.getLayoutParams();
        boardParams.width=dp(landscape?68:90); boardParams.height=dp(landscape?42:70); boardParams.topMargin=dp(landscape?8:12);
        sensorBoard.setLayoutParams(boardParams); sensorBoard.setTextSize(landscape?10:12);
        FrameLayout.LayoutParams arrowParams=(FrameLayout.LayoutParams)directionArrow.getLayoutParams();
        arrowParams.width=dp(landscape?32:45); arrowParams.height=dp(landscape?44:55);
        arrowParams.leftMargin=dp(landscape?108:128); arrowParams.topMargin=dp(landscape?7:28);
        directionArrow.setLayoutParams(arrowParams); directionArrow.setTextSize(landscape?24:32);
        if (expanded) scene.setFocusBounds(margin,margin,w-margin,h-margin);
        else if (landscape) scene.setFocusBounds(dp(80),dp(12),w-dp(80),h-dp(14));
        else scene.setFocusBounds(margin,Math.min(dp(130),h/5),w-margin,footerTop-dp(54));
    }

    private void place(View view,int x,int y,int w,int h) {
        FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)view.getLayoutParams();
        if (p.width==w && p.height==h && p.leftMargin==x && p.topMargin==y) return;
        p.width=w; p.height=h; p.leftMargin=x; p.topMargin=y; view.setLayoutParams(p);
    }

    private void sizeTool(LinearLayout card,int height) {
        boolean compact=height<dp(48);
        card.setPadding(dp(2),dp(compact?1:3),dp(2),dp(compact?1:3));
        int size=Math.max(dp(12),Math.min(dp(34),height-dp(compact?18:24)));
        View tile=card.getChildAt(0); tile.setLayoutParams(new LinearLayout.LayoutParams(size,size));
        View icon=((FrameLayout)tile).getChildAt(0); int iconSize=Math.round(size*.76f);
        icon.setLayoutParams(new FrameLayout.LayoutParams(iconSize,iconSize,Gravity.CENTER));
        ((TextView)card.getChildAt(1)).setTextSize(compact?9:10);
    }

    private int dp(float value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout v=new LinearLayout(getContext()); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v=new LinearLayout(getContext()); v.setOrientation(LinearLayout.HORIZONTAL); return v; }
    private LinearLayout.LayoutParams spaced(int w,int h,int top,int bottom) { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w,h); p.topMargin=top; p.bottomMargin=bottom; return p; }
    private TextView label(String value,float size,int color,boolean bold) { TextView v=new TextView(getContext()); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setIncludeFontPadding(false); if (bold) v.setTypeface(null,1); return v; }
    private void singleLine(TextView v) { v.setSingleLine(true); v.setEllipsize(TextUtils.TruncateAt.END); }
    private void text(TextView v,String value) { if (!value.contentEquals(v.getText())) v.setText(value); }
    private String angle(double radians) { double degrees=radians*180/Math.PI; return String.format(Locale.US,"%.1f°",Math.abs(degrees)<.05?0:degrees); }
    private GradientDrawable shape(int top,int bottom,int border,int radius) { GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{top,bottom}); d.setCornerRadius(radius); d.setStroke(dp(1),border); return d; }
    private void clickable(View v,int radius) { v.setClickable(true); v.setFocusable(true); GradientDrawable mask=new GradientDrawable(); mask.setColor(0xffffffff); mask.setCornerRadius(radius); v.setForeground(new RippleDrawable(ColorStateList.valueOf(0x33439bd1),null,mask)); }
    private FrameLayout tile(NativeIcon.Shape icon,int color,int top,int bottom,int size,int radius) { FrameLayout v=new FrameLayout(getContext()); v.setBackground(shape(top,bottom,top,radius)); int imageSize=Math.round(size*.72f); v.addView(new NativeIcon(getContext(),icon,color),new FrameLayout.LayoutParams(imageSize,imageSize,Gravity.CENTER)); return v; }
    private View roundButton(NativeIcon.Shape icon,int color,int size) { FrameLayout v=tile(icon,color,0xff172638,0xff0b1525,size,size/2); v.setBackground(shape(0xff172638,0xff0b1525,0xff243d56,size/2)); clickable(v,size/2); return v; }
    private LinearLayout toolCard(NativeIcon.Shape icon,String title,int color,int top,int bottom,int tileColor) { LinearLayout v=column(); v.setGravity(Gravity.CENTER); v.setPadding(dp(3),dp(3),dp(3),dp(3)); v.setBackground(shape(top,bottom,top==0xff0b7eea?0xff1684f5:0xff22354d,dp(18))); clickable(v,dp(18)); v.addView(tile(icon,color,tileColor,tileColor,dp(34),dp(17)),new LinearLayout.LayoutParams(dp(34),dp(34))); TextView caption=label(title,10,WHITE,true); singleLine(caption); caption.setGravity(Gravity.CENTER); v.addView(caption,spaced(-1,-2,dp(3),0)); return v; }
    private LinearLayout statusCard() { LinearLayout v=column(); v.setPadding(dp(10),dp(10),dp(10),dp(10)); v.setBackground(shape(0xf20c1d2f,0xf207111e,0xff1e3c58,dp(18))); return v; }
    private LinearLayout statusHeading(LinearLayout card,NativeIcon.Shape icon,String title,int color) { LinearLayout heading=row(); heading.setGravity(Gravity.CENTER_VERTICAL); card.addView(heading,new LinearLayout.LayoutParams(-1,dp(26))); heading.addView(tile(icon,color,0xff10314a,0xff0b2338,dp(24),dp(8)),new LinearLayout.LayoutParams(dp(24),dp(24))); TextView name=label(title,11,WHITE,true); singleLine(name); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1); p.leftMargin=dp(5); heading.addView(name,p); return heading; }
    private TextView badge(String title) { TextView v=label(title,8.5f,MUTED,false); v.setSingleLine(true); v.setPadding(dp(5),dp(5),dp(5),dp(5)); v.setGravity(Gravity.CENTER); v.setBackground(shape(0xff202e43,0xff202e43,0xff202e43,dp(24))); return v; }
    private void badgeState(TextView v,int bg,int fg) { int key=31*bg+fg; if (v.getTag() instanceof Integer && (Integer)v.getTag()==key) return; v.setTag(key); v.setTextColor(fg); v.setBackground(shape(bg,bg,bg,dp(24))); }
    private TextView angleColumn(LinearLayout parent,String name) { if (parent.getChildCount()>0) { View divider=new View(getContext()); divider.setBackgroundColor(0x552d4560); parent.addView(divider,new LinearLayout.LayoutParams(dp(1),dp(30))); } LinearLayout col=column(); col.setGravity(Gravity.CENTER); parent.addView(col,new LinearLayout.LayoutParams(0,-1,1)); TextView caption=label(name,10,MUTED,false); caption.setGravity(Gravity.CENTER); col.addView(caption); TextView value=label("0.0°",18,WHITE,false); value.setGravity(Gravity.CENTER); singleLine(value); col.addView(value,spaced(-1,-2,dp(7),0)); return value; }
    private TextView smallButton(String title,int color) { TextView v=label(title,14,WHITE,true); v.setGravity(Gravity.CENTER); v.setPadding(dp(10),dp(7),dp(10),dp(7)); v.setBackground(shape(color,color,color,dp(10))); clickable(v,dp(10)); return v; }
}
