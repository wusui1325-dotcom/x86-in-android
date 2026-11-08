package com.wusper.x86.engine

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.json.JSONObject
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 首次 PE 啟動時下載／解壓 Wine＋glibc rootfs＋glibc Box64。
 * PRoot 本體隨 APK（assets/engine/proot）。
 * 圖形預設走 GpuStack（Turnip／DXVK）；Xvfb 軟渲非預設。
 */
object ComponentInstaller {
    private val busy = AtomicBoolean(false)

    data class Progress(
        val stage: String,
        val current: Long = 0,
        val total: Long = 0,
        val message: String = "",
    )

    fun runtimeRoot(ctx: Context): File =
        File(ctx.filesDir, "engine/runtime").also { it.mkdirs() }

    fun expectedRuntimeId(ctx: Context): String =
        runCatching {
            JSONObject(ctx.assets.open("engine/components.json").bufferedReader().readText())
                .getString("runtimeId")
        }.getOrDefault("")

    fun installedRuntimeId(ctx: Context): String =
        runCatching {
            JSONObject(File(runtimeRoot(ctx), "VERSION.json").readText()).getString("runtimeId")
        }.getOrDefault("")

    fun hasElectronOverlay(rootfs: File): Boolean =
        File(rootfs, "etc/x86-electron-overlay.stamp").exists() ||
            File(rootfs, "usr/lib/aarch64-linux-gnu/libnss3.so").exists()

    fun hasWineAddons(rootfs: File): Boolean =
        File(rootfs, "opt/wine/share/wine/mono/wine-mono-10.1.0-x86.msi").exists() ||
            File(rootfs, "opt/wine/share/wine/gecko/wine-gecko-2.47.4-x86_64").isDirectory

    /**
     * Wine／glibc 基底就緒。
     * 不再把 Xvfb／xdotool 當硬依賴（GpuStack 預設不走軟渲）；
     * SoftLegacy 另查 [isSoftLegacyReady]。
     */
    fun isReady(ctx: Context): Boolean {
        val root = runtimeRoot(ctx)
        val ver = File(root, "VERSION.json")
        if (!ver.exists()) return false
        val expect = expectedRuntimeId(ctx)
        if (expect.isNotBlank() && installedRuntimeId(ctx) != expect) return false
        val rootfs = File(root, "rootfs")
        val wine = File(rootfs, "opt/wine/bin/wine")
        val box = File(rootfs, "usr/local/bin/box64")
        val proot = File(EngineAssets.engineRoot(ctx), "proot")
        return wine.exists() && box.exists() &&
            hasElectronOverlay(rootfs) && hasWineAddons(rootfs) &&
            (proot.exists() || File(root, "bin/proot").exists())
    }

    fun isSoftLegacyReady(ctx: Context): Boolean {
        if (!isReady(ctx)) return false
        val rootfs = File(runtimeRoot(ctx), "rootfs")
        return File(rootfs, "usr/bin/Xvfb").exists() && File(rootfs, "usr/bin/xdotool").exists()
    }

    /** GpuStack 過渡：確保 Xvfb 在 rootfs（只當 X 協定 stub，畫面走 Present／AHB） */
    fun ensureXProtocolStub(ctx: Context): Boolean {
        val rootfs = File(runtimeRoot(ctx), "rootfs")
        val xvfb = File(rootfs, "usr/bin/Xvfb")
        if (xvfb.exists()) {
            xvfb.setExecutable(true, false)
            return true
        }
        // 觸發完整 install 以拉取 x11-overlay（若 manifest 仍含）
        return false
    }

    fun needsX11Overlay(ctx: Context): Boolean {
        val rootfs = File(runtimeRoot(ctx), "rootfs")
        return !File(rootfs, "usr/bin/Xvfb").exists()
    }

    fun ensureProot(ctx: Context): File {
        val dest = File(EngineAssets.engineRoot(ctx), "proot")
        return copyAsset(ctx, "engine/proot", dest, executable = true)
    }

    fun ensureWineLaunchScript(ctx: Context) {
        val rootfs = File(runtimeRoot(ctx), "rootfs")
        val dest = File(rootfs, "usr/local/bin/wine-launch.sh")
        dest.parentFile?.mkdirs()
        ctx.assets.open("engine/wine-launch.sh").use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        dest.setExecutable(true, false)
        File(rootfs, "usr/bin/Xvfb").let { if (it.exists()) it.setExecutable(true, false) }
        File(rootfs, "usr/bin/xdotool").let { if (it.exists()) it.setExecutable(true, false) }
        ensureInputAgent(ctx)
    }

    fun ensureInputAgent(ctx: Context) {
        val rootfs = File(runtimeRoot(ctx), "rootfs")
        val dest = File(rootfs, "usr/local/bin/x11-input-agent.sh")
        dest.parentFile?.mkdirs()
        ctx.assets.open("engine/x11-input-agent.sh").use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        dest.setExecutable(true, false)
        // 同步一份到 data bind，啟動腳本可 fallback
    }

    fun ensureInputAgent(rootfs: File) {
        // install() 無 Context assets 時：若已有檔則 chmod
        val dest = File(rootfs, "usr/local/bin/x11-input-agent.sh")
        if (dest.exists()) dest.setExecutable(true, false)
    }

    /**
     * 阻塞下載＋解壓。應在背景執行緒呼叫。
     */
    fun install(ctx: Context, onProgress: (Progress) -> Unit = {}): Result<File> {
        if (!busy.compareAndSet(false, true)) {
            return Result.failure(IllegalStateException("組件安裝進行中"))
        }
        try {
            ensureProot(ctx)
            if (isReady(ctx)) {
                ensureWineLaunchScript(ctx)
                onProgress(Progress("ready", message = "運行時已就緒"))
                return Result.success(runtimeRoot(ctx))
            }
            val manifest = ctx.assets.open("engine/components.json").bufferedReader().readText()
            val root = runtimeRoot(ctx)
            val cache = File(ctx.filesDir, "engine/cache").also { it.mkdirs() }
            val jo = JSONObject(manifest)
            val runtimeId = jo.getString("runtimeId")
            val arr = jo.getJSONArray("components")
            val rootfs = File(root, "rootfs")
            val hasWine = File(rootfs, "opt/wine/bin/wine").exists()
            val hasBox = File(rootfs, "usr/local/bin/box64").exists()
            val hasElectron = hasElectronOverlay(rootfs)
            val hasAddons = hasWineAddons(rootfs)
            val baseOk = hasWine && hasBox
            // GpuStack 預設：不再強制抓 Xvfb／xdotool；僅補 Electron／mono 等
            val needExtras = mutableSetOf<String>()
            if (!hasElectron) needExtras += "electron-overlay"
            if (!hasAddons) needExtras += "wine-addons-overlay"
            // SoftLegacy 才需要 Xvfb；GpuStack 用進程內 X
            val onlyExtras = baseOk && needExtras.isNotEmpty()
            val stampOnly = baseOk && hasElectron && hasAddons
            if (stampOnly) {
                onProgress(Progress("ready", message = "更新 runtimeId → $runtimeId"))
            }
            for (i in 0 until arr.length()) {
                if (stampOnly) break
                val c = arr.getJSONObject(i)
                val id = c.getString("id")
                // 已有基底：略過 rootfs／wine／box64；只抓缺的 overlay
                if (baseOk && id in setOf("rootfs", "wine", "box64-glibc")) continue
                if (baseOk && needExtras.isNotEmpty() && id !in needExtras) continue
                val filename = c.getString("filename")
                val url = c.getString("url")
                val expectSha = c.optString("sha256", "")
                val out = File(cache, filename)
                onProgress(Progress("download", message = "下載 $id …"))
                download(url, out) { cur, tot ->
                    onProgress(Progress("download", cur, tot, "下載 $id"))
                }
                if (expectSha.isNotBlank()) {
                    val got = sha256(out)
                    if (!got.equals(expectSha, ignoreCase = true)) {
                        out.delete()
                        return Result.failure(
                            IllegalStateException("SHA256 不符：$id expect=$expectSha got=$got"),
                        )
                    }
                }
                onProgress(Progress("extract", message = "解壓 $id …"))
                when (id) {
                    "rootfs" -> extractRootfs(out, File(root, "rootfs"), c.optString("stripPrefix", ""))
                    "wine" -> extractWine(out, File(root, "rootfs/opt"), c)
                    "box64-glibc" -> extractBox64(out, File(root, "rootfs/usr/local/bin"))
                    "x11-overlay", "input-overlay", "electron-overlay", "wine-addons-overlay" ->
                        extractTarXz(out, File(root, "rootfs"), skipDevice = true)
                    else -> extractTarXz(out, File(root, c.optString("extract", ".")))
                }
            }
            // resolv + dirs
            File(rootfs, "dev").mkdirs()
            File(rootfs, "proc").mkdirs()
            File(rootfs, "sys").mkdirs()
            File(rootfs, "tmp").mkdirs()
            File(rootfs, "home/xuser").mkdirs()
            File(rootfs, "etc").mkdirs()
            File(rootfs, "etc/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
            File(root, "VERSION.json").writeText(
                JSONObject()
                    .put("runtimeId", runtimeId)
                    .put("installedAt", System.currentTimeMillis())
                    .toString(2),
            )
            // executable bits
            File(rootfs, "usr/local/bin/box64").setExecutable(true, false)
            File(rootfs, "opt/wine/bin/wine").setExecutable(true, false)
            File(rootfs, "usr/bin/Xvfb").setExecutable(true, false)
            File(rootfs, "usr/bin/xdotool").setExecutable(true, false)
            // 確保 null ALSA
            val asound = File(rootfs, "etc/asound.conf")
            if (!asound.exists()) {
                asound.parentFile?.mkdirs()
                asound.writeText("pcm.!default { type null }\nctl.!default { type null }\n")
            }
            ensureWineLaunchScript(ctx)
            onProgress(Progress("ready", message = "運行時安裝完成"))
            return Result.success(root)
        } catch (t: Throwable) {
            return Result.failure(t)
        } finally {
            busy.set(false)
        }
    }

    private fun download(url: String, dest: File, progress: (Long, Long) -> Unit) {
        if (dest.exists() && dest.length() > 1_000_000L) {
            progress(dest.length(), dest.length())
            return
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 120_000
            instanceFollowRedirects = true
        }
        conn.inputStream.use { input ->
            val total = conn.contentLengthLong.coerceAtLeast(0L)
            FileOutputStream(dest).use { output ->
                val buf = ByteArray(256 * 1024)
                var got = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                    got += n
                    progress(got, total)
                }
            }
        }
        conn.disconnect()
    }

    private fun extractRootfs(archive: File, dest: File, stripPrefix: String) {
        dest.mkdirs()
        extractTarXz(archive, dest, skipDevice = true)
        if (stripPrefix.isNotBlank()) {
            val nested = File(dest, stripPrefix)
            if (nested.isDirectory) {
                nested.listFiles()?.forEach { child ->
                    val target = File(dest, child.name)
                    if (!target.exists()) child.renameTo(target)
                }
                nested.deleteRecursively()
            }
        }
    }

    private fun extractWine(archive: File, optDir: File, c: JSONObject) {
        optDir.mkdirs()
        extractTarXz(archive, optDir)
        val from = c.optString("renameFrom", "")
        val to = c.optString("renameTo", "wine")
        if (from.isNotBlank()) {
            val src = File(optDir, from)
            val dst = File(optDir, to)
            if (src.exists()) {
                if (dst.exists()) dst.deleteRecursively()
                src.renameTo(dst)
            }
        }
    }

    private fun extractBox64(archive: File, binDir: File) {
        binDir.mkdirs()
        val tmp = File(binDir.parentFile, "_box64_extract").also {
            it.deleteRecursively()
            it.mkdirs()
        }
        extractTarXz(archive, tmp)
        val box = tmp.walkTopDown().firstOrNull { it.isFile && it.name == "box64" }
            ?: throw IllegalStateException("wcp 內找不到 box64")
        val dest = File(binDir, "box64")
        box.copyTo(dest, overwrite = true)
        dest.setExecutable(true, false)
        tmp.deleteRecursively()
    }

    private fun extractTarXz(
        archive: File,
        dest: File,
        skipDevice: Boolean = false,
    ) {
        dest.mkdirs()
        FileInputStream(archive).use { fis ->
            BufferedInputStream(fis).use { bis ->
                XZInputStream(bis).use { xzis ->
                    TarArchiveInputStream(xzis).use { tis ->
                        var entry: TarArchiveEntry? = tis.nextEntry
                        while (entry != null) {
                            val name = entry.name.removePrefix("./")
                            if (skipDevice && (name.contains("/dev/") || name.startsWith("dev/"))) {
                                entry = tis.nextEntry
                                continue
                            }
                            val out = File(dest, name)
                            if (entry.isDirectory) {
                                out.mkdirs()
                            } else if (entry.isSymbolicLink) {
                                // skip symlinks that may escape; best-effort
                                out.parentFile?.mkdirs()
                            } else {
                                out.parentFile?.mkdirs()
                                FileOutputStream(out).use { fos -> tis.copyTo(fos) }
                                if (entry.mode and 0b001001001 != 0) {
                                    out.setExecutable(true, false)
                                }
                            }
                            entry = tis.nextEntry
                        }
                    }
                }
            }
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun copyAsset(ctx: Context, assetPath: String, dest: File, executable: Boolean): File {
        val assetLen = runCatching { ctx.assets.openFd(assetPath).use { it.length } }.getOrElse { -1L }
        val need = !dest.exists() || dest.length() == 0L || (assetLen > 0 && dest.length() != assetLen)
        if (need) {
            dest.parentFile?.mkdirs()
            ctx.assets.open(assetPath).use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
        }
        if (executable) dest.setExecutable(true, false)
        return dest
    }

    /** 僅在無 Context assets 時備援；正常路徑走 ensureWineLaunchScript。 */
    private fun writeWineLaunchScript(rootfs: File) {
        val f = File(rootfs, "usr/local/bin/wine-launch.sh")
        f.parentFile?.mkdirs()
        val d = "${'$'}"
        val script = """
            |#!/bin/sh
            |set -e
            |export DISPLAY=:0
            |export LIBGL_ALWAYS_SOFTWARE="${d}{LIBGL_ALWAYS_SOFTWARE:-1}"
            |export GALLIUM_DRIVER="${d}{GALLIUM_DRIVER:-llvmpipe}"
            |export WINEDLLOVERRIDES="${d}{WINEDLLOVERRIDES:-winepulse.drv=d}"
            |if [ ! -f /opt/wine/share/wine/mono/wine-mono-10.1.0-x86.msi ] && \
            |   [ ! -d /opt/wine/share/wine/mono/wine-mono-10.1.0 ]; then
            |  export WINEDLLOVERRIDES="mscoree,mshtml=;${d}WINEDLLOVERRIDES"
            |fi
            |W="${d}{WINE_DESKTOP_W:-1280}"
            |H="${d}{WINE_DESKTOP_H:-720}"
            |mkdir -p /mnt/data/xfb /tmp/.X11-unix /mnt/data
            |rm -f /mnt/data/xfb/* 2>/dev/null || true
            |if [ ! -f /etc/asound.conf ]; then
            |  printf 'pcm.!default { type null }\nctl.!default { type null }\n' > /etc/asound.conf
            |fi
            |Xvfb :0 -screen 0 ${d}{W}x${d}{H}x24 -ac -nolisten tcp -fbdir /mnt/data/xfb >/mnt/data/xvfb.log 2>&1 &
            |XPID=${d}!
            |i=0
            |while [ ${d}i -lt 80 ]; do
            |  if [ -e /mnt/data/xfb/Xvfb_screen0 ] || [ -S /tmp/.X11-unix/X0 ]; then break; fi
            |  i=${d}((i+1)); sleep 0.1
            |done
            |if [ -x /usr/bin/xdotool ] || command -v xdotool >/dev/null 2>&1; then
            |  if [ -x /usr/local/bin/x11-input-agent.sh ]; then
            |    /usr/local/bin/x11-input-agent.sh >/mnt/data/x11input-agent.out 2>&1 &
            |  elif [ -f /mnt/data/x11-input-agent.sh ]; then
            |    sh /mnt/data/x11-input-agent.sh >/mnt/data/x11input-agent.out 2>&1 &
            |  fi
            |  IPID=${d}!
            |else
            |  IPID=
            |fi
            |/usr/local/bin/box64 /opt/wine/bin/wine explorer /desktop=x86,${d}{W}x${d}{H} "${d}@"
            |RC=${d}?
            |[ -n "${d}IPID" ] && kill ${d}IPID 2>/dev/null || true
            |kill ${d}XPID 2>/dev/null || true
            |wait ${d}XPID 2>/dev/null || true
            |exit ${d}RC
            """.trimMargin()
        f.writeText(script)
        f.setExecutable(true, false)
    }
}
