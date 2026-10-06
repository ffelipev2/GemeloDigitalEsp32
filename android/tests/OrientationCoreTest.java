package com.gemelodigital.esp32;

/** Run with javac/java; mirrors the existing Three.js calibration and timeline fixtures. */
public final class OrientationCoreTest {
    private static final OrientationCore.Vec3 X=new OrientationCore.Vec3(1,0,0);
    private static final OrientationCore.Vec3 Y=new OrientationCore.Vec3(0,1,0);
    private static final OrientationCore.Vec3 Z=new OrientationCore.Vec3(0,0,1);
    private static OrientationCore.Quat turn(OrientationCore.Vec3 axis,double deg) {
        return OrientationCore.Quat.fromAxisAngle(axis,deg*OrientationCore.RAD);
    }
    private static void equal(OrientationCore.Quat a,OrientationCore.Quat b,String what) {
        if (a.angleTo(b)>.005) throw new AssertionError(what+": "+a.angleTo(b));
    }
    private static void check(boolean condition,String what) {
        if (!condition) throw new AssertionError(what);
    }

    public static void main(String[] args) {
        OrientationCore.Vec3[][] axes={{X,Y,Z},{X,Z,Y},{Y,X,Z},{Y,Z,X},{Z,X,Y},{Z,Y,X}};
        int tested=0;
        for (int sx : new int[]{-1,1}) for (int sy : new int[]{-1,1}) for (int sz : new int[]{-1,1}) {
            for (OrientationCore.Vec3[] order:axes) {
                OrientationCore.Vec3 a=order[0].mul(sx),b=order[1].mul(sy),c=order[2].mul(sz);
                if (a.cross(b).dot(c)<0) continue;
                OrientationCore.Quat C=OrientationCore.Quat.fromBasis(a,b,c);
                OrientationCore.Quat neutral=turn(Z,63).mul(turn(X,15)).mul(C);
                OrientationCore.Quat forward=neutral.mul(C.inverse()).mul(turn(X,45)).mul(C);
                OrientationCore.Quat right=neutral.mul(C.inverse()).mul(turn(Z,-42)).mul(C);
                OrientationCore.Calibration result=OrientationCore.derive(neutral,forward,right);
                equal(result.sensorToModel,C,"Montaje");
                OrientationCore.Quat motion=turn(Y,55).mul(turn(X,-28)).mul(turn(Z,22));
                OrientationCore.Quat live=neutral.mul(C.inverse()).mul(motion).mul(C);
                equal(OrientationCore.modelMotion(live,neutral,result.sensorToModel),motion,"Movimiento");
                OrientationCore.Quat boot=turn(Z,-87).mul(neutral);
                OrientationCore.Quat ref=OrientationCore.referenceForConnection(boot,result.neutralUp);
                equal(OrientationCore.modelMotion(turn(Z,-87).mul(live),ref,result.sensorToModel),motion,"Reinicio");
                OrientationCore.Quat recentered=OrientationCore.referenceAtSavedNeutral(turn(Z,15).mul(boot),result.neutralUp);
                equal(OrientationCore.modelMotion(recentered,recentered,result.sensorToModel),OrientationCore.Quat.identity(),"Cero");
                tested++;
            }
        }
        check(tested==24,"Se esperaban 24 montajes");
        OrientationCore.Timeline timeline=new OrientationCore.Timeline();
        double next=0,previous=0;
        for (int frame=0;frame<=60;frame++) {
            double now=frame*1000.0/60;
            while(next<=now+.001) { timeline.push(turn(Y,next*.12),next); next+=40; }
            OrientationCore.Quat q=timeline.at(now);
            double angle=2*Math.atan2(q.y,q.w)/OrientationCore.RAD;
            if(now>200 && now<900) {
                check(Math.abs(angle-(now-65)*.12)<2.5,"Latencia de interpolación");
                check(angle>=previous-.2 && angle-previous<4,"Salto entre cuadros");
            }
            previous=angle;
        }
        timeline.snap(OrientationCore.Quat.identity());
        equal(timeline.at(1200),OrientationCore.Quat.identity(),"Volver a cero");
        for (int hz:new int[]{30,60,90,120}) {
            checkIrregularStream(hz,1,.55);
            checkIrregularStream(hz,2,.25);
        }
        checkBurstHistory();
        checkStreamRecovery();
        System.out.println("24 montajes; interpolación regular/irregular a 30/60/90/120 Hz; ráfagas, pausas y Cero: OK");
    }

    private static double yaw(OrientationCore.Quat q) {
        return 2*Math.atan2(q.y,q.w)/OrientationCore.RAD;
    }

    private static void checkIrregularStream(int hz,int fixture,double variationLimit) {
        OrientationCore.Timeline timeline=new OrientationCore.Timeline();
        int sample=0,n=0;
        double previous=0,sum=0,squares=0;
        int[] delays={0,22,6,18,0,25,3,12};
        for(int frame=0;frame<=hz*2;frame++) {
            double now=frame*1000.0/hz;
            while(true) {
                double captureAt=sample*40;
                double delay=fixture==1?(sample%2==1?30:0):delays[sample%delays.length];
                if(captureAt+delay>now+.001) break;
                timeline.push(turn(Y,captureAt*.12),captureAt+delay);
                sample++;
            }
            OrientationCore.Quat q=timeline.at(now);
            check(Math.abs(q.lengthSquared()-1)<1e-9,"Cuaternión normalizado");
            double angle=yaw(q),step=angle-previous;
            if(now>250 && now<1900) {
                check(step>=0,"Retroceso con un giro constante");
                check(step<2.3*(120.0/hz),"Salto excesivo con paquetes irregulares");
                double latency=now-angle/.12;
                check(latency>35 && latency<110,"Retardo acotado de la animación");
                sum+=step; squares+=step*step; n++;
            }
            previous=angle;
        }
        double mean=sum/n;
        double variation=Math.sqrt(Math.max(0,squares/n-mean*mean))/mean;
        check(variation<variationLimit,"Velocidad irregular a "+hz+" Hz: "+variation);
    }

    private static void checkBurstHistory() {
        OrientationCore.Timeline timeline=new OrientationCore.Timeline();
        timeline.snap(turn(Y,0));
        // These timestamps were captured on reception, before the main thread drained its queue.
        timeline.push(turn(Y,0),0);
        timeline.push(turn(Y,4),40);
        timeline.push(turn(Y,8),80);
        timeline.push(turn(Y,12),120);
        double angle=yaw(timeline.at(120));
        check(angle>0 && angle<4,"Una ráfaga descartó el segmento que aún se debía mostrar");
        timeline.push(turn(Y,-90),100); // Delayed/out-of-order data cannot replace the newest pose.
        for(int frame=1;frame<=20;frame++) timeline.at(120+frame*16);
        check(Math.abs(yaw(timeline.at(500))-12)<.05,"Una muestra antigua reemplazó la orientación");
    }

    private static void checkStreamRecovery() {
        OrientationCore.Timeline timeline=new OrientationCore.Timeline();
        timeline.push(turn(Y,0),0);
        timeline.push(turn(Y,90),40);
        for(int frame=0;frame<=30;frame++) timeline.at(frame*16);
        check(Math.abs(yaw(timeline.at(500))-90)<.05,"La predicción continuó con el sensor detenido");
        double before=yaw(timeline.at(500));
        timeline.clear();
        check(Math.abs(yaw(timeline.at(5000))-before)<.001,"Desconectar desplazó el modelo");
        timeline.push(turn(Y,-40),5000);
        check(Math.abs(yaw(timeline.at(5000))-before)<.001,"Reconectar saltó al primer paquete");
        for(int frame=1;frame<=20;frame++) timeline.at(5000+frame*16);
        check(Math.abs(yaw(timeline.at(5400))+40)<.05,"No recuperó el movimiento después de una pausa");
        timeline.snap(turn(Y,0));
        equal(timeline.at(6000),turn(Y,0),"Cero dejó muestras anteriores en el historial");
        timeline.push(turn(Y,20),6040);
        timeline.push(turn(Y,25),6040); // Millisecond resolution may produce equal timestamps.
        check(Double.isFinite(timeline.at(6060).lengthSquared()),"Muestras simultáneas inválidas");
    }
}
