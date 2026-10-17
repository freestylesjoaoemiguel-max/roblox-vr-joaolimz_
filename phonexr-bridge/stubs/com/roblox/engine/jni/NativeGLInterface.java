package com.roblox.engine.jni;

public final class NativeGLInterface {
    public static native boolean isVRVirtualInputFlagEnabled();
    public static native void enableVRVirtualInput(boolean enabled);
    public static native void setVRCameraCFrame(float x, float y, float z);
    public static native void setVRWorldRayCFrameFromScreenPosition(int id, float x, float y, int side);
    public static native void setVirtualVRJoyStickPosition(float x, float y);
    public static native void syncTextboxTextAndCursorPosition2(String text, int pos);
    public static native void nativePassText(long textBoxId, String text, boolean enterPressed, int flags);
}
