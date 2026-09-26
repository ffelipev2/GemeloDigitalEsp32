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
                check(Math.abs(angle-(now-45)*.12)<2.5,"Latencia de interpolación");
                check(angle>=previous-.2 && angle-previous<4,"Salto entre cuadros");
            }
            previous=angle;
        }
        timeline.snap(OrientationCore.Quat.identity());
        equal(timeline.at(1200),OrientationCore.Quat.identity(),"Volver a cero");
        System.out.println("24 montajes y cronología nativa equivalentes: OK");
    }
}
