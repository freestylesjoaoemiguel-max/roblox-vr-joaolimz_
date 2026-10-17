package com.phonexr.roblox;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.Image;
import android.util.Log;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;

final class MediaPipeHandTracker implements AutoCloseable {
    private static final String TAG = "PhoneXR-Hands";
    private final Context context;
    private final ByteBuffer modelBuffer;
    private HandLandmarker landmarker;
    private boolean usingGpu;
    long lastDetectNanos;
    private Bitmap bitmap;
    private int[] pixels;
    /** Last analysed frame (camera orientation) and its landmarks, for passthrough and the hand check. */
    volatile int frameWidth, frameHeight;
    volatile float[][] lastLandmarks = new float[0][];
    volatile boolean[] lastIsLeft = new boolean[0];
    private byte[] yBytes, uBytes, vBytes;
    private long lastTimestampMs;

    static final class Pose {
        boolean active;
        float[] landmarks;
        float x, y, angle, scale = 1f;
        /** Landmarks in screen space (x, y in 0..1, y down) for the skeleton overlay. */
        float[] screen;
        /** Thumb-index gap in palm widths (PhoneXR HandGestures.pinchGap / pinchGap3d). */
        float pinchGap = 1f;
        /** All fingers folded (grip). */
        boolean fist;
        /** Pinch after the latch's hysteresis: the click. */
        boolean pinching;
    }

    /** PhoneXR HandGestures.PinchLatch: starts when fingers touch, ends once they clearly open. */
    private static final class PinchLatch {
        private static final float CLOSE = .30f, OPEN = .48f;
        private boolean pinching;

        boolean update(Pose pose) {
            pinching = !pose.fist && (pinching ? pose.pinchGap < OPEN : pose.pinchGap < CLOSE);
            return pinching;
        }

        void reset() { pinching = false; }
    }

    private final PinchLatch leftLatch = new PinchLatch(), rightLatch = new PinchLatch();

    private static final int[] PALM = {0, 5, 9, 13, 17};

    static final class Result {
        final Pose left = new Pose();
        final Pose right = new Pose();
    }

    MediaPipeHandTracker(Context context) throws Exception {
        InputStream input = context.getAssets().open("hand_landmarker.task");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] chunk = new byte[16384];
        int read;
        while ((read = input.read(chunk)) >= 0) output.write(chunk, 0, read);
        input.close();
        byte[] model = output.toByteArray();
        this.context = context;
        modelBuffer = ByteBuffer.allocateDirect(model.length);
        modelBuffer.put(model).rewind();
        boolean preferGpu = context.getSharedPreferences("roblox_vr", Context.MODE_PRIVATE)
                .getBoolean("pref_hand_gpu", true);
        try {
            if (!preferGpu) throw new IllegalStateException("CPU selected in settings");
            landmarker = create(Delegate.GPU);
            usingGpu = true;
        } catch (Throwable error) {
            Log.w(TAG, "GPU hand tracking unavailable, using CPU", error);
            landmarker = create(Delegate.CPU);
        }
        Log.i(TAG, "MediaPipe Hand Landmarker ready (" + (usingGpu ? "GPU" : "CPU") + ")");
    }

    private HandLandmarker create(Delegate delegate) {
        modelBuffer.rewind();
        BaseOptions base = BaseOptions.builder().setModelAssetBuffer(modelBuffer).setDelegate(delegate).build();
        HandLandmarker.HandLandmarkerOptions options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.VIDEO)
                .setNumHands(2)
                .setMinHandDetectionConfidence(0.45f)
                .setMinHandPresenceConfidence(0.45f)
                .setMinTrackingConfidence(0.45f)
                .build();
        return HandLandmarker.createFromOptions(context, options);
    }

    /**
     * @param rotation clockwise degrees (0/90/180/270) that turn the camera image upright
     *                 on screen; landmarks are returned in screen space.
     */
    Result track(Image image, int rotation) {
        // ARCore's CPU image is 640x480: halve it, MediaPipe gains nothing from more pixels.
        int step = image.getWidth() >= 600 ? 2 : 1;
        int width = image.getWidth() / step;
        int height = image.getHeight() / step;
        if (bitmap == null || bitmap.getWidth() != width || bitmap.getHeight() != height) {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            pixels = new int[width * height];
        }
        yuvToArgb(image, width, height, step);
        frameWidth = width;
        frameHeight = height;
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        // Do not close the MPImage: that recycles the bitmap reused for the next frame.
        MPImage mpImage = new BitmapImageBuilder(bitmap).build();
        // VIDEO mode tracks landmarks from the previous frame instead of running the
        // palm detector every time, which is several times faster on a phone.
        long timestampMs = Math.max(lastTimestampMs + 1, image.getTimestamp() / 1_000_000L);
        lastTimestampMs = timestampMs;
        long started = System.nanoTime();
        HandLandmarkerResult detected;
        try {
            detected = landmarker.detectForVideo(mpImage, timestampMs);
        } catch (RuntimeException error) {
            if (!usingGpu) throw error;
            // Some GPU drivers only fail on the first inference; fall back to CPU once.
            Log.w(TAG, "GPU hand tracking failed, switching to CPU", error);
            landmarker.close();
            landmarker = create(Delegate.CPU);
            usingGpu = false;
            detected = landmarker.detectForVideo(mpImage, timestampMs);
        }
        lastDetectNanos = System.nanoTime() - started;
        Result result = poses(detected, rotation);
        if (result.left.active) result.left.pinching = leftLatch.update(result.left); else leftLatch.reset();
        if (result.right.active) result.right.pinching = rightLatch.update(result.right); else rightLatch.reset();
        toScreen(result.left, rotation);
        toScreen(result.right, rotation);
        return result;
    }

    private static void toScreen(Pose pose, int rotation) {
        if (!pose.active) return;
        float[] marks = pose.landmarks;
        if (marks != null) {
            float[] out = new float[42];
            for (int i = 0; i < 42; i += 2) {
                float lx = marks[i], ly = marks[i + 1];
                switch (rotation) {
                    case 90: out[i] = 1f - ly; out[i + 1] = lx; break;
                    case 180: out[i] = 1f - lx; out[i + 1] = 1f - ly; break;
                    case 270: out[i] = ly; out[i + 1] = 1f - lx; break;
                    default: out[i] = lx; out[i + 1] = ly; break;
                }
            }
            pose.screen = out;
        }
        float x = pose.x, y = pose.y;
        switch (rotation) {
            case 90: pose.x = 1f - y; pose.y = x; pose.angle += (float) Math.PI * 0.5f; break;
            case 180: pose.x = 1f - x; pose.y = 1f - y; pose.angle += (float) Math.PI; break;
            case 270: pose.x = y; pose.y = 1f - x; pose.angle -= (float) Math.PI * 0.5f; break;
            default: break;
        }
    }

    int[] pixels() {
        return pixels;
    }

    private Result poses(HandLandmarkerResult detected, int rotation) {
        Result result = new Result();
        List<List<NormalizedLandmark>> hands = detected.landmarks();
        List<List<com.google.mediapipe.tasks.components.containers.Landmark>> world = detected.worldLandmarks();
        Pose[] found = new Pose[Math.min(2, hands.size())];
        for (int i = 0; i < found.length; i++) {
            List<NormalizedLandmark> points = hands.get(i);
            if (points.size() < 21) continue;
            Pose pose = new Pose();
            pose.landmarks = new float[42];
            for (int k = 0; k < 21; k++) {
                pose.landmarks[k * 2] = points.get(k).x();
                pose.landmarks[k * 2 + 1] = points.get(k).y();
            }
            int[] palm = PALM;
            for (int index : palm) {
                pose.x += points.get(index).x();
                pose.y += points.get(index).y();
            }
            pose.x /= palm.length;
            pose.y /= palm.length;
            float dx = points.get(9).x() - points.get(0).x();
            float dy = points.get(9).y() - points.get(0).y();
            // 0 = fingers up (screen y grows down); the old atan2(dy,dx)-90° was off by 180°,
            // which showed the controllers upside down.
            pose.angle = (float) Math.atan2(dx, -dy);
            float minX = 1f, minY = 1f, maxX = 0f, maxY = 0f;
            for (NormalizedLandmark point : points) {
                minX = Math.min(minX, point.x()); maxX = Math.max(maxX, point.x());
                minY = Math.min(minY, point.y()); maxY = Math.max(maxY, point.y());
            }
            pose.scale = Math.max(0.65f, Math.min(1.55f, Math.max(maxX - minX, maxY - minY) / 0.28f));
            shape(pose, points, i < world.size() ? world.get(i) : null);
            pose.active = true;
            found[i] = pose;
        }
        boolean[] isLeft = new boolean[found.length];
        // MediaPipe sometimes reports one hand twice: both Roblox hands then sat in one spot.
        if (found.length == 2 && found[0] != null && found[1] != null
                && Math.hypot(screenX(found[0], rotation) - screenX(found[1], rotation),
                screenY(found[0], rotation) - screenY(found[1], rotation)) < 0.08) {
            found[1] = null;
        }
        long now = System.currentTimeMillis();
        if (found.length == 2 && found[0] != null && found[1] != null) {
            // Two hands: the one further left on screen is the left hand. Compared after the
            // camera rotation: in the raw image a headset worn the other way round swapped them.
            isLeft[0] = screenX(found[0], rotation) < screenX(found[1], rotation);
            isLeft[1] = !isLeft[0];
        } else {
            for (int i = 0; i < found.length; i++) {
                if (found[i] == null) continue;
                float sx = screenX(found[i], rotation), sy = screenY(found[i], rotation);
                // One hand: it stays the side it was a moment ago (no flicker between the two
                // Roblox hands); a new hand is the side of the screen it is on — left on the
                // left, right on the right. MediaPipe's per-frame label is not trusted.
                float dl = now - leftSeenMs < 500 ? (float) Math.hypot(sx - leftSx, sy - leftSy) : 9f;
                float dr = now - rightSeenMs < 500 ? (float) Math.hypot(sx - rightSx, sy - rightSy) : 9f;
                if (Math.min(dl, dr) < 0.2f) isLeft[i] = dl <= dr;
                else isLeft[i] = sx < 0.5f;
            }
        }
        for (int i = 0; i < found.length; i++) {
            if (found[i] == null) continue;
            if (isLeft[i]) { leftSx = screenX(found[i], rotation); leftSy = screenY(found[i], rotation); leftSeenMs = now; }
            else { rightSx = screenX(found[i], rotation); rightSy = screenY(found[i], rotation); rightSeenMs = now; }
        }
        float[][] marks = new float[found.length][];
        for (int i = 0; i < found.length; i++) {
            if (found[i] == null) continue;
            marks[i] = found[i].landmarks;
            Pose target = isLeft[i] ? result.left : result.right;
            if (target.active) continue;
            copy(found[i], target);
        }
        lastLandmarks = marks;
        lastIsLeft = isLeft;
        return result;
    }

    /** Port of PhoneXR HandGestures.shape (fist, pinch gap) and pinchGap3d. */
    private static void shape(Pose pose, List<NormalizedLandmark> p,
                              List<com.google.mediapipe.tasks.components.containers.Landmark> world) {
        float palm = Math.max(.02f, d(p, 5, 17));
        pose.pinchGap = d(p, 4, 8) / palm;
        int others = 0;
        if (d(p, 0, 12) < d(p, 0, 10) * 1.04f) others++;
        if (d(p, 0, 16) < d(p, 0, 14) * 1.04f) others++;
        if (d(p, 0, 20) < d(p, 0, 18) * 1.04f) others++;
        pose.fist = d(p, 0, 8) < d(p, 0, 6) * 1.04f && others >= 3;
        // Metric landmarks give the same gap however the hand is turned.
        if (world != null && world.size() >= 21) {
            float palm3 = d3(world, 5, 17);
            if (palm3 > 1e-3f) pose.pinchGap = d3(world, 4, 8) / palm3 * .8f;
        }
    }

    private static float d(List<NormalizedLandmark> p, int a, int b) {
        float dx = p.get(a).x() - p.get(b).x(), dy = p.get(a).y() - p.get(b).y();
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static float d3(List<com.google.mediapipe.tasks.components.containers.Landmark> w, int a, int b) {
        float dx = w.get(a).x() - w.get(b).x(), dy = w.get(a).y() - w.get(b).y(), dz = w.get(a).z() - w.get(b).z();
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Last screen position of each side, for keeping a single hand on its side. */
    private float leftSx, leftSy, rightSx, rightSy;
    private long leftSeenMs, rightSeenMs;

    private static float screenY(Pose pose, int rotation) {
        switch (rotation) {
            case 90: return pose.x;
            case 180: return 1f - pose.y;
            case 270: return 1f - pose.x;
            default: return pose.y;
        }
    }

    private static float screenX(Pose pose, int rotation) {
        switch (rotation) {
            case 90: return 1f - pose.y;
            case 180: return 1f - pose.x;
            case 270: return pose.y;
            default: return pose.x;
        }
    }

    private static void copy(Pose from, Pose to) {
        to.active = true;
        to.x = from.x; to.y = from.y; to.angle = from.angle; to.scale = from.scale;
        to.landmarks = from.landmarks;
        to.pinchGap = from.pinchGap; to.fist = from.fist;
    }

    private void yuvToArgb(Image image, int width, int height, int step) {
        Image.Plane[] p = image.getPlanes();
        // Bulk copies: per-pixel ByteBuffer.get() is the slow part of the old path.
        yBytes = copy(p[0].getBuffer(), yBytes);
        uBytes = copy(p[1].getBuffer(), uBytes);
        vBytes = copy(p[2].getBuffer(), vBytes);
        byte[] ya = yBytes, ua = uBytes, va = vBytes;
        int[] out = pixels;
        int yr = p[0].getRowStride(), ur = p[1].getRowStride(), vr = p[2].getRowStride();
        int up = p[1].getPixelStride(), vp = p[2].getPixelStride();
        for (int y = 0; y < height; y++) {
            int sy = y * step;
            int yi = sy * yr, ui = (sy >> 1) * ur, vi = (sy >> 1) * vr, o = y * width;
            for (int x = 0; x < width; x++) {
                int sx = x * step;
                int yy = (ya[yi + sx] & 255) << 10;
                int u = (ua[ui + (sx >> 1) * up] & 255) - 128;
                int v = (va[vi + (sx >> 1) * vp] & 255) - 128;
                int r = (yy + 1436 * v) >> 10;
                int g = (yy - 352 * u - 731 * v) >> 10;
                int b = (yy + 1815 * u) >> 10;
                out[o + x] = 0xff000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
            }
        }
    }

    private static byte[] copy(ByteBuffer buffer, byte[] into) {
        buffer.rewind();
        int n = buffer.remaining();
        if (into == null || into.length < n) into = new byte[n];
        buffer.get(into, 0, n);
        return into;
    }

    private static int clamp(int value) { return value < 0 ? 0 : Math.min(value, 255); }

    @Override public void close() { landmarker.close(); }
}
