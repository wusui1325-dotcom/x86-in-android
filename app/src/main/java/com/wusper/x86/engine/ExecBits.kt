package com.wusper.x86.engine

import android.system.Os
import android.util.Log
import java.io.File

/**
 * 強制可執行位：File.setExecutable 在部分 OEM／SELinux 下會靜默失敗；
 * 改走 Os.chmod(0700／0755)。並掃描常見引擎二進位。
 */
object ExecBits {
    private const val TAG = "ExecBits"

    fun force(file: File, mode: Int = 0b111_101_101 /* 0755 */): Boolean {
        if (!file.exists() || !file.isFile) return false
        // 先試 Java API
        runCatching {
            file.setReadable(true, false)
            file.setExecutable(true, false)
        }
        return try {
            Os.chmod(file.absolutePath, mode)
            file.canExecute() || mode and 0b001_001_001 != 0
        } catch (t: Throwable) {
            Log.w(TAG, "chmod ${file.name}: ${t.message}")
            file.canExecute()
        }
    }

    fun forceTree(root: File, names: Set<String> = ENGINE_BIN_NAMES) {
        if (!root.exists()) return
        root.walkTopDown().maxDepth(8).forEach { f ->
            if (!f.isFile) return@forEach
            if (f.name in names || f.extension in setOf("so", "sh") ||
                f.name.startsWith("lib") && f.name.contains("proot")
            ) {
                force(f)
            }
        }
    }

    /** 引擎安裝後必 chmod 的路徑 */
    fun forceEngineBins(ctxFilesEngine: File, runtimeRoot: File) {
        force(File(ctxFilesEngine, "proot"))
        force(File(ctxFilesEngine, "box64"))
        val rootfs = File(runtimeRoot, "rootfs")
        listOf(
            "usr/local/bin/box64",
            "usr/local/bin/wine-launch.sh",
            "usr/local/bin/x11-input-agent.sh",
            "opt/wine/bin/wine",
            "opt/wine/bin/wine64",
            "opt/wine/bin/wineserver",
            "usr/bin/Xvfb",
            "usr/bin/xdotool",
            "usr/bin/dash",
            "usr/bin/bash",
            "usr/bin/sh",
            "bin/bash",
            "bin/sh",
            "lib/ld-linux-aarch64.so.1",
            "usr/lib/ld-linux-aarch64.so.1",
            "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
        ).forEach { force(File(rootfs, it)) }
        // wine 目錄下其他可執行
        File(rootfs, "opt/wine/bin").listFiles()?.forEach { if (it.isFile) force(it) }
        File(rootfs, "usr/local/bin").listFiles()?.forEach { if (it.isFile) force(it) }
    }

    private val ENGINE_BIN_NAMES = setOf(
        "proot", "box64", "wine", "wine64", "wineserver", "Xvfb", "xdotool",
        "bash", "sh", "wine-launch.sh", "x11-input-agent.sh",
    )
}
