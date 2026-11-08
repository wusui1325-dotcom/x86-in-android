package com.wusper.x86.engine

import android.view.Surface
import java.nio.ByteBuffer

object NativeEngine {
    // @JvmStatic so Java (PresentXFramePump / Winlator bridge) can call
    init {
        System.loadLibrary("x86engine")
    }

    const val KIND_UNKNOWN = 0
    const val KIND_PE = 1
    const val KIND_ELF_X86_64 = 2
    const val KIND_ELF_X86 = 3
    const val KIND_ELF_AARCH64 = 4
    const val KIND_ELF_OTHER = 5

    @JvmStatic
    external fun nativeProbe(path: String): Int
    @JvmStatic
    external fun nativeProbeMessage(path: String): String
    @JvmStatic
    external fun nativeExec(
        argv: Array<String>,
        cwd: String,
        logPath: String,
        env: Array<String>?,
    ): Int
    @JvmStatic
    external fun nativeStop(): Int
    @JvmStatic
    external fun nativeRunningPid(): Int
    /** 1=仍在跑；0=無進程；負數=已結束（編碼見 native） */
    @JvmStatic
    external fun nativeWaitNonBlocking(): Int

    // —— GpuStack Present（AHB → ANativeWindow）——
    @JvmStatic
    external fun nativePresentCreate(width: Int, height: Int): Int
    @JvmStatic
    external fun nativePresentAttachSurface(surface: Surface): Int
    @JvmStatic
    external fun nativePresentDetach()
    @JvmStatic
    external fun nativePresentDestroy()
    @JvmStatic
    external fun nativePresentRgba(rgbaDirect: ByteBuffer, width: Int, height: Int, stride: Int): Int
    /** Drawable／MIT-SHM BGRA → 單趟進 Surface（無 Java swizzle） */
    @JvmStatic
    external fun nativePresentBgra(bgraDirect: ByteBuffer, width: Int, height: Int, stride: Int): Int
    /** Winlator GPUImage／DRI3 AHB → EGL GPU_SAMPLED Present */
    @JvmStatic
    external fun nativePresentAhb(ahbPtr: Long, width: Int, height: Int): Int
    @JvmStatic
    external fun nativePresentStub(frameIndex: Int): Int
    @JvmStatic
    external fun nativePresentReady(): Boolean
    @JvmStatic
    external fun nativePresentEglReady(): Boolean
}
