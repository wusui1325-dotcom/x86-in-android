package com.wusper.x86.engine

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Winlator／glibc 級 GPU 棧接縫：Turnip ICD＋DXVK＋Present→AHardwareBuffer→Session Surface。
 *
 * 合法重用（見 docs/THIRD_PARTY.md、docs/GPU_STACK.md）：
 * - Mesa Turnip **glibc／KGSL ICD**：MIT（Winlator 等價預編；PRoot glibc 客體直載）
 * - DXVK：Zlib（doitsujin 官方 release）
 * - 參考架構：brunodev85/winlator（LGPL-2.1）— 不整樹 fork
 * - nihui bionic／adrenotools／Vortek IPC：另檔（本輪不做；bionic .so 不能給 glibc Wine dlopen）
 */
object GpuStack {

    const val PRESENTATION_READY: Boolean = true
    const val INPROCESS_X_READY: Boolean = true
    /** AHB GPU_SAMPLED＋EGL／BGRA 單趟；完整 Vortek IPC 另檔 */
    const val DRI3_ZEROCOPY_READY: Boolean = true
    /** glibc Turnip ICD 下載／bind／VK_ICD_FILENAMES 通路已接 */
    const val TURNIP_ICD_READY: Boolean = true
    /** GLX＋gladiorenderer＋客體 libGL（Winlator Gladio） */
    const val GLX_GLADIO_READY: Boolean = true
    /** ALSA android_aserver → 宿主 AAudio */
    const val AAUDIO_READY: Boolean = true
    const val WINHANDLER_INPUT_READY: Boolean = true

    /** Winlator 等價 glibc Turnip（KGSL）；非 nihui bionic zip */
    const val TURNIP_VERSION = "25.1.0-R2"
    const val DXVK_VERSION = "2.7.1"

    private const val DXVK_URL =
        "https://github.com/doitsujin/dxvk/releases/download/v2.7.1/dxvk-2.7.1.tar.gz"
    private const val TURNIP_URL =
        "https://github.com/K11MCH1/WinlatorTurnipDrivers/releases/download/winlator_v25.1_r2/libvulkan_freedreno.so"
    private const val TURNIP_SHA256 =
        "fe222ea204d5ac312eae2955da4a7b78c087009f28403edceec73e4ed1ae64da"
    private const val TURNIP_SO_NAME = "libvulkan_freedreno.so"
    private const val TURNIP_ICD_NAME = "freedreno_icd.json"

    fun stackRoot(ctx: Context): File =
        File(ctx.filesDir, "engine/gpu-stack").also { it.mkdirs() }

    fun turnipDir(ctx: Context): File = File(stackRoot(ctx), "turnip-$TURNIP_VERSION")
    fun dxvkDir(ctx: Context): File = File(stackRoot(ctx), "dxvk-$DXVK_VERSION")
    fun turnipSo(ctx: Context): File = File(turnipDir(ctx), TURNIP_SO_NAME)
    fun turnipIcd(ctx: Context): File = File(turnipDir(ctx), TURNIP_ICD_NAME)

    fun hasTurnip(ctx: Context): Boolean {
        val so = turnipSo(ctx)
        val icd = turnipIcd(ctx)
        return so.isFile && so.length() > 1_000_000L && icd.isFile
    }

    fun hasDxvk(ctx: Context): Boolean {
        val x64 = File(dxvkDir(ctx), "x64")
        return File(x64, "d3d11.dll").exists() && File(x64, "dxgi.dll").exists()
    }

    fun componentsInstalled(ctx: Context): Boolean = hasTurnip(ctx) && hasDxvk(ctx)

    /** 可對使用者顯示的缺口（繁中） */
    fun gaps(ctx: Context): List<String> {
        val g = mutableListOf<String>()
        if (!hasTurnip(ctx)) {
            g += "缺 Mesa Turnip glibc ICD（$TURNIP_VERSION；KGSL／Adreno）"
        }
        if (!hasDxvk(ctx)) {
            g += "缺 DXVK $DXVK_VERSION（d3d8/9/10/11→Vulkan DLL）"
        }
        if (!PRESENTATION_READY) {
            g += "缺 Present→AHardwareBuffer→Session Surface"
        }
        if (!DRI3_ZEROCOPY_READY) {
            g += "缺 DRI3／AHB GPU Present"
        }
        g += "缺 Vortek／adrenotools 通路（Mali／非 KGSL；現為 glibc Turnip 直載）"
        if (!AAUDIO_READY) {
            g += "缺 ALSA→AAudio 真輸出"
        }
        if (!GLX_GLADIO_READY) {
            g += "缺 GLX／gladiorenderer（OpenGL 客體）"
        }
        // WinHandler 級觸控／相對滑鼠／右鍵／滾輪／鍵位手把已接；完整 dinput UDP 另項
        g += "缺完整 dinput 手把 UDP（winhandler.dll；現為鍵位映射）"
        return g
    }

    fun canLaunchGpuSession(ctx: Context): Boolean =
        PRESENTATION_READY && componentsInstalled(ctx)

    fun statusMessage(ctx: Context): String {
        val parts = mutableListOf<String>()
        parts += if (hasTurnip(ctx)) "Turnip✓" else "Turnip✗"
        parts += if (hasDxvk(ctx)) "DXVK✓" else "DXVK✗"
        parts += if (PRESENTATION_READY) "Present✓" else "Present✗"
        parts += if (INPROCESS_X_READY) "X✓（Winlator）" else "X✗"
        parts += "Input✓（WinHandler）"
        parts += if (DRI3_ZEROCOPY_READY) "DRI3✓" else "DRI3✗"
        parts += if (TURNIP_ICD_READY && hasTurnip(ctx)) "ICD✓（glibc）" else "ICD✗"
        parts += if (GLX_GLADIO_READY) "GLX✓（Gladio）" else "GLX✗"
        parts += if (AAUDIO_READY) "Audio✓（AAudio）" else "Audio✗"
        return parts.joinToString(" · ")
    }

    /** 寫入客體／啟動腳本的環境（GpuStack 路徑；不含軟渲） */
    fun guestEnv(ctx: Context, wsDataAbs: String): List<String> {
        val env = mutableListOf(
            "GRAPHICS_BACKEND=gpu",
            "LIBGL_ALWAYS_SOFTWARE=0",
            "GALLIUM_DRIVER=",
            "WINE_DESKTOP_W=1280",
            "WINE_DESKTOP_H=720",
        )
        if (hasTurnip(ctx)) {
            env += "HOST_TURNIP_DIR=${turnipDir(ctx).absolutePath}"
            env += "VK_ICD_FILENAMES=/mnt/gpu/turnip/$TURNIP_ICD_NAME"
            env += "VK_DRIVER_FILES=/mnt/gpu/turnip/$TURNIP_ICD_NAME"
            env += "TU_DEBUG=noconform"
            env += "X86_TURNIP_ICD=glibc_kgsl"
        }
        val dxvk = dxvkDir(ctx)
        if (dxvk.isDirectory) {
            env += "HOST_DXVK_DIR=${dxvk.absolutePath}"
            // Audio／DXVK override 於下方合併
        }
        env += "X86_GPU_STATUS=${statusMessage(ctx)}"
        env += "X86_WS_DATA=$wsDataAbs"
        env += "PRESENT_SHM=/mnt/data/present.shm"
        env += "X_PROTOCOL=inprocess_winlator"
        if (GLX_GLADIO_READY) {
            env += "X86_GLX=gladio"
            env += "LD_LIBRARY_PATH=/opt/gladio/lib:/mnt/gpu/turnip"
        }
        if (AAUDIO_READY) {
            env += "ANDROID_ALSA_SERVER=/tmp/.sound/AS0"
            env += "ANDROID_ASERVER_USE_SHM=true"
            env += "ALSA_PLUGIN_DIR=/usr/lib/aarch64-linux-gnu/alsa-lib"
            env += "ALSA_CONFIG_PATH=/usr/share/alsa/alsa.conf"
            // 啟用 winealsa；關掉 winepulse
            env += "WINEDLLOVERRIDES=d3d8,d3d9,d3d10core,d3d11,dxgi=n,b;winepulse.drv=d;winealsa.drv=b"
            env += "X86_AUDIO=alsa_aaudio"
        }
        return env
    }

    /**
     * 下載 Turnip＋DXVK 到 app 私有目錄（不進 APK）。
     */
    fun installComponents(ctx: Context, onProgress: (String) -> Unit = {}): Result<Unit> {
        return runCatching {
            val cache = File(stackRoot(ctx), "cache").also { it.mkdirs() }
            if (!hasTurnip(ctx)) {
                onProgress("下載 Turnip glibc ICD $TURNIP_VERSION …")
                val soCached = File(cache, "$TURNIP_SO_NAME-$TURNIP_VERSION")
                download(TURNIP_URL, soCached)
                val got = sha256(soCached)
                if (!got.equals(TURNIP_SHA256, ignoreCase = true)) {
                    soCached.delete()
                    error("Turnip SHA256 不符：expect=$TURNIP_SHA256 got=$got")
                }
                val dest = turnipDir(ctx)
                dest.deleteRecursively()
                dest.mkdirs()
                val so = File(dest, TURNIP_SO_NAME)
                soCached.copyTo(so, overwrite = true)
                so.setReadable(true, false)
                so.setExecutable(true, false)
                writeTurnipIcd(dest)
                onProgress("Turnip glibc ICD 已就緒 → ${so.absolutePath}")
            } else {
                writeTurnipIcd(turnipDir(ctx))
                onProgress("Turnip 已就緒")
            }
            if (!hasDxvk(ctx)) {
                onProgress("下載 DXVK $DXVK_VERSION …")
                val tg = File(cache, "dxvk-$DXVK_VERSION.tar.gz")
                download(DXVK_URL, tg)
                val dest = dxvkDir(ctx)
                dest.deleteRecursively()
                dest.mkdirs()
                extractTarGz(tg, dest)
                dest.listFiles()?.firstOrNull { it.isDirectory && it.name.startsWith("dxvk") }?.let { nested ->
                    nested.listFiles()?.forEach { child ->
                        val t = File(dest, child.name)
                        if (!t.exists()) child.renameTo(t)
                    }
                }
                onProgress("DXVK 已解壓 → ${dest.absolutePath}")
            } else {
                onProgress("DXVK 已就緒")
            }
            runCatching { stageGladioIntoRootfs(ctx) }
            runCatching { stageAlsaAAudioIntoRootfs(ctx) }
            File(stackRoot(ctx), "VERSION.json").writeText(
                JSONObject()
                    .put("turnip", TURNIP_VERSION)
                    .put("turnipAbi", "aarch64-glibc-kgsl")
                    .put("dxvk", DXVK_VERSION)
                    .put("presentationReady", PRESENTATION_READY)
                    .put("turnipIcdReady", TURNIP_ICD_READY)
                    .put("glxGladioReady", GLX_GLADIO_READY)
                    .put("aaudioReady", AAUDIO_READY)
                    .put("installedAt", System.currentTimeMillis())
                    .toString(2),
            )
        }
    }

    /**
     * 把 Gladio 客體 libGL 裝進 rootfs `/opt/gladio/lib`（GpuStack OpenGL／GLX）。
     */
        /** 客體 ALSA android_aserver plugin＋conf → rootfs */
    fun stageAlsaAAudioIntoRootfs(ctx: Context): Boolean {
        val rootfs = File(ComponentInstaller.runtimeRoot(ctx), "rootfs")
        if (!rootfs.isDirectory) return false
        val pluginDir = File(rootfs, "usr/lib/aarch64-linux-gnu/alsa-lib").also { it.mkdirs() }
        val plugin = File(pluginDir, "libasound_module_pcm_android_aserver.so")
        if (!plugin.exists() || plugin.length() < 10_000L) {
            try {
                ctx.assets.open("engine/audio/alsa-lib/libasound_module_pcm_android_aserver.so").use { input ->
                    plugin.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (_: Throwable) {
                return false
            }
        }
        plugin.setExecutable(true, false)
        plugin.setReadable(true, false)
        val confDir = File(rootfs, "etc/alsa/conf.d").also { it.mkdirs() }
        val conf = File(confDir, "android_aserver.conf")
        ctx.assets.open("engine/audio/conf.d/android_aserver.conf").use { input ->
            conf.outputStream().use { output -> input.copyTo(output) }
        }
        val shareAlsa = File(rootfs, "usr/share/alsa").also { it.mkdirs() }
        val alsaConf = File(shareAlsa, "alsa.conf")
        ctx.assets.open("engine/audio/alsa.conf").use { input ->
            alsaConf.outputStream().use { output -> input.copyTo(output) }
        }
        // 清掉 null sink，改走 aserver
        val asound = File(rootfs, "etc/asound.conf")
        asound.writeText(
            "pcm.!default { type android_aserver }\n" +
                "ctl.!default { type android_aserver }\n",
        )
        return plugin.exists()
    }

    fun stageGladioIntoRootfs(ctx: Context): Boolean {
        val rootfs = File(ComponentInstaller.runtimeRoot(ctx), "rootfs")
        if (!rootfs.isDirectory) return false
        val destDir = File(rootfs, "opt/gladio/lib").also { it.mkdirs() }
        val destSo = File(destDir, "libGL.so.1.7.0")
        if (!destSo.exists() || destSo.length() < 100_000L) {
            try {
                ctx.assets.open("engine/graphics/gladio/usr/lib/libGL.so.1.7.0").use { input ->
                    destSo.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (_: Throwable) {
                return false
            }
        }
        destSo.setReadable(true, false)
        destSo.setExecutable(true, false)
        for (link in listOf("libGL.so.1", "libGL.so")) {
            val f = File(destDir, link)
            if (f.exists()) f.delete()
            try {
                java.nio.file.Files.createSymbolicLink(f.toPath(), destSo.toPath().fileName)
            } catch (_: Throwable) {
                destSo.copyTo(f, overwrite = true)
            }
        }
        File(destDir, "VERSION").writeText("gladio-1.1" + "\n")
        return destSo.exists()
    }

    fun hasGladioGuest(ctx: Context): Boolean {
        val so = File(ComponentInstaller.runtimeRoot(ctx), "rootfs/opt/gladio/lib/libGL.so.1.7.0")
        return so.isFile && so.length() > 100_000L
    }

    fun stageDxvkIntoPrefix(ctx: Context, winePrefix: File): Boolean {
        if (!hasDxvk(ctx)) return false
        val sys32 = File(winePrefix, "drive_c/windows/system32").also { it.mkdirs() }
        val src = File(dxvkDir(ctx), "x64")
        if (!src.isDirectory) return false
        listOf("d3d8.dll", "d3d9.dll", "d3d10core.dll", "d3d11.dll", "dxgi.dll").forEach { name ->
            val f = File(src, name)
            if (f.exists()) f.copyTo(File(sys32, name), overwrite = true)
        }
        return true
    }

    /**
     * ICD JSON 必須用客體相對路徑（同目錄 .so），不可寫宿主 filesDir 絕對路徑。
     */
    private fun writeTurnipIcd(turnipDir: File) {
        val so = File(turnipDir, TURNIP_SO_NAME)
        if (!so.isFile) return
        File(turnipDir, TURNIP_ICD_NAME).writeText(
            """
            {
              "file_format_version": "1.0.0",
              "ICD": {
                "library_path": "$TURNIP_SO_NAME",
                "api_version": "1.3.278"
              }
            }
            """.trimIndent() + "\n",
        )
    }

    private fun download(url: String, dest: File) {
        if (dest.exists() && dest.length() > 100_000L) return
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 180_000
            instanceFollowRedirects = true
        }
        conn.inputStream.use { input ->
            FileOutputStream(dest).use { output -> input.copyTo(output) }
        }
        conn.disconnect()
    }

    @Suppress("unused")
    private fun unzip(zip: File, dest: File) {
        dest.mkdirs()
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val out = File(dest, e.name)
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zis.copyTo(it) }
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
    }

    private fun extractTarGz(archive: File, dest: File) {
        dest.mkdirs()
        val tar = org.apache.commons.compress.archivers.tar.TarArchiveInputStream(
            java.util.zip.GZIPInputStream(archive.inputStream().buffered()),
        )
        tar.use { tis ->
            var e = tis.nextEntry
            while (e != null) {
                val out = File(dest, e.name)
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { tis.copyTo(it) }
                    if (e.name.endsWith(".so") || !e.name.contains('.')) {
                        out.setExecutable(true, false)
                    }
                }
                e = tis.nextEntry
            }
        }
    }

    private fun sha256(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { b -> "%02x".format(b) }
    }
}
