package com.phonexr.roblox;

import android.app.Activity;
import android.media.Image;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;

/**
 * Optional 6DoF: ARCore tracks where the phone is in the room; that position moves the
 * Roblox head (rotation stays on the low-latency gyroscope). ARCore owns the back camera
 * then, so its CPU camera image also feeds hand tracking in place of camera2.
 */
final class ArCoreHead {
    private static final String TAG = "PhoneXR-ARCore";
    /** Roblox UserCFrame positions are in studs; 1 stud is 0.28 m. */
    private static final float STUDS_PER_METRE = 1f / 0.28f;

    interface ImageSink {
        /** Called on the hand thread; the image is closed afterwards by ArCoreHead. */
        void onImage(Image image);
        float headYaw();
    }

    private final Activity activity;
    private final ImageSink sink;
    private HandlerThread thread, handThread;
    private Handler handHandler;
    private volatile boolean running;
    private volatile boolean handBusy;
    private volatile boolean recenterRequested = true;
    private Session session;

    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface surface = EGL14.EGL_NO_SURFACE;

    private float originX, originY, originZ;
    private float yawOffset;
    private boolean yawOffsetReady;
    private final float[] forward = new float[3];
    private long lastLog;

    ArCoreHead(Activity activity, ImageSink sink) {
        this.activity = activity;
        this.sink = sink;
    }

    /**
     * True when ARCore is installed and this phone supports it (no install prompt in VR).
     * The first check is often still "checking" (asynchronous); callers poll again later.
     */
    private static ArCoreApk.Availability lastReported;

    static boolean available(Activity activity) {
        try {
            ArCoreApk.Availability availability = ArCoreApk.getInstance().checkAvailability(activity);
            if (availability != ArCoreApk.Availability.SUPPORTED_INSTALLED && !availability.isTransient()
                    && availability != lastReported) {
                lastReported = availability;
                Log.w(TAG, "ARCore not usable: " + availability);
            }
            return availability == ArCoreApk.Availability.SUPPORTED_INSTALLED;
        } catch (Throwable error) {
            Log.w(TAG, "ARCore availability check failed", error);
            return false;
        }
    }

    /** Supported phone without "Google Play Services for AR": it has to be installed first. */
    static boolean needsInstall(Activity activity) {
        try {
            ArCoreApk.Availability a = ArCoreApk.getInstance().checkAvailability(activity);
            return a == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED || a == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD;
        } catch (Throwable error) {
            return false;
        }
    }

    private volatile long failedAtMs;

    /** ARCore failed a moment ago (camera still busy, session error): use camera2 meanwhile. */
    boolean failedRecently() {
        return System.currentTimeMillis() - failedAtMs < 5000;
    }

    boolean isRunning() {
        return running;
    }

    void recenter() {
        recenterRequested = true;
    }

    void start() {
        if (running) return;
        running = true;
        handThread = new HandlerThread("RobloxVR-ARHands");
        handThread.start();
        handHandler = new Handler(handThread.getLooper());
        thread = new HandlerThread("RobloxVR-ARCore");
        thread.start();
        new Handler(thread.getLooper()).post(this::loop);
    }

    void stop() {
        running = false;
        if (thread != null) thread.quitSafely();
        if (handThread != null) handThread.quitSafely();
        thread = null;
        handThread = null;
        handHandler = null;
        try { PhoneXRBridge.setHeadPosition(0f, 0f, 0f); } catch (Throwable ignored) { }
    }

    private void loop() {
        try {
            if (!initGl()) { running = false; return; }
            session = new Session(activity);
            Config config = new Config(session);
            // Only the camera pose is needed: no planes, light or depth keeps ARCore cheap.
            config.setPlaneFindingMode(Config.PlaneFindingMode.DISABLED);
            config.setLightEstimationMode(Config.LightEstimationMode.DISABLED);
            config.setDepthMode(Config.DepthMode.DISABLED);
            config.setUpdateMode(Config.UpdateMode.BLOCKING);
            config.setFocusMode(Config.FocusMode.FIXED);
            session.configure(config);
            int[] texture = new int[1];
            GLES20.glGenTextures(1, texture, 0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture[0]);
            session.setCameraTextureName(texture[0]);
            session.resume();
            Log.i(TAG, "ARCore 6DoF started");
            while (running) step();
        } catch (Throwable error) {
            failedAtMs = System.currentTimeMillis();
            Log.w(TAG, "ARCore 6DoF failed, retrying in 5 s", error);
        } finally {
            if (session != null) {
                try { session.pause(); } catch (Throwable ignored) { }
                session.close();
                session = null;
            }
            releaseGl();
            running = false;
        }
    }

    private static final float WALK_MIN = 0.15f, WALK_MAX = 1.0f; // m/s
    private boolean haveLast;
    private float lastX, lastZ, velX, velZ;
    private long lastStepNs;

    private void step() throws Exception {
        Frame frame = session.update();
        Camera camera = frame.getCamera();
        if (camera.getTrackingState() == TrackingState.TRACKING) {
            Pose pose = camera.getPose();
            float x = pose.tx(), y = pose.ty(), z = pose.tz();
            // ARCore's world has its own yaw zero; the gyro head yaw has another. Line them up
            // from the direction the back camera looks (-Z of the camera pose), slowly, so the
            // gyro's drift is followed without jumps.
            pose.getTransformedAxis(2, -1f, forward, 0);
            float arYaw = (float) Math.atan2(forward[0], -forward[2]);
            float offset = wrap(arYaw - sink.headYaw());
            if (!yawOffsetReady) { yawOffset = offset; yawOffsetReady = true; }
            else yawOffset = wrap(yawOffset + wrap(offset - yawOffset) * 0.02f);
            if (recenterRequested) {
                recenterRequested = false;
                originX = x; originY = y; originZ = z;
            }
            long now = System.nanoTime();
            if (haveLast) {
                float dt = (now - lastStepNs) * 1e-9f;
                if (dt > 0.001f && dt < 0.5f) {
                    float cw = (float) Math.cos(-yawOffset), sw = (float) Math.sin(-yawOffset);
                    float vxw = (x - lastX) / dt, vzw = (z - lastZ) / dt;
                    float vx = vxw * cw - vzw * sw, vz = vzw * cw + vxw * sw; // tracking space, m/s
                    velX += (vx - velX) * 0.2f;
                    velZ += (vz - velZ) * 0.2f;
                    float yaw = sink.headYaw();
                    float fwd = velX * (float) Math.sin(yaw) - velZ * (float) Math.cos(yaw);
                    float rgt = velX * (float) Math.cos(yaw) + velZ * (float) Math.sin(yaw);
                    float mag = (float) Math.hypot(fwd, rgt);
                    float k = mag < WALK_MIN ? 0f : Math.min(1f, (mag - WALK_MIN) / (WALK_MAX - WALK_MIN)) / mag;
                    PhoneXRBridge.onPhysicalWalk(rgt * k, fwd * k);
                }
            }
            haveLast = true; lastX = x; lastZ = z; lastStepNs = now;
            // Steady walking moves the character, so the camera offset follows slowly; leaning stays.
            originX += (x - originX) * 0.03f;
            originZ += (z - originZ) * 0.03f;
            float dx = x - originX, dy = y - originY, dz = z - originZ;
            // Rotate the AR-world offset into the head's yaw frame (yaw right positive).
            float c = (float) Math.cos(-yawOffset), s = (float) Math.sin(-yawOffset);
            float rx = dx * c - dz * s, rz = dz * c + dx * s;
            PhoneXRBridge.setHeadPosition(rx * STUDS_PER_METRE, dy * STUDS_PER_METRE, rz * STUDS_PER_METRE);
            if (System.currentTimeMillis() - lastLog > 2000) {
                lastLog = System.currentTimeMillis();
                Log.i(TAG, "6DoF head x=" + rx + " y=" + dy + " z=" + rz + " m");
            }
        }
        // One camera image at a time goes to hand tracking; ARCore keeps its own pace.
        if (!handBusy && handHandler != null) {
            final Image image;
            try {
                image = frame.acquireCameraImage();
            } catch (Throwable notYet) {
                return;
            }
            handBusy = true;
            Handler target = handHandler;
            if (target == null || !target.post(() -> {
                try { sink.onImage(image); } finally { image.close(); handBusy = false; }
            })) {
                image.close();
                handBusy = false;
            }
        }
    }

    private static float wrap(float angle) {
        while (angle > Math.PI) angle -= (float) (2 * Math.PI);
        while (angle < -Math.PI) angle += (float) (2 * Math.PI);
        return angle;
    }

    /** ARCore needs a current GL context for its camera texture; a 1x1 pbuffer is enough. */
    private boolean initGl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return false;
        int[] attribs = {EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE};
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) || count[0] == 0) return false;
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
        return EGL14.eglMakeCurrent(display, surface, surface, context);
    }

    private void releaseGl() {
        if (display == EGL14.EGL_NO_DISPLAY) return;
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
        display = EGL14.EGL_NO_DISPLAY;
        surface = EGL14.EGL_NO_SURFACE;
        context = EGL14.EGL_NO_CONTEXT;
    }
}
