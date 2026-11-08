package com.wusper.x86.engine

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * 把 assets/testdata 與引擎二進位解到 files/engine/。
 */
object EngineAssets {
    private const val ENGINE_DIR = "engine"
    private const val TESTDATA = "testdata"

    fun engineRoot(ctx: Context): File =
        File(ctx.filesDir, ENGINE_DIR).also { it.mkdirs() }

    data class TestBinaries(
        val hostArm64: File,
        val guestLinuxX86_64: File,
        val guestAndroidX86_64: File,
        val microbenchHostArm64: File,
        val microbenchAndroidX86_64: File,
    )

    fun ensureTestBinaries(ctx: Context): TestBinaries {
        val root = File(engineRoot(ctx), TESTDATA).also { it.mkdirs() }
        return TestBinaries(
            hostArm64 = copyAsset(ctx, "$TESTDATA/hello_host_arm64", File(root, "hello_host_arm64"), executable = true),
            guestLinuxX86_64 = copyAsset(ctx, "$TESTDATA/hello_x86_64", File(root, "hello_x86_64"), executable = true),
            guestAndroidX86_64 = copyAsset(
                ctx,
                "$TESTDATA/hello_android_x86_64",
                File(root, "hello_android_x86_64"),
                executable = true,
            ),
            microbenchHostArm64 = copyAsset(
                ctx,
                "$TESTDATA/microbench_host_arm64",
                File(root, "microbench_host_arm64"),
                executable = true,
            ),
            microbenchAndroidX86_64 = copyAsset(
                ctx,
                "$TESTDATA/microbench_android_x86_64",
                File(root, "microbench_android_x86_64"),
                executable = true,
            ),
        )
    }

    fun ensureBox64(ctx: Context): File {
        val dest = File(engineRoot(ctx), "box64")
        return copyAsset(ctx, "engine/box64", dest, executable = true)
    }

    fun findBox64(ctx: Context): File? {
        val ensured = runCatching { ensureBox64(ctx) }.getOrNull()
        if (ensured != null && ensured.exists() && ensured.canExecute()) return ensured
        val candidates = listOf(
            File(engineRoot(ctx), "box64"),
            File(ctx.applicationInfo.nativeLibraryDir, "box64"),
            File(ctx.applicationInfo.nativeLibraryDir, "libbox64.so"),
        )
        return candidates.firstOrNull { it.exists() && it.canExecute() }
    }

    private fun copyAsset(ctx: Context, assetPath: String, dest: File, executable: Boolean): File {
        val assetLen = runCatching {
            ctx.assets.openFd(assetPath).use { it.length }
        }.getOrElse { -1L }
        val needCopy = !dest.exists() || dest.length() == 0L ||
            (assetLen > 0L && dest.length() != assetLen)
        if (needCopy) {
            dest.parentFile?.mkdirs()
            ctx.assets.open(assetPath).use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
        }
        if (executable) {
            dest.setReadable(true, false)
            dest.setExecutable(true, false)
        }
        return dest
    }
}
