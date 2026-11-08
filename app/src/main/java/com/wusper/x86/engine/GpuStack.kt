package com.wusper.x86.engine

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Winlator／glibc 級 GPU 棧接縫：Turnip ICD＋DXVK＋（下一步）進程內 X／Present→Surface。
 *
 * 合法重用（見 docs/THIRD_PARTY.md、docs/GPU_STACK.md）：
 * - Mesa Turnip：MIT（nihui Android 預編 ICD）
 * - DXVK：Zlib（doitsujin 官方 release）
 * - 參考架構：brunodev85/winlator（LGPL-2.1）— 本版**不整樹 fork**，只對齊容器／啟動腳本＋我們的 Session Surface
 */
object GpuStack {

    /** 進程內 X／DRI3 Present→AHardwareBuffer→Surface 尚未落地前為 false */
    const val PRESENTATION_READY: Boolean = false

    const val TURNIP_VERSION = "26.2.6"
    const val DXVK_VERSION = "2.7.1"

    // DXVK 2.7.1 與常見 Wine 11 組合較穩；3.x 需更新 Wine，下一檔再升
    private const val DXVK_URL =
        "https://github.com/doitsujin/dxvk/releases/download/v2.7.1/dxvk-2.7.1.tar.gz"
    private const val TURNIP_URL =
        "https://github.com/nihui/mesa-turnip-android-driver/releases/download/26.2.6/mesa-turnip-android-26.2.6.zip"
    private const val TURNIP_SHA256 =
        "936c31c5ed5947b3c2a1106043832db93d6af270b5dede86449b6a44fc40fff4"

    fun stackRoot(ctx: Context): File =
        File(ctx.filesDir, "engine/gpu-stack").also { it.mkdirs() }

    fun turnipDir(ctx: Context): File = File(stackRoot(ctx), "turnip-$TURNIP_VERSION")
    fun dxvkDir(ctx: Context): File = File(stackRoot(ctx), "dxvk-$DXVK_VERSION")

    fun hasTurnip(ctx: Context): Boolean {
        val d = turnipDir(ctx)
        if (!d.isDirectory) return false
        return d.walkTopDown().any { it.isFile && it.name.contains("vulkan") && it.extension == "so" } ||
            File(d, "libvulkan_freedreno.so").exists() ||
            d.listFiles()?.any { it.extension == "so" } == true
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
            g += "缺 Mesa Turnip Android ICD（$TURNIP_VERSION；Adreno Vulkan）"
        }
        if (!hasDxvk(ctx)) {
            g += "缺 DXVK $DXVK_VERSION（d3d8/9/10/11→Vulkan DLL）"
        }
        if (!PRESENTATION_READY) {
            g += "缺進程內 X／Present→AHardwareBuffer→Session Surface（對齊 Winlator XServerView／GLRenderer；非 Xvfb XWD）"
        }
        g += "缺 WinHandler 級輸入橋（觸控／鍵鼠→客體；取代 xdotool 檔案佇列）"
        g += "缺 Pulse／ALSA→AAudio 真輸出"
        return g
    }

    fun canLaunchGpuSession(ctx: Context): Boolean =
        PRESENTATION_READY && componentsInstalled(ctx)

    fun statusMessage(ctx: Context): String {
        val parts = mutableListOf<String>()
        parts += if (hasTurnip(ctx)) "Turnip✓" else "Turnip✗"
        parts += if (hasDxvk(ctx)) "DXVK✓" else "DXVK✗"
        parts += if (PRESENTATION_READY) "Present✓" else "Present✗（架構已切，橋未落地）"
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
        val turnip = turnipDir(ctx)
        if (turnip.isDirectory) {
            // ICD 經 proot bind 進客體後由腳本設置；此處先標路徑
            env += "HOST_TURNIP_DIR=${turnip.absolutePath}"
            env += "VK_ICD_FILENAMES=/mnt/gpu/turnip/freedreno_icd.json"
        }
        val dxvk = dxvkDir(ctx)
        if (dxvk.isDirectory) {
            env += "HOST_DXVK_DIR=${dxvk.absolutePath}"
            env += "WINEDLLOVERRIDES=d3d8,d3d9,d3d10core,d3d11,dxgi=n,b;winepulse.drv=d"
        }
        env += "X86_GPU_STATUS=${statusMessage(ctx)}"
        env += "X86_WS_DATA=$wsDataAbs"
        return env
    }

    /**
     * 下載 Turnip＋DXVK 到 app 私有目錄（不進 APK）。
     * 可在 PE 啟動前呼叫；Present 橋未就緒時仍可先備料。
     */
    fun installComponents(ctx: Context, onProgress: (String) -> Unit = {}): Result<Unit> {
        return runCatching {
            val cache = File(stackRoot(ctx), "cache").also { it.mkdirs() }
            if (!hasTurnip(ctx)) {
                onProgress("下載 Mesa Turnip $TURNIP_VERSION …")
                val zip = File(cache, "mesa-turnip-android-$TURNIP_VERSION.zip")
                download(TURNIP_URL, zip)
                val got = sha256(zip)
                if (!got.equals(TURNIP_SHA256, ignoreCase = true)) {
                    zip.delete()
                    error("Turnip SHA256 不符：expect=$TURNIP_SHA256 got=$got")
                }
                val dest = turnipDir(ctx)
                dest.deleteRecursively()
                dest.mkdirs()
                unzip(zip, dest)
                onProgress("Turnip 已解壓 → ${dest.absolutePath}")
                writeTurnipIcdHint(dest)
            } else {
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
                // 官方包頂層常為 dxvk-x.y.z/{x64,x32}
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
            File(stackRoot(ctx), "VERSION.json").writeText(
                JSONObject()
                    .put("turnip", TURNIP_VERSION)
                    .put("dxvk", DXVK_VERSION)
                    .put("presentationReady", PRESENTATION_READY)
                    .put("installedAt", System.currentTimeMillis())
                    .toString(2),
            )
        }
    }

    /** 把 DXVK x64 DLL 拷進 wineprefix（若存在） */
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

    private fun writeTurnipIcdHint(turnipDir: File) {
        val so = turnipDir.walkTopDown().firstOrNull {
            it.isFile && it.extension == "so" &&
                (it.name.contains("freedreno") || it.name.contains("vulkan") || it.name.startsWith("lib"))
        } ?: return
        val icd = File(turnipDir, "freedreno_icd.json")
        if (!icd.exists()) {
            icd.writeText(
                """
                {
                  "file_format_version": "1.0.0",
                  "ICD": {
                    "library_path": "${so.absolutePath}",
                    "api_version": "1.3.0"
                  }
                }
                """.trimIndent() + "\n",
            )
        }
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
