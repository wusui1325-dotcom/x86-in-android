package com.wusper.x86.engine

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * 把 assets／jniLibs 引擎二進位落到可執行路徑。
 * proot 優先用 nativeLibraryDir/libproot.so（Android 可執行區；避開 filesDir EACCES）。
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
            File(ctx.applicationInfo.nativeLibraryDir, "libbox64.so"),
            File(ctx.applicationInfo.nativeLibraryDir, "box64"),
        )
        return candidates.firstOrNull { it.exists() }.also { it?.let { f -> ExecBits.force(f) } }
            ?.takeIf { it.canExecute() || it.exists() }
    }

    /**
     * 宿主 proot：1) jniLibs libproot.so  2) assets→files/engine/proot＋Os.chmod
     */
    fun ensureProot(ctx: Context): File {
        val native = File(ctx.applicationInfo.nativeLibraryDir, "libproot.so")
        if (native.isFile && native.length() > 10_000L) {
            ExecBits.force(native)
            if (native.canExecute() || native.exists()) {
                // 同步一份到 files（腳本／除錯）；執行優先回傳 native 路徑
                val mirror = File(engineRoot(ctx), "proot")
                if (!mirror.exists() || mirror.length() != native.length()) {
                    runCatching {
                        native.copyTo(mirror, overwrite = true)
                        ExecBits.force(mirror)
                    }
                } else {
                    ExecBits.force(mirror)
                }
                return native
            }
        }
        val dest = File(engineRoot(ctx), "proot")
        copyAsset(ctx, "engine/proot", dest, executable = true)
        ExecBits.force(dest, 0b111_000_000) // 0700
        if (!dest.canExecute()) {
            // 最後手段：再拷到 codeCacheDir（部分機型可執行）
            val alt = File(ctx.codeCacheDir, "proot").also { it.parentFile?.mkdirs() }
            dest.copyTo(alt, overwrite = true)
            ExecBits.force(alt, 0b111_000_000)
            if (alt.canExecute()) return alt
        }
        return dest
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
            ExecBits.force(dest)
        }
        return dest
    }
}
