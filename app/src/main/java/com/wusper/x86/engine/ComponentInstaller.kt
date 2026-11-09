package com.wusper.x86.engine

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.json.JSONObject
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Future
import java.util.concurrent.Executors
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Paths
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
    private val cancelRequested = AtomicBoolean(false)

    /** 首次下載必須有非空 sha256 的組件 */
    private val CRITICAL_SHA_IDS = setOf("rootfs", "box64-glibc", "box64-x64lib", "wine")

    data class Progress(
        val stage: String,
        val current: Long = 0,
        val total: Long = 0,
        val message: String = "",
        val componentId: String = "",
        val componentIndex: Int = 0,
        val componentCount: Int = 0,
    )

    fun requestCancel() {
        cancelRequested.set(true)
    }

    fun isBusy(): Boolean = busy.get()

    class CancelledException : Exception("安裝已取消")

    /** 統一文案：請用戶在 App 內重裝，勿提內部路徑。 */
    const val USER_HINT_REINSTALL_RUNTIME = "請在 App 內點「重裝運行時」後再試（不會刪除工作區）。"

    /**
     * 清除引擎運行時（files/engine/runtime）與下載快取（files/engine/cache）。
     * **不**刪除 files/workspaces。回傳清除的位元組估算。
     */
    fun clearRuntime(ctx: Context, alsoClearCache: Boolean = true): Long {
        var freed = 0L
        val runtime = File(ctx.filesDir, "engine/runtime")
        if (runtime.exists()) {
            // 先拆 usrmerge，避免 dirSize／deleteRecursively 跟隨 symlink
            deleteRootfsSafely(File(runtime, "rootfs"))
            freed += dirSize(runtime)
            runtime.deleteRecursively()
        }
        if (alsoClearCache) {
            val cache = File(ctx.filesDir, "engine/cache")
            if (cache.exists()) {
                freed += dirSize(cache)
                cache.deleteRecursively()
            }
        }
        // GpuStack 組件（Turnip／DXVK）一併清，避免半殘
        val gpu = File(ctx.filesDir, "engine/gpu-stack")
        if (gpu.exists()) {
            freed += dirSize(gpu)
            gpu.deleteRecursively()
        }
        return freed
    }


    /**
     * 刪 rootfs 前先拆掉 usrmerge 頂層 symlink，避免 deleteRecursively／canonical
     * 跟著 bin→usr/bin 把真實目錄當連結目標清掉或踩到 ELOOP。
     */
    fun deleteRootfsSafely(rootfs: File) {
        if (!rootfs.exists()) return
        // 先拆頂層 usrmerge／自指環，再 deleteRecursively
        for (name in listOf("bin", "lib", "sbin", "lib64", "lib32", "usr/lib64")) {
            val f = File(rootfs, name)
            runCatching {
                if (Files.isSymbolicLink(f.toPath())) {
                    Files.deleteIfExists(f.toPath())
                }
            }
        }
        // 修已形成的 usr/bin→usr/bin 之類自指
        for (name in listOf("usr/bin", "usr/lib", "usr/sbin")) {
            val f = File(rootfs, name)
            runCatching {
                if (Files.isSymbolicLink(f.toPath())) {
                    val tgt = Files.readSymbolicLink(f.toPath()).toString()
                    val parent = f.parentFile?.toPath() ?: return@runCatching
                    if (parent.resolve(tgt).normalize() == f.toPath().toAbsolutePath().normalize()) {
                        Files.deleteIfExists(f.toPath())
                    }
                }
            }
        }
        if (!rootfs.deleteRecursively()) {
            // 殘留時逐層再試
            rootfs.walkBottomUp().forEach { f ->
                runCatching {
                    if (Files.isSymbolicLink(f.toPath())) Files.deleteIfExists(f.toPath())
                    else f.delete()
                }
            }
            rootfs.deleteRecursively()
        }
    }

    /**
     * 不解引用 symlink 的路徑拼接＋normalize。用於 tar 解壓輸出路徑。
     * 舊版用 File.canonicalFile 會在已有 bin→usr/bin 時把 entry「bin」解析成 usr/bin，
     * 再 createSymbolicLink(usr/bin, "usr/bin") → 自指環 → IOException ELOOP。
     */
    private fun lexicalResolveUnder(dest: File, relative: String): File? {
        if (relative.isBlank() || relative.startsWith("/")) return null
        val destPath = dest.toPath().toAbsolutePath().normalize()
        val out = destPath.resolve(relative).normalize()
        if (!out.startsWith(destPath)) return null
        return out.toFile()
    }

    private fun isSymlinkLoopMessage(t: Throwable): Boolean {
        var c: Throwable? = t
        while (c != null) {
            val m = c.message?.lowercase() ?: ""
            if (m.contains("too many symbolic links") || m.contains("too many levels of symbolic links") ||
                m.contains("eloop")
            ) {
                return true
            }
            c = c.cause
        }
        return false
    }

    /** 刪除路徑自身（symlink 只刪連結，不跟隨） */
    private fun deleteSelfNoFollow(f: File) {
        val p = f.toPath()
        if (Files.isSymbolicLink(p)) {
            Files.deleteIfExists(p)
            return
        }
        if (!f.exists()) return
        if (f.isDirectory) f.deleteRecursively() else f.delete()
    }

    fun approxDownloadBytes(ctx: Context): Long =
        runCatching {
            JSONObject(ctx.assets.open("engine/components.json").bufferedReader().readText())
                .optLong("approxDownloadBytes", 450_000_000L)
        }.getOrDefault(450_000_000L)

    fun approxDownloadMbLabel(ctx: Context): String {
        val mb = (approxDownloadBytes(ctx) + 500_000) / 1_000_000
        return "約 ${mb}MB"
    }

    /** 下載＋解壓緩衝：約需下載量 ×1.5，至少再留 200MB。 */
    fun requiredFreeBytes(ctx: Context): Long {
        val dl = approxDownloadBytes(ctx)
        return maxOf(dl + dl / 2, dl + 200_000_000L)
    }

    fun availableBytes(ctx: Context): Long = runCatching {
        val path = ctx.filesDir?.absolutePath ?: return@runCatching 0L
        val st = android.os.StatFs(path)
        st.availableBlocksLong * st.blockSizeLong
    }.getOrDefault(0L)

    fun formatBytes(bytes: Long): String {
        if (bytes >= 1_000_000_000L) return "%.1fGB".format(bytes / 1e9)
        if (bytes >= 1_000_000L) return "%.0fMB".format(bytes / 1e6)
        return "${bytes}B"
    }

    /**
     * 下載前預檢。不足時拋 [IllegalStateException]（繁中，不含內部路徑）。
     */
    fun assertEnoughSpace(ctx: Context) {
        val need = requiredFreeBytes(ctx)
        val have = availableBytes(ctx)
        if (have in 1 until need) {
            throw IllegalStateException(
                "儲存空間不足：可用約 ${formatBytes(have)}，下載與解壓約需 ${formatBytes(need)}。" +
                    "請腾出空間後再試。",
            )
        }
    }

    private fun dirSize(f: File): Long {
        // 不跟隨 symlink（usrmerge bin→usr/bin、X11→. 否則會循環或 ELOOP）
        if (Files.isSymbolicLink(f.toPath())) return 0L
        if (!f.exists()) return 0L
        if (f.isFile) return f.length()
        return f.listFiles()?.sumOf { dirSize(it) } ?: 0L
    }


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
     * Wine 樹完整：不只 bin/wine，還要有 Box64 會 dlopen 的 unix ntdll.so。
     * 半殘安裝（只剩 wine stub）會讓 isReady 誤過 → 客體 exit=1 Cannot dlopen(ntdll.so)。
     */
    fun hasWineTree(rootfs: File): Boolean {
        val wine = File(rootfs, "opt/wine/bin/wine")
        val ntdll = File(rootfs, "opt/wine/lib/wine/x86_64-unix/ntdll.so")
        return wine.isFile && wine.length() > 0L && ntdll.isFile && ntdll.length() > 1024L
    }

    /** Box64 模擬用 x86_64 依賴庫（libgcc_s + 0.1.30+ bookworm stamp）。 */
    fun hasBox64X64lib(rootfs: File): Boolean {
        val gcc = File(rootfs, "usr/lib/box64-x86_64-linux-gnu/libgcc_s.so.1")
        val stamp = File(rootfs, "usr/lib/box64-x86_64-linux-gnu/.x64lib-stamp")
        // 缺 stamp＝舊 gcc-15 x64lib，強制重拉 bookworm 組件
        return gcc.isFile && gcc.length() > 0L && stamp.isFile && stamp.length() > 0L
    }

    /** Turnip glibc ICD 依賴：Vulkan loader＋drm／xcb（來自 x11-overlay） */
    fun hasTurnipVkDeps(rootfs: File): Boolean {
        val lib = File(rootfs, "usr/lib/aarch64-linux-gnu")
        return File(lib, "libvulkan.so.1").exists() &&
            File(lib, "libdrm.so.2").exists() &&
            File(lib, "libxcb-dri3.so.0").exists()
    }

    /**
     * rootfs 可啟動條件：
     * 1) 有可解析到真實 ELF 的 shell（bin/sh／usr/bin/sh／dash／bash）
     * 2) 有動態連結器 `/lib/ld-linux-aarch64.so.1`（dash 的 PT_INTERP；
     *    缺此檔時 execve(sh) 也回 ENOENT，proot 常顯示成 `/usr/bin/sh` not found）
     * 不接受 dangling symlink。
     */
    fun hasValidRootfs(rootfs: File): Boolean {
        if (!rootfs.isDirectory) return false
        return resolveShellElf(rootfs) != null && hasDynamicLinker(rootfs)
    }

    /** 客體路徑：優先真實 ELF（/usr/bin/dash|bash），再 /usr/bin/sh、/bin/sh */
    fun shellPathInRootfs(rootfs: File): String {
        resolveShellElf(rootfs)?.let { return it.guestPath }
        return "/usr/bin/dash"
    }

    data class ShellElf(val guestPath: String, val hostFile: File)

    /** 跟隨 symlink，必須落到 size>0 的常規檔 */
    fun resolveShellElf(rootfs: File): ShellElf? {
        val candidates = listOf(
            "usr/bin/dash",
            "usr/bin/bash",
            "usr/bin/sh",
            "bin/dash",
            "bin/bash",
            "bin/sh",
        )
        for (rel in candidates) {
            val f = File(rootfs, rel)
            val real = resolveRegularFile(f) ?: continue
            if (real.length() < 1024L) continue
            return ShellElf("/$rel", real)
        }
        return null
    }

    fun hasDynamicLinker(rootfs: File): Boolean {
        val candidates = listOf(
            "lib/ld-linux-aarch64.so.1",
            "usr/lib/ld-linux-aarch64.so.1",
            "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
            "lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
        )
        return candidates.any { rel ->
            resolveRegularFile(File(rootfs, rel))?.let { it.length() > 1024L } == true
        }
    }

    /** 解析最終常規檔；dangling／缺檔回 null */
    private fun resolveRegularFile(f: File): File? {
        return runCatching {
            if (!f.exists() && !Files.isSymbolicLink(f.toPath())) return null
            var cur = f.toPath()
            repeat(8) {
                if (Files.isSymbolicLink(cur)) {
                    val target = Files.readSymbolicLink(cur)
                    cur = if (target.isAbsolute) target else cur.parent.resolve(target).normalize()
                } else {
                    val file = cur.toFile()
                    return if (file.isFile && file.length() > 0L) file else null
                }
            }
            null
        }.getOrNull()
    }

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
        val prootFiles = File(EngineAssets.engineRoot(ctx), "proot")
        val prootNative = File(ctx.applicationInfo.nativeLibraryDir, "libproot.so")
        return hasWineTree(rootfs) && hasGlibcBox64(rootfs) && hasBox64X64lib(rootfs) &&
            hasValidRootfs(rootfs) &&
            hasElectronOverlay(rootfs) && hasWineAddons(rootfs) &&
            hasTurnipVkDeps(rootfs) &&
            (prootNative.exists() || prootFiles.exists() || File(root, "bin/proot").exists())
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
        val bundle = EngineAssets.ensureProotBundle(ctx)
        ExecBits.force(bundle.proot)
        ExecBits.force(bundle.loader)
        ExecBits.force(File(EngineAssets.engineRoot(ctx), "proot"))
        return bundle.proot
    }

    fun ensureProotBundle(ctx: Context): EngineAssets.ProotBundle =
        EngineAssets.ensureProotBundle(ctx)

    fun ensureWineLaunchScript(ctx: Context) {
        val rootfs = File(runtimeRoot(ctx), "rootfs")
        val dest = File(rootfs, "usr/local/bin/wine-launch.sh")
        dest.parentFile?.mkdirs()
        // 每次啟動覆寫（0.1.33：確保 unix wine 路徑進客體，勿留舊 bin/wine 腳本）
        if (dest.exists()) dest.delete()
        ctx.assets.open("engine/wine-launch.sh").use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        ExecBits.force(dest)
        // 0.1.59: wine-reg-helper.py beside wine-launch.sh (UTF-8 Version 2 offline patch)
        val helper = File(rootfs, "usr/local/bin/wine-reg-helper.py")
        runCatching {
            if (helper.exists()) helper.delete()
            ctx.assets.open("engine/wine-reg-helper.py").use { input ->
                helper.outputStream().use { output -> input.copyTo(output) }
            }
            ExecBits.force(helper)
        }.onFailure {
            android.util.Log.w("ComponentInstaller", "wine-reg-helper.py install failed: $it")
        }
        // script stamp fingerprint; clear sticky holefit marker when APK script changes
        val scriptStamp = File(rootfs, "usr/local/bin/.wine-launch-apk-stamp")
        val want = "0.1.63"
        val prev = runCatching { scriptStamp.readText().trim() }.getOrDefault("")
        if (prev != want) {
            File(rootfs, "usr/local/bin/.box64-wine64k-holefit").delete()
            android.util.Log.i(
                "ComponentInstaller",
                "wine-launch script refresh $prev → $want; cleared .box64-wine64k-holefit",
            )
        }
        scriptStamp.writeText("$want\n")
        File(rootfs, "usr/bin/Xvfb").let { if (it.exists()) ExecBits.force(it) }
        File(rootfs, "usr/bin/xdotool").let { if (it.exists()) ExecBits.force(it) }
        ensureInputAgent(ctx)
    }

    fun ensureInputAgent(ctx: Context) {
        val rootfs = File(runtimeRoot(ctx), "rootfs")
        val dest = File(rootfs, "usr/local/bin/x11-input-agent.sh")
        dest.parentFile?.mkdirs()
        ctx.assets.open("engine/x11-input-agent.sh").use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        ExecBits.force(dest)
        // 同步一份到 data bind，啟動腳本可 fallback
    }

    fun ensureInputAgent(rootfs: File) {
        // install() 無 Context assets 時：若已有檔則 chmod
        val dest = File(rootfs, "usr/local/bin/x11-input-agent.sh")
        if (dest.exists()) ExecBits.force(dest)
    }

    /**
     * 阻塞下載＋解壓。應在背景執行緒呼叫。
     */
    fun install(ctx: Context, onProgress: (Progress) -> Unit = {}): Result<File> {
        if (!busy.compareAndSet(false, true)) {
            return Result.failure(IllegalStateException("組件安裝進行中"))
        }
        try {
            cancelRequested.set(false)
            ensureProot(ctx)
            if (isReady(ctx)) {
                ensureUsrmergeShell(File(runtimeRoot(ctx), "rootfs"))
                ExecBits.forceEngineBins(EngineAssets.engineRoot(ctx), runtimeRoot(ctx))
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
            val hasWine = hasWineTree(rootfs)
            val hasBoxElf = hasGlibcBox64Elf(rootfs)
            val hasBox = hasGlibcBox64(rootfs) // ELF + wine64k stamp
            // 舊 bionic box64 視為未裝，強制重抓 glibc 包
            if (!hasBoxElf) {
                File(rootfs, "usr/local/bin/box64").takeIf { it.exists() }?.let {
                    android.util.Log.w("ComponentInstaller", "removing non-glibc box64 ${it.length()}B")
                    it.delete()
                }
            }
            // 舊 glibc box64（無 wine64k stamp）刪 stamp 旁的舊二進位標記，強制走 needExtras 重抽
            if (hasBoxElf && !hasBox) {
                android.util.Log.w("ComponentInstaller", "box64 missing wine64k-align-0x100000 stamp → will reinstall Box64-0.4.4-glibc-wine64k")
            }
            val hasElectron = hasElectronOverlay(rootfs)
            val hasAddons = hasWineAddons(rootfs)
            // 舊安裝跳過 symlink → 無 /bin/sh；先補 usrmerge（保留已下的 wine／box）
            if (rootfs.isDirectory) {
                ensureUsrmergeShell(rootfs)
            }
            var rootfsOk = hasValidRootfs(rootfs)
            val hasDashOrBash =
                File(rootfs, "usr/bin/dash").isFile || File(rootfs, "usr/bin/bash").isFile
            // 連 dash／bash 實體都沒有 → 必須重解整棵 rootfs（會清掉樹上 wine，故全組件重裝）
            val needsRootfsRetar = !rootfsOk && !hasDashOrBash
            val baseOk = hasWine && hasBoxElf && rootfsOk
            // GpuStack：x11-overlay 提供 libvulkan／drm／xcb（Turnip 依賴）；不強制 Xvfb
            val hasVkDeps = hasTurnipVkDeps(rootfs)
            val hasX64 = hasBox64X64lib(rootfs)
            val needExtras = mutableSetOf<String>()
            if (!hasElectron) needExtras += "electron-overlay"
            if (!hasAddons) needExtras += "wine-addons-overlay"
            if (!hasVkDeps) needExtras += "x11-overlay"
            if (!hasX64) needExtras += "box64-x64lib"
            if (!hasBox) needExtras += "box64-glibc"
            val stampOnly = baseOk && hasBox && hasElectron && hasAddons && hasVkDeps && hasX64 && !needsRootfsRetar
            if (stampOnly) {
                onProgress(Progress("ready", message = "更新 runtimeId → $runtimeId"))
            }
            if (!rootfsOk && hasDashOrBash) {
                onProgress(Progress("extract", message = "補齊 usrmerge／bin/sh symlink…"))
                ensureUsrmergeShell(rootfs)
                rootfsOk = hasValidRootfs(rootfs)
            }
            // 收集待處理組件
            val jobs = mutableListOf<JSONObject>()
            if (!stampOnly) {
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    val id = c.getString("id")
                    if (needsRootfsRetar) {
                        // 全量重裝
                        jobs += c
                        continue
                    }
                    if (baseOk && id in setOf("rootfs", "wine")) continue
                    if (baseOk && id == "box64-glibc" && hasBox) continue
                    if (baseOk && needExtras.isNotEmpty() && id !in needExtras) continue
                    if (!baseOk) {
                        // 缺基底：該下的都下（略過已有的）
                        if (id == "rootfs" && rootfsOk) continue
                        if (id == "wine" && hasWine) continue
                        if (id == "box64-glibc" && hasBox) continue
                        if (id == "box64-x64lib" && hasX64) continue
                    }
                    jobs += c
                }
            }
            val jobCount = jobs.size
            // 並行下載（解壓仍循序，避免搶磁碟）
            val downloaded = java.util.concurrent.ConcurrentHashMap<String, File>()
            if (jobs.isNotEmpty()) {
                onProgress(Progress("download", message = "並行下載 ${jobCount} 個組件…", componentCount = jobCount))
                val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(4, jobCount.coerceAtLeast(1)))
                try {
                    val futs = jobs.mapIndexed { idx, c ->
                        pool.submit<File> {
                            checkCancelled()
                            val id = c.getString("id")
                            val filename = c.getString("filename")
                            val url = c.getString("url")
                            val expectSha = c.optString("sha256", "")
                            if (id in CRITICAL_SHA_IDS && expectSha.isBlank()) {
                                throw IllegalStateException(
                                    "組件 $id 缺少完整性校驗（sha256）。請更新 App 後再裝運行時。",
                                )
                            }
                            val out = File(cache, filename)
                            downloadResumable(url, out, expectSha) { cur, tot ->
                                onProgress(
                                    Progress(
                                        "download", cur, tot, "下載 $id",
                                        componentId = id,
                                        componentIndex = idx + 1,
                                        componentCount = jobCount,
                                    ),
                                )
                            }
                            if (expectSha.isNotBlank()) {
                                val got = sha256(out)
                                if (!got.equals(expectSha, ignoreCase = true)) {
                                    out.delete()
                                    throw IllegalStateException("組件「$id」下載內容校驗失敗（檔案可能損毀）。請重試；若持續失敗，$USER_HINT_REINSTALL_RUNTIME")
                                }
                            }
                            downloaded[id] = out
                            out
                        }
                    }
                    futs.forEach { it.get() }
                } finally {
                    pool.shutdownNow()
                }
            }
            // 循序解壓（相依順序照 jobs）
            for ((idx, c) in jobs.withIndex()) {
                checkCancelled()
                val id = c.getString("id")
                val out = downloaded[id] ?: File(cache, c.getString("filename"))
                onProgress(
                    Progress(
                        "extract", message = "解壓 $id …",
                        componentId = id,
                        componentIndex = idx + 1,
                        componentCount = jobCount,
                    ),
                )
                when (id) {
                    "rootfs" -> {
                        val rf = File(root, "rootfs")
                        if (rf.exists()) {
                            onProgress(Progress("extract", message = "清除舊 rootfs 後重解…"))
                            // 必須整棵清掉：若殘留 bin→usr/bin，再解壓 entry「bin」時
                            // 舊 canonicalFile 會把輸出解析到 usr/bin 並建成自指環（ELOOP）。
                            deleteRootfsSafely(rf)
                        }
                        extractRootfs(out, rf, c.optString("stripPrefix", ""))
                    }
                    "wine" -> extractWine(out, File(root, "rootfs/opt"), c)
                    "box64-glibc" -> extractBox64(out, File(root, "rootfs/usr/local/bin"))
                    "box64-x64lib", "x11-overlay", "input-overlay", "electron-overlay", "wine-addons-overlay" ->
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
            ExecBits.force(File(rootfs, "usr/local/bin/box64"))
            ExecBits.force(File(rootfs, "opt/wine/bin/wine"))
            ExecBits.force(File(rootfs, "usr/bin/Xvfb"))
            ExecBits.force(File(rootfs, "usr/bin/xdotool"))
            if (!hasWineTree(rootfs)) {
                throw IllegalStateException(
                    "Wine 安裝不完整（缺少 ntdll.so）。$USER_HINT_REINSTALL_RUNTIME",
                )
            }
            if (!hasBox64X64lib(rootfs)) {
                throw IllegalStateException(
                    "Box64 依賴庫未就緒（缺少 libgcc_s）。$USER_HINT_REINSTALL_RUNTIME",
                )
            }
            // 確保 null ALSA
            val asound = File(rootfs, "etc/asound.conf")
            if (!asound.exists()) {
                asound.parentFile?.mkdirs()
                asound.writeText("pcm.!default { type null }\nctl.!default { type null }\n")
            }
            ensureUsrmergeShell(rootfs)
            if (!hasValidRootfs(rootfs)) {
                return Result.failure(
                    IllegalStateException(
                        "安裝後運行時不完整（系統殼層／連結器缺失）。$USER_HINT_REINSTALL_RUNTIME",
                    ),
                )
            }
            val boxDest = File(rootfs, "usr/local/bin/box64")
            if (!boxDest.isFile || !isGlibcBox64(boxDest)) {
                return Result.failure(
                    IllegalStateException(
                        "安裝後找不到可用的 Box64（客體轉譯器）。$USER_HINT_REINSTALL_RUNTIME",
                    ),
                )
            }
            ensureWineLaunchScript(ctx)
            ExecBits.forceEngineBins(EngineAssets.engineRoot(ctx), root)
            ensureProot(ctx)
            // 刷新 stamp（含 rootfs 校驗）
            File(root, "VERSION.json").writeText(
                JSONObject()
                    .put("runtimeId", runtimeId)
                    .put("installedAt", System.currentTimeMillis())
                    .put("rootfsShell", shellPathInRootfs(rootfs))
                    .put("rootfsOk", true)
                    .toString(2),
            )
            onProgress(Progress("ready", message = "運行時安裝完成（shell=${shellPathInRootfs(rootfs)}）"))
            return Result.success(root)
                } catch (t: Throwable) {
            if (isSymlinkLoopMessage(t)) {
                // 半殘 usrmerge 自指環：清掉讓用戶重裝即可
                runCatching { deleteRootfsSafely(File(runtimeRoot(ctx), "rootfs")) }
                return Result.failure(
                    IllegalStateException(
                        "安裝失敗：運行時目錄出現符號連結循環（常為升級／重解殘留）。" +
                            USER_HINT_REINSTALL_RUNTIME,
                        t,
                    ),
                )
            }
            return Result.failure(t) } finally {
            busy.set(false)
        }
    }

    private fun checkCancelled() {
        if (cancelRequested.get()) throw CancelledException()
    }

    /**
     * 斷點續傳：已有完整檔（sha 吻合或長度＝Content-Length）則跳過；
     * 否則 Range 續傳／重下。支援 [requestCancel]。
     */
    private fun downloadResumable(
        url: String,
        dest: File,
        expectSha: String,
        progress: (Long, Long) -> Unit,
    ) {
        checkCancelled()
        if (dest.exists() && dest.length() > 0L) {
            if (expectSha.isNotBlank()) {
                val got = runCatching { sha256(dest) }.getOrDefault("")
                if (got.equals(expectSha, ignoreCase = true)) {
                    progress(dest.length(), dest.length())
                    return
                }
            }
        }
        var attempt = 0
        var lastErr: Throwable? = null
        while (attempt < 3) {
            attempt++
            checkCancelled()
            try {
                downloadOnce(url, dest, progress)
                return
            } catch (c: CancelledException) {
                throw c
            } catch (t: Throwable) {
                lastErr = t
                // 保留部分檔供續傳
            }
        }
        throw lastErr ?: IllegalStateException("下載失敗：$url")
    }

    private fun downloadOnce(url: String, dest: File, progress: (Long, Long) -> Unit) {
        dest.parentFile?.mkdirs()
        val existing = if (dest.exists()) dest.length() else 0L
        // HEAD：無 sha 時用 Content-Length 判斷是否已完整
        val remoteLen = runCatching {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 15_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "x86-in-android/0.1")
            }
            val cl = c.contentLengthLong
            c.disconnect()
            cl
        }.getOrDefault(-1L)
        if (existing > 0L && remoteLen > 0L && existing == remoteLen) {
            progress(existing, remoteLen)
            return
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "x86-in-android/0.1")
            if (existing > 0L && (remoteLen < 0L || existing < remoteLen)) {
                setRequestProperty("Range", "bytes=$existing-")
            }
        }
        val code = conn.responseCode
        val append: Boolean
        val total: Long
        when (code) {
            HttpURLConnection.HTTP_PARTIAL -> {
                append = true
                val cr = conn.getHeaderField("Content-Range") // bytes a-b/total
                total = cr?.substringAfter('/')?.toLongOrNull()
                    ?: (existing + conn.contentLengthLong.coerceAtLeast(0L))
            }
            HttpURLConnection.HTTP_OK -> {
                // 伺服器忽略 Range → 重頭
                append = false
                total = conn.contentLengthLong.coerceAtLeast(0L)
                if (existing > 0L) dest.delete()
            }
            416 -> { // Range Not Satisfiable → 已完整
                conn.disconnect()
                progress(existing, existing)
                return
            }
            else -> {
                conn.disconnect()
                throw IllegalStateException("HTTP $code for $url")
            }
        }
        conn.inputStream.use { input ->
            FileOutputStream(dest, append).use { output ->
                val buf = ByteArray(1024 * 1024)
                var got = if (append) existing else 0L
                while (true) {
                    checkCancelled()
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                    got += n
                    progress(got, total.coerceAtLeast(got))
                }
            }
        }
        conn.disconnect()
    }

    /** @deprecated 內部改走 downloadResumable */
    private fun download(url: String, dest: File, progress: (Long, Long) -> Unit) {
        downloadResumable(url, dest, "", progress)
    }

    private fun extractRootfs(archive: File, dest: File, stripPrefix: String) {
        dest.mkdirs()
        extractTarXz(archive, dest, skipDevice = true, stripPrefix = stripPrefix)
        ensureUsrmergeShell(dest)
        if (!hasValidRootfs(dest)) {
            throw IllegalStateException(
                "運行時解壓後不完整。$USER_HINT_REINSTALL_RUNTIME",
            )
        }
    }

    /**
     * Ubuntu Noble usrmerge＋動態連結器：
     * - bin→usr/bin、sbin→usr/sbin、lib→usr/lib
     * - usr/bin/sh→dash（或 bash）
     * - /lib/ld-linux-aarch64.so.1 必須可解析（dash PT_INTERP；缺則 execve ENOENT）
     * symlink 失敗時改 copy 真實檔，避免裝置不支援 symlink 或解壓靜默跳過。
     */
    fun ensureUsrmergeShell(rootfs: File) {
        if (!rootfs.isDirectory) return
        // 若先前誤把 usr/bin 建成自指 symlink，先拆掉以免 isDirectory／canonical ELOOP
        for (name in listOf("usr/bin", "usr/lib", "usr/sbin")) {
            val f = File(rootfs, name)
            runCatching {
                if (Files.isSymbolicLink(f.toPath())) {
                    val tgt = Files.readSymbolicLink(f.toPath()).toString()
                    val parent = f.parentFile?.toPath() ?: return@runCatching
                    if (parent.resolve(tgt).normalize() == f.toPath().toAbsolutePath().normalize() ||
                        tgt == name.substringAfterLast('/') ||
                        tgt == name
                    ) {
                        Files.deleteIfExists(f.toPath())
                    }
                }
            }
        }
        val usrBin = File(rootfs, "usr/bin")
        val usrLib = File(rootfs, "usr/lib")
        // isDirectory 跟隨 symlink；若仍 ELOOP 則當缺失
        val usrBinOk = runCatching { usrBin.isDirectory }.getOrDefault(false)
        val usrLibOk = runCatching { usrLib.isDirectory }.getOrDefault(false)
        if (usrBinOk) {
            ensureRelativeSymlink(File(rootfs, "bin"), "usr/bin")
            ensureRelativeSymlink(File(rootfs, "sbin"), "usr/sbin")
        }
        if (usrLibOk) {
            ensureRelativeSymlink(File(rootfs, "lib"), "usr/lib")
        }

        val dash = File(usrBin, "dash")
        val bash = File(usrBin, "bash")
        val shellSrc = when {
            resolveRegularFile(dash) != null -> dash
            resolveRegularFile(bash) != null -> bash
            else -> null
        }
        if (shellSrc != null) {
            // /usr/bin/sh
            ensureSymlinkOrCopy(File(usrBin, "sh"), shellSrc.name, shellSrc)
            // /bin/sh：若 bin 已是 usrmerge symlink，上面的 usr/bin/sh 已覆蓋；
            // 若 bin 是真實目錄，再補一份。
            val binDir = File(rootfs, "bin")
            if (binDir.isDirectory && !Files.isSymbolicLink(binDir.toPath())) {
                ensureSymlinkOrCopy(File(binDir, "sh"), "../usr/bin/${shellSrc.name}", shellSrc)
            }
            ExecBits.force(shellSrc)
        }

        ensureDynamicLinker(rootfs)

        // 再 force 常見 shell 實體
        resolveRegularFile(dash)?.let { ExecBits.force(it) }
        resolveRegularFile(bash)?.let { ExecBits.force(it) }
    }

    /** 確保 PT_INTERP `/lib/ld-linux-aarch64.so.1` 在 rootfs 內可開 */
    private fun ensureDynamicLinker(rootfs: File) {
        if (hasDynamicLinker(rootfs)) {
            // 仍保證 usrmerge lib→usr/lib，方便 INTERP 路徑 /lib/...
            if (File(rootfs, "usr/lib").isDirectory) {
                ensureRelativeSymlink(File(rootfs, "lib"), "usr/lib")
            }
            // 若只有 aarch64-linux-gnu 實體，補 usr/lib 與 lib 入口
        }
        val realCandidates = listOf(
            File(rootfs, "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"),
            File(rootfs, "lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"),
            File(rootfs, "usr/lib/ld-linux-aarch64.so.1"),
            File(rootfs, "lib/ld-linux-aarch64.so.1"),
        )
        val real = realCandidates.mapNotNull { resolveRegularFile(it) }.firstOrNull()
            ?: return
        ExecBits.force(real)

        // usr/lib/ld-linux-aarch64.so.1
        val usrLibLd = File(rootfs, "usr/lib/ld-linux-aarch64.so.1")
        if (resolveRegularFile(usrLibLd) == null) {
            val rel = runCatching {
                File(rootfs, "usr/lib").toPath().relativize(real.toPath()).toString()
            }.getOrDefault("aarch64-linux-gnu/ld-linux-aarch64.so.1")
            ensureSymlinkOrCopy(usrLibLd, rel, real)
        }

        // lib → usr/lib（usrmerge）後 /lib/ld-linux 即可用；若 symlink 失敗則直接落地 lib/
        ensureRelativeSymlink(File(rootfs, "lib"), "usr/lib")
        val libLd = File(rootfs, "lib/ld-linux-aarch64.so.1")
        if (resolveRegularFile(libLd) == null) {
            // lib 可能仍是真目錄（symlink 失敗）；若已是 symlink 則勿 mkdirs 跟進去
            val libDir = File(rootfs, "lib")
            if (!Files.isSymbolicLink(libDir.toPath())) {
                libDir.mkdirs()
            }
            ensureSymlinkOrCopy(libLd, "aarch64-linux-gnu/ld-linux-aarch64.so.1", real)
            if (resolveRegularFile(libLd) == null) {
                // 最後手段：複製實體到 /lib/ld-linux-aarch64.so.1
                real.copyTo(libLd, overwrite = true)
                ExecBits.force(libLd)
            }
        }
    }

    /** 建相對 symlink；失敗則 copy 真實檔 */
    private fun ensureSymlinkOrCopy(link: File, targetRel: String, copyFrom: File) {
        if (resolveRegularFile(link)?.absolutePath == resolveRegularFile(copyFrom)?.absolutePath) {
            return
        }
        val linked = ensureRelativeSymlink(link, targetRel)
        if (!linked || resolveRegularFile(link) == null) {
            link.parentFile?.mkdirs()
            runCatching { deleteSelfNoFollow(link) }
            copyFrom.copyTo(link, overwrite = true)
            ExecBits.force(link)
        }
    }

    /** @return true if symlink exists with desired target */
    private fun ensureRelativeSymlink(link: File, targetRel: String): Boolean {
        try {
            // 禁止自指：lib → lib、usr/bin → usr/bin
            val parent = link.parentFile?.toPath() ?: return false
            val resolved = parent.resolve(targetRel).normalize()
            if (resolved == link.toPath().toAbsolutePath().normalize()) {
                android.util.Log.w("ComponentInstaller", "refuse self-symlink ${link.name} → $targetRel")
                return false
            }
            if (Files.isSymbolicLink(link.toPath())) {
                val cur = runCatching { Files.readSymbolicLink(link.toPath()).toString() }.getOrDefault("")
                if (cur == targetRel || cur.endsWith(targetRel)) return true
                Files.deleteIfExists(link.toPath())
            } else if (link.exists()) {
                // 錯誤地被建成空目錄／檔 → 清掉再連（不跟隨）
                deleteSelfNoFollow(link)
            }
            link.parentFile?.mkdirs()
            Files.createSymbolicLink(link.toPath(), Paths.get(targetRel))
            return Files.isSymbolicLink(link.toPath())
        } catch (t: Throwable) {
            android.util.Log.w("ComponentInstaller", "symlink ${link.name} → $targetRel: ${t.message}")
            return false
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
                if (!src.renameTo(dst)) {
                    // 跨裝置／失敗時改 copy
                    src.copyRecursively(dst, overwrite = true)
                    src.deleteRecursively()
                }
            }
        }
        val wineRoot = File(optDir, to.ifBlank { "wine" })
        val ntdll = File(wineRoot, "lib/wine/x86_64-unix/ntdll.so")
        val wineBin = File(wineRoot, "bin/wine")
        if (!wineBin.isFile || !ntdll.isFile || ntdll.length() < 1024L) {
            throw IllegalStateException(
                "Wine 解壓不完整（ntdll.so／wine 缺失）。$USER_HINT_REINSTALL_RUNTIME",
            )
        }
        android.util.Log.i(
            "ComponentInstaller",
            "wine ok bin=${wineBin.length()}B ntdll=${ntdll.length()}B → ${wineRoot.absolutePath}",
        )
    }

    /**
     * 客體 Box64 必須是 **glibc**（PT_INTERP `/lib/ld-linux-aarch64.so.1`，NEEDED libc.so.6）。
     * Nightlies「Bionic／Native」.wcp 都是 `/system/bin/linker64`，在 Ubuntu proot 內
     * exec 會被 dash 報 `box64: not found`（ENOENT on missing interpreter）。
     */
    fun isGlibcBox64(file: File): Boolean {
        if (!file.isFile || file.length() < 4096L) return false
        return runCatching {
            file.inputStream().use { input ->
                val buf = ByteArray(minOf(file.length(), 2_000_000L).toInt())
                var off = 0
                while (off < buf.size) {
                    val n = input.read(buf, off, buf.size - off)
                    if (n <= 0) break
                    off += n
                }
                val bytes = buf.copyOf(off)
                val asStr = runCatching { String(bytes, Charsets.ISO_8859_1) }.getOrDefault("")
                val hasBionic = asStr.contains("/system/bin/linker64") ||
                    asStr.contains("linker64") && asStr.contains("Android")
                val hasGlibcInterp = asStr.contains("ld-linux-aarch64.so.1")
                val hasGlibcLibc = asStr.contains("libc.so.6")
                !hasBionic && hasGlibcInterp && hasGlibcLibc
            }
        }.getOrDefault(false)
    }

    /** 僅檢查 glibc ELF（不含 stamp；給 baseOk 判斷用）。 */
    fun hasGlibcBox64Elf(rootfs: File): Boolean =
        isGlibcBox64(File(rootfs, "usr/local/bin/box64"))

    /** glibc Box64 + Wine11-aligned prereserve stamp（0.1.37+：low 0x100000）。舊 stamp 無 align-0x100000 → 強制重抽。 */
    fun hasGlibcBox64(rootfs: File): Boolean {
        val stamp = File(rootfs, "usr/local/bin/.box64-wine64k-stamp")
        if (!hasGlibcBox64Elf(rootfs) || !stamp.isFile) return false
        val body = runCatching { stamp.readText() }.getOrDefault("")
        return body.contains("wine64k-align-0x100000")
    }

    private fun extractBox64(archive: File, binDir: File) {
        binDir.mkdirs()
        val tmp = File(binDir.parentFile, "_box64_extract").also {
            it.deleteRecursively()
            it.mkdirs()
        }
        extractTarXz(archive, tmp)
        val box = tmp.walkTopDown().firstOrNull { it.isFile && it.name == "box64" }
            ?: run {
                tmp.deleteRecursively()
                throw IllegalStateException(
                    "下載的 Box64 組件不完整（找不到可執行檔）。$USER_HINT_REINSTALL_RUNTIME",
                )
            }
        if (!isGlibcBox64(box)) {
            tmp.deleteRecursively()
            throw IllegalStateException(
                "Box64 組件版本不正確（無法在本運行時執行）。$USER_HINT_REINSTALL_RUNTIME",
            )
        }
        val dest = File(binDir, "box64")
        box.copyTo(dest, overwrite = true)
        ExecBits.force(dest)
        if (!dest.isFile || dest.length() < 4096L || !isGlibcBox64(dest)) {
            dest.delete()
            throw IllegalStateException(
                "Box64 安裝後校驗失敗（檔案缺失或版本不符）。$USER_HINT_REINSTALL_RUNTIME",
            )
        }
        // 可選：一併落下 profile.json
        tmp.walkTopDown().firstOrNull { it.isFile && it.name == "profile.json" }?.let { prof ->
            prof.copyTo(File(binDir, "box64.profile.json"), overwrite = true)
        }
        // Wine11 64k-aligned prereserve stamp（0.1.34+）；缺此 stamp 會強制重抽
        val stampSrc = tmp.walkTopDown().firstOrNull { it.isFile && it.name == ".box64-wine64k-stamp" }
        val stampDest = File(binDir, ".box64-wine64k-stamp")
        if (stampSrc != null) {
            stampSrc.copyTo(stampDest, overwrite = true)
        } else {
            stampDest.writeText("wine64k-align-0x100000-2026-11-09\n")
        }
        // 0.1.40: new box64 binary invalidates sticky hole-fit cache
        File(binDir, ".box64-wine64k-holefit").delete()
        tmp.deleteRecursively()
        android.util.Log.i(
            "ComponentInstaller",
            "installed glibc box64 wine64k → ${dest.absolutePath} (${dest.length()} bytes) stamp=${stampDest.readText().trim()}; cleared holefit marker",
        )
    }

    /** XZ stream magic: FD 37 7A 58 5A 00 ("\xFD7zXZ\0"). */
    private fun requireXzMagic(archive: File) {
        val hdr = ByteArray(6)
        val n = FileInputStream(archive).use { it.read(hdr) }
        val ok = n == 6 &&
            hdr[0] == 0xFD.toByte() &&
            hdr[1] == '7'.code.toByte() &&
            hdr[2] == 'z'.code.toByte() &&
            hdr[3] == 'X'.code.toByte() &&
            hdr[4] == 'Z'.code.toByte() &&
            hdr[5] == 0.toByte()
        if (!ok) {
            val preview = hdr.take(n.coerceAtLeast(0)).joinToString(" ") { "%02x".format(it) }
            val looksTar = n >= 5 && hdr[0] == 'b'.code.toByte() // e.g. raw ustar named box64
            val hint = if (looksTar) {
                "下載到的是未壓縮 tar（非 .xz）。請更新 App 或重裝運行時拉正確組件。"
            } else {
                "下載內容不是 XZ 壓縮檔（可能是 HTML／錯誤頁或損毀檔）。請重試；$USER_HINT_REINSTALL_RUNTIME"
            }
            throw IllegalStateException(
                "組件解壓失敗：Input is not in the XZ format（magic=$preview）。$hint",
            )
        }
    }

    private fun extractTarXz(
        archive: File,
        dest: File,
        skipDevice: Boolean = false,
        stripPrefix: String = "",
    ) {
        dest.mkdirs()
        requireXzMagic(archive)
        android.util.Log.i(
            "ComponentInstaller",
            "extractTarXz buffer=1MiB parallel-write archive=${archive.name} -> ${dest.name}",
        )
        val strip = stripPrefix.trim().trim('/')
        val stripWithSlash = if (strip.isNotEmpty()) "$strip/" else ""
        val nWorkers = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val pool = Executors.newFixedThreadPool(nWorkers)
        val pending = mutableListOf<Future<*>>()
        val writeErrors = AtomicInteger(0)
        var firstError: Throwable? = null
        val writeLock = Any()
        fun drainFailures() {
            val err = firstError
            if (err != null) throw err
            if (writeErrors.get() > 0) {
                throw IllegalStateException("extractTarXz parallel write failures=${writeErrors.get()}")
            }
        }
        try {
            FileInputStream(archive).use { fis ->
                BufferedInputStream(fis, 1024 * 1024).use { bis ->
                    XZInputStream(bis).use { xzis ->
                        TarArchiveInputStream(xzis).use { tis ->
                            var entry: TarArchiveEntry? = tis.nextEntry
                            while (entry != null) {
                                drainFailures()
                                var name = entry.name.removePrefix("./")
                                if (strip.isNotEmpty()) {
                                    when {
                                        name == strip -> {
                                            entry = tis.nextEntry
                                            continue
                                        }
                                        name.startsWith(stripWithSlash) ->
                                            name = name.removePrefix(stripWithSlash)
                                        else -> {
                                            entry = tis.nextEntry
                                            continue
                                        }
                                    }
                                }
                                if (name.isEmpty()) {
                                    entry = tis.nextEntry
                                    continue
                                }
                                if (skipDevice && (name == "dev" || name.startsWith("dev/") || name.contains("/dev/"))) {
                                    entry = tis.nextEntry
                                    continue
                                }
                                val out = lexicalResolveUnder(dest, name)
                                if (out == null) {
                                    entry = tis.nextEntry
                                    continue
                                }
                                when {
                                    entry.isDirectory -> {
                                        if (!Files.isSymbolicLink(out.toPath())) {
                                            out.mkdirs()
                                        }
                                    }
                                    entry.isSymbolicLink -> {
                                        out.parentFile?.mkdirs()
                                        val target = entry.linkName ?: ""
                                        val critical = name == "bin" || name == "lib" || name == "sbin" ||
                                            name == "usr/bin/sh" || name.endsWith("/ld-linux-aarch64.so.1")
                                        fun linkOrLog() {
                                            try {
                                                val parent = out.parentFile?.toPath() ?: dest.toPath()
                                                val resolvedTarget = parent.resolve(target).normalize()
                                                if (resolvedTarget == out.toPath().toAbsolutePath().normalize()) {
                                                    android.util.Log.w(
                                                        "ComponentInstaller",
                                                        "skip self-referential symlink $name → $target",
                                                    )
                                                    if (critical) {
                                                        throw IllegalStateException(
                                                            "符號連結自指：$name → $target",
                                                        )
                                                    }
                                                    return
                                                }
                                                deleteSelfNoFollow(out)
                                                Files.createSymbolicLink(out.toPath(), Paths.get(target))
                                            } catch (t: Throwable) {
                                                android.util.Log.w(
                                                    "ComponentInstaller",
                                                    "extract symlink $name → $target: ${t.message}",
                                                )
                                                if (critical) throw t
                                            }
                                        }
                                        if (target.isNotBlank() && !target.startsWith("/") && ".." !in target.split('/')) {
                                            linkOrLog()
                                        } else if (target.isNotBlank() && !target.startsWith("/")) {
                                            val destPath = dest.toPath().toAbsolutePath().normalize()
                                            val resolved = out.parentFile!!.toPath().resolve(target).normalize()
                                            if (resolved.startsWith(destPath)) {
                                                linkOrLog()
                                            }
                                        } else if (critical && target.isNotBlank()) {
                                            android.util.Log.w(
                                                "ComponentInstaller",
                                                "skip absolute symlink $name → $target",
                                            )
                                        }
                                    }
                                    entry.isLink -> {
                                        out.parentFile?.mkdirs()
                                        val linkTarget = entry.linkName ?: ""
                                        val src = if (strip.isNotEmpty() && linkTarget.startsWith(stripWithSlash)) {
                                            File(dest, linkTarget.removePrefix(stripWithSlash))
                                        } else if (strip.isNotEmpty() && linkTarget.startsWith("$strip/")) {
                                            File(dest, linkTarget.removePrefix("$strip/"))
                                        } else {
                                            File(dest, linkTarget.removePrefix("./"))
                                        }
                                        if (src.isFile) {
                                            src.copyTo(out, overwrite = true)
                                            if (entry.mode and 0b001001001 != 0) ExecBits.force(out)
                                        }
                                    }
                                    else -> {
                                        out.parentFile?.mkdirs()
                                        val mode = entry.mode
                                        val size = entry.size
                                        // Parallel write for modest files: read fully then pool.write
                                        // Large files stay synchronous to bound memory.
                                        val parallelMax = 8L * 1024L * 1024L
                                        if (size in 1..parallelMax) {
                                            val bytes = ByteArray(size.toInt())
                                            var off = 0
                                            while (off < bytes.size) {
                                                val n = tis.read(bytes, off, bytes.size - off)
                                                if (n <= 0) break
                                                off += n
                                            }
                                            val destFile = out
                                            pending.add(
                                                pool.submit {
                                                    try {
                                                        FileOutputStream(destFile).use { fos ->
                                                            BufferedOutputStream(fos, 1024 * 1024).use { bos ->
                                                                bos.write(bytes, 0, off)
                                                                bos.flush()
                                                            }
                                                        }
                                                        if (mode and 0b001001001 != 0) {
                                                            ExecBits.force(destFile)
                                                        }
                                                    } catch (t: Throwable) {
                                                        writeErrors.incrementAndGet()
                                                        synchronized(writeLock) {
                                                            if (firstError == null) firstError = t
                                                        }
                                                    }
                                                },
                                            )
                                        } else {
                                            val buf = ByteArray(1024 * 1024)
                                            FileOutputStream(out).use { fos ->
                                                BufferedOutputStream(fos, buf.size).use { bos ->
                                                    while (true) {
                                                        val n = tis.read(buf)
                                                        if (n <= 0) break
                                                        bos.write(buf, 0, n)
                                                    }
                                                    bos.flush()
                                                }
                                            }
                                            if (mode and 0b001001001 != 0) {
                                                ExecBits.force(out)
                                            }
                                        }
                                    }
                                }
                                entry = tis.nextEntry
                            }
                        }
                    }
                }
            }
            for (f in pending) {
                f.get()
            }
            drainFailures()
        } finally {
            pool.shutdown()
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
        if (executable) ExecBits.force(dest)
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
            |BOX64=/usr/local/bin/box64
            |if [ ! -x "${d}BOX64" ] && [ ! -f "${d}BOX64" ]; then
            |  echo "FATAL: box64 missing" >> /mnt/data/gpu-stack.log; exit 127
            |fi
            |"${d}BOX64" /opt/wine/bin/wine explorer /desktop=x86,${d}{W}x${d}{H} "${d}@"
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
