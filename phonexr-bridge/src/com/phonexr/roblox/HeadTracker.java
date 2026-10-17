package com.phonexr.roblox;

import android.view.Surface;

/**
 * Cardboard-style 3DoF head tracker: integrates the raw gyroscope into a full
 * orientation quaternion, corrects tilt drift with the accelerometer (Mahony
 * complementary filter) and predicts ahead to hide sensor-to-photon latency.
 *
 * Output is the head rotation in Roblox space (Y up, looking down -Z) as a
 * row-major 3x3 matrix whose columns are Right, Up and Back, recentered so that
 * yaw 0 is the direction faced at the last recenter.
 */
final class HeadTracker {
    private static final float PREDICTION_SECONDS = 0.05f;
    private static final float TILT_GAIN = 0.6f;     // rad/s per unit gravity error
    private static final float GRAVITY = 9.80665f;

    // Device-to-world orientation (Android world: X east, Y north, Z up).
    private float qw = 1f, qx, qy, qz;
    private final float[] accel = new float[3];
    private final float[] rate = new float[3];
    private boolean haveAccel;
    private boolean initialized;
    private long lastTimestamp;
    private float recenterYaw;
    private boolean recenterPending = true;

    boolean isReady() {
        return initialized;
    }

    void reset() {
        initialized = false;
        lastTimestamp = 0;
    }

    void onAccelerometer(float[] values) {
        if (!haveAccel) {
            System.arraycopy(values, 0, accel, 0, 3);
            haveAccel = true;
        } else {
            for (int i = 0; i < 3; i++) accel[i] += (values[i] - accel[i]) * 0.5f;
        }
        if (!initialized) initFromGravity();
    }

    void onGyroscope(float[] values, long timestamp) {
        rate[0] = values[0];
        rate[1] = values[1];
        rate[2] = values[2];
        if (!initialized || lastTimestamp == 0) {
            lastTimestamp = timestamp;
            return;
        }
        float dt = (timestamp - lastTimestamp) * 1e-9f;
        lastTimestamp = timestamp;
        if (dt <= 0f || dt > 0.1f) return;

        float wx = rate[0], wy = rate[1], wz = rate[2];
        float an = (float) Math.sqrt(accel[0] * accel[0] + accel[1] * accel[1] + accel[2] * accel[2]);
        // Only trust the accelerometer as "up" while the head is not accelerating.
        if (haveAccel && Math.abs(an - GRAVITY) < 1.5f) {
            float ax = accel[0] / an, ay = accel[1] / an, az = accel[2] / an;
            // Estimated world-up in device coordinates: third row of R(q).
            float vx = 2f * (qx * qz - qw * qy);
            float vy = 2f * (qw * qx + qy * qz);
            float vz = qw * qw - qx * qx - qy * qy + qz * qz;
            wx += TILT_GAIN * (ay * vz - az * vy);
            wy += TILT_GAIN * (az * vx - ax * vz);
            wz += TILT_GAIN * (ax * vy - ay * vx);
        }
        integrate(wx, wy, wz, dt);
    }

    /** Uses an externally fused device orientation (Android game rotation vector). */
    void setDeviceOrientation(float w, float x, float y, float z) {
        qw = w; qx = x; qy = y; qz = z;
        rate[0] = rate[1] = rate[2] = 0f;
        if (!initialized) {
            initialized = true;
            recenterPending = true;
        }
    }

    /** Snap/smooth turn: positive turns the view left. */
    void turn(float radians) {
        recenterYaw -= radians;
    }

    /** Stores the current heading so that it becomes straight ahead. */
    void recenter() {
        recenterPending = true;
    }

    /**
     * Writes the predicted head rotation (Roblox space, recentered) into out[9].
     * Returns yaw (right positive) and pitch (up positive) in yawPitch[0..1].
     */
    void headMatrix(int displayRotation, float[] out, float[] yawPitch) {
        // Predict ahead using the latest angular velocity, like the Cardboard SDK.
        float angle = (float) Math.sqrt(rate[0] * rate[0] + rate[1] * rate[1] + rate[2] * rate[2])
                * PREDICTION_SECONDS;
        float pw = qw, px = qx, py = qy, pz = qz;
        if (angle > 1e-5f) {
            float s = (float) Math.sin(angle * 0.5f) / (angle / PREDICTION_SECONDS);
            float dw = (float) Math.cos(angle * 0.5f);
            float dx = rate[0] * s, dy = rate[1] * s, dz = rate[2] * s;
            pw = qw * dw - qx * dx - qy * dy - qz * dz;
            px = qw * dx + qx * dw + qy * dz - qz * dy;
            py = qw * dy - qx * dz + qy * dw + qz * dx;
            pz = qw * dz + qx * dy - qy * dx + qz * dw;
        }
        float[] m = new float[9];
        if (recenterPending) {
            // Heading of the unpredicted pose becomes yaw 0 (Roblox yaw: CCW about +Y).
            worldFromDisplay(qw, qx, qy, qz, displayRotation, m);
            float fx = -m[2], fz = m[5]; // forward = -Back; Roblox z = -north
            recenterYaw = (float) Math.atan2(-fx, -fz);
            recenterPending = false;
        }
        worldFromDisplay(pw, px, py, pz, displayRotation, m);

        // Android world (X east, Y north, Z up) -> Roblox (X, Y up, Z south), then remove recenter yaw.
        float c = (float) Math.cos(recenterYaw), s = (float) Math.sin(recenterYaw);
        for (int col = 0; col < 3; col++) {
            float ex = m[col], ey = m[3 + col], ez = m[6 + col];
            float rx = ex, ry = ez, rz = -ey;
            // Rotate by -recenterYaw about +Y.
            out[col] = c * rx - s * rz;
            out[3 + col] = ry;
            out[6 + col] = s * rx + c * rz;
        }
        // Forward = -Back column.
        float fx = -out[2], fy = -out[5], fz = -out[8];
        yawPitch[0] = (float) Math.atan2(fx, -fz);
        yawPitch[1] = (float) Math.asin(Math.max(-1f, Math.min(1f, fy)));
    }

    private void initFromGravity() {
        float an = (float) Math.sqrt(accel[0] * accel[0] + accel[1] * accel[1] + accel[2] * accel[2]);
        if (an < 1f) return;
        float ax = accel[0] / an, ay = accel[1] / an, az = accel[2] / an;
        // Shortest rotation taking device-frame up (a) to world +Z.
        float w = 1f + az;
        if (w < 1e-4f) {
            qw = 0f; qx = 1f; qy = 0f; qz = 0f;
        } else {
            // q = normalize(1 + a.z, a x z) where a x (0,0,1) = (ay, -ax, 0)
            float n = (float) Math.sqrt(w * w + ay * ay + ax * ax);
            qw = w / n; qx = ay / n; qy = -ax / n; qz = 0f;
        }
        initialized = true;
        recenter();
    }

    private void integrate(float wx, float wy, float wz, float dt) {
        float hx = wx * dt * 0.5f, hy = wy * dt * 0.5f, hz = wz * dt * 0.5f;
        float nw = qw - qx * hx - qy * hy - qz * hz;
        float nx = qx + qw * hx + qy * hz - qz * hy;
        float ny = qy + qw * hy - qx * hz + qz * hx;
        float nz = qz + qw * hz + qx * hy - qy * hx;
        float n = (float) Math.sqrt(nw * nw + nx * nx + ny * ny + nz * nz);
        qw = nw / n; qx = nx / n; qy = ny / n; qz = nz / n;
    }

    /** World-from-display rotation: columns are the display X, Y, Z axes in world space. */
    private static void worldFromDisplay(float w, float x, float y, float z, int displayRotation, float[] m) {
        float r00 = 1 - 2 * (y * y + z * z), r01 = 2 * (x * y - w * z), r02 = 2 * (x * z + w * y);
        float r10 = 2 * (x * y + w * z), r11 = 1 - 2 * (x * x + z * z), r12 = 2 * (y * z - w * x);
        float r20 = 2 * (x * z - w * y), r21 = 2 * (y * z + w * x), r22 = 1 - 2 * (x * x + y * y);
        // Display axes in device coordinates. ROTATION_90 means the device was turned
        // counter-clockwise, so the device top (+Y) points to the screen's left.
        float dxx = 1, dxy = 0, dyx = 0, dyy = 1;
        switch (displayRotation) {
            case Surface.ROTATION_90: dxx = 0; dxy = -1; dyx = 1; dyy = 0; break;
            case Surface.ROTATION_180: dxx = -1; dxy = 0; dyx = 0; dyy = -1; break;
            case Surface.ROTATION_270: dxx = 0; dxy = 1; dyx = -1; dyy = 0; break;
            default: break;
        }
        // Column 0: R * displayX, column 1: R * displayY, column 2: R * deviceZ.
        m[0] = r00 * dxx + r01 * dxy; m[3] = r10 * dxx + r11 * dxy; m[6] = r20 * dxx + r21 * dxy;
        m[1] = r00 * dyx + r01 * dyy; m[4] = r10 * dyx + r11 * dyy; m[7] = r20 * dyx + r21 * dyy;
        m[2] = r02; m[5] = r12; m[8] = r22;
    }
}
