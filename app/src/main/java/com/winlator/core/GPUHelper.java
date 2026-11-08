package com.winlator.core;

import java.util.Locale;

/** Slimmed vendor of Winlator GPUHelper — only EGL/Vulkan helpers needed by X Present/DRI3. */
public abstract class GPUHelper {
    public enum VkPresentMode {
        IMMEDIATE, MAILBOX, FIFO, FIFO_RELAXED;
        public String value() { return name().toLowerCase(Locale.ENGLISH); }
    }
    public static int VK_API_VERSION_1_3 = vkMakeVersion(1, 3, 0);

    static { System.loadLibrary("winlator"); }

    public static int vkMakeVersion(int major, int minor, int patch) {
        return (major << 22) | (minor << 12) | patch;
    }

    public static native String[] vkGetDeviceExtensions();
    public static native int vkGetApiVersion();
    public static native void setGlobalEGLContext();
    public static native long createOffscreenEGLContext(boolean gles3);
    public static native void destroyOffscreenEGLContext(long ptr);
}
