package com.wusper.x86.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 引擎二進位落地。
 *
 * **Android noexec**：`filesDir`／`codeCacheDir` 常不可 exec。
 * 宿主 `proot`／`loader`／依賴 `.so` **只**從 [Context.getApplicationInfo] 的
 * `nativeLibraryDir`（jniLibs：`libproot.so`／`libprootloader.so`／`libtalloc.so`／…）啟動。
 * `PROOT_TMP_DIR` 可放 codeCache（只寫暫存，不 exec）。
 */
object EngineAssets {
    private const val TAG = "EngineAssets"
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
            hostArm64 = copyAsset(ctx, "$TESTDATA/hello_host_arm64", File(root, "hello_host_arm64"), true),
            guestLinuxX86_64 = copyAsset(ctx, "$TESTDATA/hello_x86_64", File(root, "hello_x86_64"), true),
            guestAndroidX86_64 = copyAsset(ctx, "$TESTDATA/hello_android_x86_64", File(root, "hello_android_x86_64"), true),
            microbenchHostArm64 = copyAsset(ctx, "$TESTDATA/microbench_host_arm64", File(root, "microbench_host_arm64"), true),
            microbenchAndroidX86_64 = copyAsset(
                ctx, "$TESTDATA/microbench_android_x86_64", File(root, "microbench_android_x86_64"), true,
            ),
        )
    }

    fun ensureBox64(ctx: Context): File {
        // 宿主 box64 若用於 smoke：優先 native；否則 assets→勿當主路徑 exec
        val native = File(ctx.applicationInfo.nativeLibraryDir, "libbox64.so")
        if (native.isFile) {
            ExecBits.force(native)
            return native
        }
        val dest = File(engineRoot(ctx), "box64")
        return copyAsset(ctx, "engine/box64", dest, executable = true)
    }

    fun findBox64(ctx: Context): File? {
        val native = File(ctx.applicationInfo.nativeLibraryDir, "libbox64.so")
        if (native.isFile) {
            ExecBits.force(native)
            return native
        }
        val ensured = runCatching { ensureBox64(ctx) }.getOrNull()
        if (ensured != null && ensured.exists()) {
            ExecBits.force(ensured)
            return ensured
        }
        return null
    }

    data class ProotBundle(
        /** 必須位於 nativeLibraryDir，可 exec */
        val proot: File,
        val loader: File,
        val loader32: File?,
        /** 同為 nativeLibraryDir（libtalloc／shmem） */
        val libDir: File,
        /** 可寫暫存；不必可 exec */
        val tmpDir: File,
        val rootDir: File,
    ) {
        fun envVars(): List<String> {
            val lib = libDir.absolutePath
            return buildList {
                add("PROOT_TMP_DIR=${tmpDir.absolutePath}")
                add("PROOT_LOADER=${loader.absolutePath}")
                add("LD_LIBRARY_PATH=$lib")
                add("PROOT_NO_SECCOMP=1")
                add("TMPDIR=${tmpDir.absolutePath}")
                add("TMP=${tmpDir.absolutePath}")
                add("TEMP=${tmpDir.absolutePath}")
                // 清掉可能的 termux-exec 等
                add("LD_PRELOAD=")
                if (loader32 != null && loader32.isFile) {
                    add("PROOT_LOADER_32=${loader32.absolutePath}")
                }
            }
        }
    }

    /** 僅暫存；不在此放置可執行檔 */
    fun prootTmpDir(ctx: Context): File =
        File(ctx.codeCacheDir, "proot-tmp").also { dir ->
            dir.mkdirs()
            dir.setReadable(true, false)
            dir.setWritable(true, false)
            // 目錄需可遍歷（x bit on dir ≠ 執行檔）
            dir.setExecutable(true, false)
        }

    /**
     * 宿主 proot 包：執行檔／loader／.so **只**用 jniLibs→nativeLibraryDir。
     * 禁止複製到 code_cache／files 再 exec（Android noexec → EACCES 13）。
     */
    fun ensureProotBundle(ctx: Context): ProotBundle {
        val nlib = File(ctx.applicationInfo.nativeLibraryDir)
        require(nlib.isDirectory) {
            "nativeLibraryDir 不存在：${nlib.absolutePath}（jniLibs 未打包？）"
        }

        val proot = File(nlib, "libproot.so")
        val loader = File(nlib, "libprootloader.so")
        val loader32 = File(nlib, "libprootloader32.so").takeIf { it.isFile }
        val talloc = File(nlib, "libtalloc.so")
        val shmem = File(nlib, "libandroid-shmem.so")

        fun need(f: File, label: String) {
            if (!f.isFile || f.length() < 1000L) {
                throw IllegalStateException(
                    "缺 $label：${f.absolutePath}（請確認 jniLibs/arm64-v8a 已打進 APK）",
                )
            }
            ExecBits.force(f)
        }
        need(proot, "libproot.so")
        need(loader, "libprootloader.so")
        need(talloc, "libtalloc.so")
        need(shmem, "libandroid-shmem.so")
        loader32?.let { ExecBits.force(it) }

        if (!proot.canExecute()) {
            Log.w(TAG, "libproot.so canExecute=false（仍嘗試 exec；應在 nativeLibraryDir）")
        }

        val tmp = prootTmpDir(ctx)
        // 清殘留抽出的 loader 碎片（舊版誤寫入）
        tmp.listFiles()?.forEach { f ->
            if (f.name.startsWith(".proot-") || f.name.contains("loader")) {
                runCatching { f.delete() }
            }
        }
        // 順便清掉舊版誤放在 code_cache 的可執行複本（避免誤用）
        runCatching {
            File(ctx.codeCacheDir, "proot-bundle").takeIf { it.exists() }?.let { stale ->
                Log.i(TAG, "removing stale noexec proot-bundle at ${stale.absolutePath}")
                stale.deleteRecursively()
            }
            File(ctx.codeCacheDir, "proot").takeIf { it.isFile }?.delete()
        }

        Log.i(
            TAG,
            "proot from nativeLibraryDir proot=${proot.absolutePath} " +
                "loader=${loader.absolutePath} lib=$nlib tmp=${tmp.absolutePath} " +
                "canExec=${proot.canExecute()} loaderExec=${loader.canExecute()}",
        )
        return ProotBundle(
            proot = proot,
            loader = loader,
            loader32 = loader32,
            libDir = nlib,
            tmpDir = tmp,
            rootDir = nlib,
        )
    }

    fun ensureProot(ctx: Context): File = ensureProotBundle(ctx).proot

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
        if (executable) ExecBits.force(dest)
        return dest
    }
}
