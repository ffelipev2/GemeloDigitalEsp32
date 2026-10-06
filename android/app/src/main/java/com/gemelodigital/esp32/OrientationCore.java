package com.gemelodigital.esp32;

/** The quaternion calibration and interpolation previously used by calibration.js and orientation-timeline.js. */
final class OrientationCore {
    static final double RAD = Math.PI / 180.0;
    static final Vec3 UP = new Vec3(0, 0, 1);

    static final class Vec3 {
        final double x, y, z;
        Vec3(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
        Vec3 add(Vec3 v) { return new Vec3(x + v.x, y + v.y, z + v.z); }
        Vec3 sub(Vec3 v) { return new Vec3(x - v.x, y - v.y, z - v.z); }
        Vec3 mul(double n) { return new Vec3(x * n, y * n, z * n); }
        double dot(Vec3 v) { return x * v.x + y * v.y + z * v.z; }
        Vec3 cross(Vec3 v) { return new Vec3(y * v.z - z * v.y, z * v.x - x * v.z, x * v.y - y * v.x); }
        double length() { return Math.sqrt(dot(this)); }
        Vec3 normalized() { double n = length(); return n == 0 ? this : mul(1 / n); }
    }

    static final class Quat {
        final double x, y, z, w;
        Quat(double x, double y, double z, double w) { this.x = x; this.y = y; this.z = z; this.w = w; }
        static Quat identity() { return new Quat(0, 0, 0, 1); }
        double lengthSquared() { return x*x + y*y + z*z + w*w; }
        Quat normalized() { double n = Math.sqrt(lengthSquared()); return new Quat(x/n, y/n, z/n, w/n); }
        Quat inverse() { return new Quat(-x, -y, -z, w).normalized(); }
        Quat mul(Quat q) {
            return new Quat(w*q.x + x*q.w + y*q.z - z*q.y,
                    w*q.y - x*q.z + y*q.w + z*q.x,
                    w*q.z + x*q.y - y*q.x + z*q.w,
                    w*q.w - x*q.x - y*q.y - z*q.z);
        }
        Vec3 rotate(Vec3 v) {
            Vec3 qv = new Vec3(x, y, z);
            Vec3 t = qv.cross(v).mul(2);
            return v.add(t.mul(w)).add(qv.cross(t));
        }
        double dot(Quat q) { return x*q.x + y*q.y + z*q.z + w*q.w; }
        double angleTo(Quat q) { return 2 * Math.acos(Math.min(1, Math.abs(dot(q)))); }
        Quat slerp(Quat q, double t) {
            double d = dot(q);
            if (d < 0) { q = new Quat(-q.x, -q.y, -q.z, -q.w); d = -d; }
            if (d > 0.9995) return new Quat(x+t*(q.x-x), y+t*(q.y-y), z+t*(q.z-z), w+t*(q.w-w)).normalized();
            double angle = Math.acos(Math.max(-1, Math.min(1, d)));
            double sin = Math.sin(angle);
            double a = Math.sin((1-t)*angle)/sin, b = Math.sin(t*angle)/sin;
            return new Quat(a*x+b*q.x, a*y+b*q.y, a*z+b*q.z, a*w+b*q.w).normalized();
        }
        static Quat fromAxisAngle(Vec3 axis, double angle) {
            double s = Math.sin(angle/2);
            return new Quat(axis.x*s, axis.y*s, axis.z*s, Math.cos(angle/2));
        }
        static Quat fromUnitVectors(Vec3 from, Vec3 to) {
            double r = from.dot(to) + 1;
            Vec3 axis;
            if (r < 1e-6) {
                axis = Math.abs(from.x) > Math.abs(from.z)
                        ? new Vec3(-from.y, from.x, 0) : new Vec3(0, -from.z, from.y);
                return new Quat(axis.x, axis.y, axis.z, 0).normalized();
            }
            axis = from.cross(to);
            return new Quat(axis.x, axis.y, axis.z, r).normalized();
        }
        static Quat fromBasis(Vec3 x, Vec3 y, Vec3 z) {
            double m11=x.x, m12=y.x, m13=z.x, m21=x.y, m22=y.y, m23=z.y, m31=x.z, m32=y.z, m33=z.z;
            double qx, qy, qz, qw, trace = m11+m22+m33;
            if (trace > 0) {
                double s = 0.5 / Math.sqrt(trace+1);
                qw = 0.25/s; qx=(m32-m23)*s; qy=(m13-m31)*s; qz=(m21-m12)*s;
            } else if (m11 > m22 && m11 > m33) {
                double s = 2*Math.sqrt(1+m11-m22-m33);
                qw=(m32-m23)/s; qx=0.25*s; qy=(m12+m21)/s; qz=(m13+m31)/s;
            } else if (m22 > m33) {
                double s = 2*Math.sqrt(1+m22-m11-m33);
                qw=(m13-m31)/s; qx=(m12+m21)/s; qy=0.25*s; qz=(m23+m32)/s;
            } else {
                double s = 2*Math.sqrt(1+m33-m11-m22);
                qw=(m21-m12)/s; qx=(m13+m31)/s; qy=(m23+m32)/s; qz=0.25*s;
            }
            return new Quat(qx,qy,qz,qw).normalized();
        }
        /** Three.js Euler order YXZ, used only for the existing status labels. */
        double[] eulerYXZ() {
            double m11=1-2*(y*y+z*z), m13=2*(x*z+y*w), m21=2*(x*y+z*w);
            double m22=1-2*(x*x+z*z), m23=2*(y*z-x*w), m31=2*(x*z-y*w), m33=1-2*(x*x+y*y);
            double pitch=Math.asin(Math.max(-1,Math.min(1,-m23)));
            double yaw, roll;
            if (Math.abs(m23)<0.9999999) { yaw=Math.atan2(m13,m33); roll=Math.atan2(m21,m22); }
            else { yaw=Math.atan2(-m31,m11); roll=0; }
            return new double[]{pitch,roll,yaw};
        }
    }

    static final class Calibration {
        final Quat sensorToModel;
        final Vec3 neutralUp;
        Calibration(Quat sensorToModel, Vec3 neutralUp) {
            this.sensorToModel=sensorToModel; this.neutralUp=neutralUp;
        }
    }

    static Vec3 axisFromPose(Quat neutral, Quat pose) {
        Quat rotation = neutral.inverse().mul(pose).normalized();
        if (rotation.w < 0) rotation = new Quat(-rotation.x,-rotation.y,-rotation.z,-rotation.w);
        double angle = 2*Math.atan2(Math.sqrt(rotation.x*rotation.x+rotation.y*rotation.y+rotation.z*rotation.z),rotation.w);
        if (angle < 20*RAD || angle > 85*RAD)
            throw new IllegalArgumentException("Inclina entre 20° y 85° desde la posición inicial y vuelve a intentarlo.");
        return new Vec3(rotation.x,rotation.y,rotation.z).normalized();
    }

    static Calibration derive(Quat neutral, Quat forward, Quat right) {
        Vec3 forwardAxis=axisFromPose(neutral,forward), rightAxis=axisFromPose(neutral,right);
        if (Math.abs(forwardAxis.dot(rightAxis)) > 0.65)
            throw new IllegalArgumentException("Las dos inclinaciones parecen usar el mismo eje. Vuelve a la posición inicial e inclina hacia la derecha.");
        // Same proper basis as calibration.js: X = forward tilt, -Z = right tilt.
        Vec3 sensorX=forwardAxis;
        Vec3 sensorZ=rightAxis.mul(-1).add(sensorX.mul(rightAxis.dot(sensorX))).normalized();
        Vec3 sensorY=sensorZ.cross(sensorX).normalized();
        return new Calibration(Quat.fromBasis(sensorX,sensorY,sensorZ).inverse(),neutral.inverse().rotate(UP).normalized());
    }

    static Quat referenceForConnection(Quat current, Vec3 neutralUp) {
        Vec3 currentUp=current.rotate(neutralUp).normalized();
        return Quat.fromUnitVectors(currentUp,UP).mul(current).normalized();
    }

    static Quat referenceAtSavedNeutral(Quat current, Vec3 neutralUp) {
        Vec3 currentUp=current.rotate(neutralUp).normalized();
        if (currentUp.dot(UP) < Math.cos(20*RAD)) throw new IllegalArgumentException("Colócalo en posición inicial");
        return current;
    }

    static Quat modelMotion(Quat current, Quat reference, Quat sensorToModel) {
        return sensorToModel.mul(reference.inverse()).mul(current).mul(sensorToModel.inverse()).normalized();
    }

    static final class Timeline {
        // Keep the segment for the delayed render time even when BLE callbacks arrive in bursts.
        private static final int CAPACITY=16;
        private static final double RENDER_DELAY_MS=50, FILTER_MS=25, RESET_GAP_MS=200;
        private final Quat[] samples=new Quat[CAPACITY];
        private final double[] times=new double[CAPACITY];
        private int first, count;
        private Quat output=Quat.identity();
        private double frameAt=Double.NaN;

        void clear() {
            java.util.Arrays.fill(samples,null);
            first=0; count=0; frameAt=Double.NaN;
        }
        void snap(Quat q) { clear(); output=q; }

        void push(Quat q, double now) {
            if (!Double.isFinite(now)) return;
            if (count>0) {
                int last=index(count-1);
                if (now<times[last]) return;
                if (now==times[last]) { samples[last]=q; return; }
                if (now-times[last]>RESET_GAP_MS) clear();
            }
            if (count==0) append(output,now-RENDER_DELAY_MS);
            append(q,now);
        }

        private int index(int offset) { return (first+offset)%CAPACITY; }
        private void append(Quat q,double now) {
            if (count==CAPACITY) { samples[first]=null; first=index(1); count--; }
            int slot=index(count++); samples[slot]=q; times[slot]=now;
        }

        Quat at(double now) {
            double frameMs=Double.isNaN(frameAt)?16:Math.max(0,Math.min(100,now-frameAt));
            frameAt=now;
            if (count==0) return output;
            double renderAt=now-RENDER_DELAY_MS;
            while (count>2 && times[index(1)]<=renderAt) {
                samples[first]=null; first=index(1); count--;
            }
            int next=index(1);
            double interval=Math.max(1,times[next]-times[first]);
            double fraction=Math.max(0,(renderAt-times[first])/interval);
            if (fraction>1) {
                double angle=samples[first].angleTo(samples[next]);
                // After a stopped feed, settle on the last measured pose instead of a prediction.
                double extraMs=now-times[next]>120?0:Math.min(10,renderAt-times[next]);
                fraction=1+Math.min(extraMs/interval,angle>0?(3*RAD)/angle:0);
            }
            Quat target=samples[first].slerp(samples[next],fraction);
            // A fixed time constant avoids changing speed at angular-error thresholds.
            output=output.slerp(target,1-Math.exp(-frameMs/FILTER_MS));
            return output;
        }
    }
}
