package com.wusper.x86.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 引擎二進位落地。
 *
 * proot 對齊 Termux／Winlator 可用組合：
 * `proot` + `libexec/proot/loader{,32}` + `libtalloc.so.2` + `libandroid-shmem.so`
 * 部署到 [codeCacheDir]（可寫可 exec），設 PROOT_LOADER／LD_LIBRARY_PATH／PROOT_TMP_DIR。
 */
object EngineAssets {
    private const val TAG = "EngineAssets"
    private const val ENGINE_DIR = "engine"
    private const val TESTDATA = "testdata"
    private const val BUNDLE_ASSET = "engine/proot-bundle"

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
        val dest = File(engineRoot(ctx), "box64")
        return copyAsset(ctx, "engine/box64", dest, executable = true)
    }

    fun findBox64(ctx: Context): File? {
        val ensured = runCatching { ensureBox64(ctx) }.getOrNull()
        if (ensured != null && ensured.exists()) {
            ExecBits.force(ensured)
            return ensured
        }
        return null
    }

    data class ProotBundle(
        val proot: File,
        val loader: File,
        val loader32: File?,
        val libDir: File,
        val tmpDir: File,
        val rootDir: File,
    ) {
        fun envVars(): List<String> {
            return listOf(
                "PROOT_TMP_DIR=${tmpDir.absolutePath}",
                "PROOT_LOADER=${loader.absolutePath}",
                "LD_LIBRARY_PATH=${libDir.absolutePath}",
                "PROOT_NO_SECCOMP=1",
                "TMPDIR=${tmpDir.absolutePath}",
                "TMP=${tmpDir.absolutePath}",
                "TEMP=${tmpDir.absolutePath}",
            ).let { list ->
                if (loader32 != null && loader32.exists()) {
                    list + "PROOT_LOADER_32=${loader32.absolutePath}"
                } else list
            }
        }
    }

    fun prootTmpDir(ctx: Context): File =
        File(ctx.codeCacheDir, "proot-tmp").also { dir ->
            dir.mkdirs()
            dir.setReadable(true, false)
            dir.setWritable(true, false)
            dir.setExecutable(true, false)
        }

    fun ensureProotBundle(ctx: Context): ProotBundle {
        val root = File(ctx.codeCacheDir, "proot-bundle").also { it.mkdirs() }
        val wantVer = runCatching {
            ctx.assets.open("$BUNDLE_ASSET/VERSION").bufferedReader().readText().trim()
        }.getOrDefault("")
        val haveVer = File(root, "VERSION").takeIf { it.exists() }?.readText()?.trim().orEmpty()
        if (wantVer.isNotBlank() && wantVer != haveVer) {
            root.deleteRecursively()
            root.mkdirs()
        }

        val proot = copyAsset(ctx, "$BUNDLE_ASSET/proot", File(root, "proot"), true)
        val loader = copyAsset(
            ctx, "$BUNDLE_ASSET/libexec/proot/loader",
            File(root, "libexec/proot/loader"), true,
        )
        val loader32 = runCatching {
            copyAsset(
                ctx, "$BUNDLE_ASSET/libexec/proot/loader32",
                File(root, "libexec/proot/loader32"), true,
            )
        }.getOrNull()
        val libDir = File(root, "lib").also { it.mkdirs() }
        copyAsset(ctx, "$BUNDLE_ASSET/lib/libtalloc.so.2", File(libDir, "libtalloc.so.2"), true)
        copyAsset(ctx, "$BUNDLE_ASSET/lib/libandroid-shmem.so", File(libDir, "libandroid-shmem.so"), true)
        // soname 相容
        val tallocLink = File(libDir, "libtalloc.so")
        if (!tallocLink.exists()) {
            runCatching {
                java.nio.file.Files.createSymbolicLink(
                    tallocLink.toPath(),
                    java.nio.file.Paths.get("libtalloc.so.2"),
                )
            }.onFailure {
                File(libDir, "libtalloc.so.2").copyTo(tallocLink, overwrite = true)
            }
        }
        if (wantVer.isNotBlank()) File(root, "VERSION").writeText(wantVer + "\n")

        // files 鏡像（舊路徑／除錯）
        val mirror = File(engineRoot(ctx), "proot")
        proot.copyTo(mirror, overwrite = true)
        ExecBits.force(mirror)

        val tmp = prootTmpDir(ctx)
        // 清掉殘留壞 loader
        tmp.listFiles()?.forEach { f ->
            if (f.name.startsWith(".proot-") || f.name.contains("loader")) {
                runCatching { f.delete() }
            }
        }

        ExecBits.force(proot, 0b111_000_000)
        ExecBits.force(loader, 0b111_000_000)
        loader32?.let { ExecBits.force(it, 0b111_000_000) }
        File(libDir, "libtalloc.so.2").let { ExecBits.force(it) }
        File(libDir, "libandroid-shmem.so").let { ExecBits.force(it) }

        Log.i(
            TAG,
            "proot bundle ready proot=${proot.absolutePath} loader=${loader.absolutePath} " +
                "lib=${libDir.absolutePath} tmp=${tmp.absolutePath} canExec=${proot.canExecute()}",
        )
        return ProotBundle(proot, loader, loader32, libDir, tmp, root)
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
