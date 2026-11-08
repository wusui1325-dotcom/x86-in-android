package com.wusper.x86.engine

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * 把 assets/testdata 與可選引擎二進位解到 files/engine/。
 */
object EngineAssets {
    private const val ENGINE_DIR = "engine"
    private const val TESTDATA = "testdata"

    fun engineRoot(ctx: Context): File =
        File(ctx.filesDir, ENGINE_DIR).also { it.mkdirs() }

    fun ensureTestBinaries(ctx: Context): Pair<File, File> {
        val root = File(engineRoot(ctx), TESTDATA).also { it.mkdirs() }
        val host = copyAsset(ctx, "$TESTDATA/hello_host_arm64", File(root, "hello_host_arm64"), executable = true)
        val guest = copyAsset(ctx, "$TESTDATA/hello_x86_64", File(root, "hello_x86_64"), executable = true)
        return host to guest
    }

    /** 尋找 Box64：jniLibs 抽出的 nativeLibraryDir，或 files/engine/box64 */
    fun findBox64(ctx: Context): File? {
        val candidates = listOf(
            File(engineRoot(ctx), "box64"),
            File(ctx.applicationInfo.nativeLibraryDir, "box64"),
            File(ctx.applicationInfo.nativeLibraryDir, "libbox64.so"),
        )
        return candidates.firstOrNull { it.exists() && it.canExecute() }
    }

    private fun copyAsset(ctx: Context, assetPath: String, dest: File, executable: Boolean): File {
        if (dest.exists() && dest.length() > 0L) {
            if (executable) dest.setExecutable(true, false)
            return dest
        }
        ctx.assets.open(assetPath).use { input ->
            FileOutputStream(dest).use { output -> input.copyTo(output) }
        }
        if (executable) dest.setExecutable(true, false)
        return dest
    }
}
