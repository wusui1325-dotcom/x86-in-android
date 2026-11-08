package com.wusper.x86.engine

import android.view.Surface
import java.nio.ByteBuffer

object NativeEngine {
    init {
        System.loadLibrary("x86engine")
    }

    const val KIND_UNKNOWN = 0
    const val KIND_PE = 1
    const val KIND_ELF_X86_64 = 2
    const val KIND_ELF_X86 = 3
    const val KIND_ELF_AARCH64 = 4
    const val KIND_ELF_OTHER = 5

    external fun nativeProbe(path: String): Int
    external fun nativeProbeMessage(path: String): String
    external fun nativeExec(
        argv: Array<String>,
        cwd: String,
        logPath: String,
        env: Array<String>?,
    ): Int
    external fun nativeStop(): Int
    external fun nativeRunningPid(): Int
    /** 1=仍在跑；0=無進程；負數=已結束（編碼見 native） */
    external fun nativeWaitNonBlocking(): Int

    // —— GpuStack Present（AHB → ANativeWindow）——
    external fun nativePresentCreate(width: Int, height: Int): Int
    external fun nativePresentAttachSurface(surface: Surface): Int
    external fun nativePresentDetach()
    external fun nativePresentDestroy()
    external fun nativePresentRgba(rgbaDirect: ByteBuffer, width: Int, height: Int, stride: Int): Int
    external fun nativePresentStub(frameIndex: Int): Int
    external fun nativePresentReady(): Boolean
}
