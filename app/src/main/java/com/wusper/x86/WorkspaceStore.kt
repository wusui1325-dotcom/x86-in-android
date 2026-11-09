package com.wusper.x86

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * 每個 exe 在 App 私有 data 下有一個專屬工作區目錄。
 * 路徑：files/workspaces/<id>/
 *   - meta.json   來源 exe 資訊
 *   - exe/        可放本體副本（空殼暫只記路徑）
 *   - data/       儲存／額外資源下載落點
 */
data class Workspace(
    val id: String,
    val displayName: String,
    val sourceExePath: String,
    val sourceExeName: String,
    val createdAt: Long,
    val lastLaunchedAt: Long,
    val dir: File,
) {
    val dataDir: File get() = File(dir, "data")
    val exeDir: File get() = File(dir, "exe")
}

data class DeviceExe(
    val displayName: String,
    val path: String,
    val uriString: String?,
)

object WorkspaceStore {
    private const val ROOT = "workspaces"
    private const val INDEX = "index.json"
    private const val META = "meta.json"
    private const val PICKED = "picked_exes.json"

    fun root(ctx: Context): File =
        File(ctx.filesDir, ROOT).also { it.mkdirs() }

    fun listWorkspaces(ctx: Context): List<Workspace> {
        val root = root(ctx)
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: emptyList()
        return dirs.mapNotNull { dir ->
            val meta = File(dir, META)
            if (!meta.exists()) return@mapNotNull null
            runCatching {
                val o = JSONObject(meta.readText())
                Workspace(
                    id = o.getString("id"),
                    displayName = o.optString("displayName", dir.name),
                    sourceExePath = o.optString("sourceExePath", ""),
                    sourceExeName = o.optString("sourceExeName", ""),
                    createdAt = o.optLong("createdAt", 0L),
                    lastLaunchedAt = o.optLong("lastLaunchedAt", 0L),
                    dir = dir,
                )
            }.getOrNull()
        }.sortedByDescending { it.lastLaunchedAt.takeIf { t -> t > 0 } ?: it.createdAt }
    }

    /** 已有工作區的來源 exe **路徑**集合（僅路徑；同檔名不同路徑不互相隱藏）。 */
    fun coveredExeKeys(ctx: Context): Set<String> {
        val keys = mutableSetOf<String>()
        for (ws in listWorkspaces(ctx)) {
            if (ws.sourceExePath.isNotBlank()) keys += normalizeKey(ws.sourceExePath)
        }
        return keys
    }

    /**
     * 僅隱藏「同一絕對路徑／URI」已有工作區的 exe；同檔名不同路徑仍顯示。
     */
    fun filterVisibleExes(ctx: Context, exes: List<DeviceExe>): List<DeviceExe> {
        val covered = coveredExeKeys(ctx)
        if (covered.isEmpty()) return exes
        return exes.filterNot { exe ->
            normalizeKey(exe.path) in covered
        }
    }

    fun ensureWorkspace(ctx: Context, exe: DeviceExe): Workspace {
        val existing = findByExe(ctx, exe)
        if (existing != null) return existing

        val id = idFor(exe)
        val dir = File(root(ctx), id)
        dir.mkdirs()
        File(dir, "data").mkdirs()
        File(dir, "exe").mkdirs()
        val now = System.currentTimeMillis()
        val ws = Workspace(
            id = id,
            displayName = exe.displayName.removeSuffixIgnoreCase(".exe"),
            sourceExePath = exe.path,
            sourceExeName = exe.displayName,
            createdAt = now,
            lastLaunchedAt = now,
            dir = dir,
        )
        writeMeta(ws)
        touchIndex(ctx)
        return ws
    }

    fun touchLaunch(ctx: Context, ws: Workspace): Workspace {
        val updated = ws.copy(lastLaunchedAt = System.currentTimeMillis())
        writeMeta(updated)
        touchIndex(ctx)
        return updated
    }

    fun findByExe(ctx: Context, exe: DeviceExe): Workspace? {
        val keyPath = normalizeKey(exe.path)
        if (keyPath.isBlank()) return null
        return listWorkspaces(ctx).firstOrNull {
            normalizeKey(it.sourceExePath) == keyPath
        }
    }

    /**
     * 刪除工作區目錄（含 wineprefix／logs／exe 副本）。
     * [deleteFiles]=false 時僅從索引移除 meta（保留 dir 於磁碟，進階用）。
     * 預設刪除 App 私有 files/workspaces/<id>；**不**動 files/engine。
     */
    fun deleteWorkspace(ctx: Context, ws: Workspace, deleteFiles: Boolean = true): Boolean {
        return runCatching {
            if (deleteFiles) {
                if (ws.dir.exists()) ws.dir.deleteRecursively()
            } else {
                File(ws.dir, META).delete()
            }
            // 同步裝置 exe 列表：保留 picked（用戶可再啟動成新工作區）
            touchIndex(ctx)
            true
        }.getOrDefault(false)
    }


    /** wineprefix 目錄（dirty 偵測／重建用） */
    fun winePrefix(ws: Workspace): File = File(ws.dataDir, "wineprefix")

    /**
     * 半殘工作區：有 wineprefix 但缺 system.reg／#arch，或殘留 .update-timestamp 且
     * system.reg 過小。**僅偵測**；不自動刪。
     */
    fun isWorkspaceDirty(ws: Workspace): Boolean {
        val prefix = winePrefix(ws)
        if (!prefix.exists()) return false
        val systemReg = File(prefix, "system.reg")
        val userReg = File(prefix, "user.reg")
        if (!systemReg.exists() && (userReg.exists() || prefix.list()?.isNotEmpty() == true)) {
            return true // half-init
        }
        if (systemReg.exists()) {
            val bytes = systemReg.length()
            if (bytes in 1 until 8_000L) return true // truncated / stub
            // missing drive_c after wineboot started
            if (!File(prefix, "drive_c").exists()) return true
        }
        // empty exe seed but meta claims a source — user may need re-import
        val staged = File(ws.exeDir, "app.exe")
        if (ws.sourceExePath.isNotBlank() && !staged.exists() && !File(ws.sourceExePath).exists()) {
            // only dirty if prefix also looks broken
            if (systemReg.exists() && systemReg.length() < 30_000L) return true
        }
        return false
    }

    /**
     * 新建乾淨工作區（空 wineprefix）。可選綁定來源 exe（只寫 meta／可稍後 import）。
     * **不**刪既有工作區。
     */
    fun createCleanWorkspace(
        ctx: Context,
        displayName: String,
        sourceExe: DeviceExe? = null,
    ): Workspace {
        val now = System.currentTimeMillis()
        val safe = displayName
            .removeSuffixIgnoreCase(".exe")
            .replace(Regex("[^A-Za-z0-9._\\-]+"), "_")
            .take(24)
            .ifBlank { "ws" }
        val id = "${safe}_clean_${now.toString(16).takeLast(8)}"
        val dir = File(root(ctx), id)
        dir.mkdirs()
        File(dir, "data").mkdirs()
        File(dir, "exe").mkdirs()
        File(dir, "data/wineprefix").mkdirs()
        val ws = Workspace(
            id = id,
            displayName = displayName.removeSuffixIgnoreCase(".exe").ifBlank { safe },
            sourceExePath = sourceExe?.path.orEmpty(),
            sourceExeName = sourceExe?.displayName.orEmpty(),
            createdAt = now,
            lastLaunchedAt = now,
            dir = dir,
        )
        writeMeta(ws)
        touchIndex(ctx)
        return ws
    }

    /**
     * 精確匯入：把 .exe 複製進**新**專屬工作區 exe/app.exe（乾淨 prefix）。
     * 與 ensureWorkspace（可能复用舊 dirty prefix）不同。
     */
    fun importExeToNewWorkspace(ctx: Context, exe: DeviceExe, copyFrom: java.io.InputStream? = null): Workspace {
        val ws = createCleanWorkspace(ctx, exe.displayName.removeSuffixIgnoreCase(".exe").ifBlank { "app" }, exe)
        val staged = File(ws.exeDir, "app.exe")
        runCatching {
            if (copyFrom != null) {
                copyFrom.use { input -> staged.outputStream().use { input.copyTo(it) } }
            } else {
                val srcPath = exe.path
                when {
                    srcPath.startsWith("content:", true) || srcPath.startsWith("file:", true) -> {
                        // caller should pass copyFrom for content URIs
                    }
                    srcPath.isNotBlank() && File(srcPath).isFile -> {
                        File(srcPath).copyTo(staged, overwrite = true)
                    }
                }
            }
        }
        writeMeta(ws)
        addPickedExe(ctx, exe)
        return ws
    }

    /**
     * 用戶確認後重建 prefix：只刪 data/wineprefix（與暫存 log），**保留** exe/ 與 meta。
     * 絕不默默刪。
     */
    fun rebuildPrefix(ctx: Context, ws: Workspace): Boolean {
        return runCatching {
            val prefix = winePrefix(ws)
            if (prefix.exists()) prefix.deleteRecursively()
            prefix.mkdirs()
            // clear volatile logs that poison dig
            listOf("wine-run.out", "wineboot-init.out", "wine-rpcss.out", "app-load.stamp", "gpu-stack.log")
                .forEach { name -> File(ws.dataDir, name).delete() }
            touchIndex(ctx)
            true
        }.getOrDefault(false)
    }

    /**
     * 掃描裝置上的 .exe（需 All files / 舊版 READ_EXTERNAL）。
     * 有界深度與數量，避免 ANR。
     */
    fun scanDeviceExes(maxFiles: Int = 400, maxDepth: Int = 6): List<DeviceExe> {
        val roots = mutableListOf<File>()
        Environment.getExternalStorageDirectory()?.let { roots += it }
        listOf(
            "/storage/emulated/0",
            "/sdcard",
            "/storage/self/primary",
        ).forEach { p ->
            val f = File(p)
            if (f.isDirectory && roots.none { it.absolutePath == f.absolutePath }) roots += f
        }
        // common download / game folders first
        val preferred = listOf("Download", "Downloads", "Games", "Documents", "Tencent", "baidu", "Obb", "obb")
        val out = LinkedHashMap<String, DeviceExe>()
        fun consider(file: File) {
            if (!file.isFile) return
            if (!file.name.endsWith(".exe", ignoreCase = true)) return
            val path = file.absolutePath
            val key = normalizeKey(path)
            if (key in out) return
            out[key] = DeviceExe(displayName = file.name, path = path, uriString = null)
        }
        fun walk(dir: File, depth: Int) {
            if (out.size >= maxFiles) return
            if (depth > maxDepth) return
            val kids = dir.listFiles() ?: return
            for (f in kids) {
                if (out.size >= maxFiles) return
                if (f.isFile) consider(f)
            }
            val dirs = kids.filter { it.isDirectory && !it.name.startsWith(".") }
                .sortedBy { d ->
                    val i = preferred.indexOfFirst { pref -> d.name.equals(pref, ignoreCase = true) }
                    if (i >= 0) i else 100
                }
            for (d in dirs) {
                if (out.size >= maxFiles) return
                if (d.name.equals("Android", true) && depth == 0) {
                    val media = File(d, "media")
                    if (media.isDirectory) walk(media, depth + 1)
                    continue
                }
                walk(d, depth + 1)
            }
        }
        for (r in roots) {
            if (r.isDirectory) walk(r, 0)
        }
        return out.values.sortedBy { it.displayName.lowercase(Locale.US) }
    }

    
    fun addPickedExe(ctx: Context, exe: DeviceExe) {
        val arr = loadPicked(ctx)
        val key = normalizeKey(exe.path.ifBlank { exe.uriString ?: exe.displayName })
        val filtered = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val k = normalizeKey(
                o.optString("path").ifBlank { o.optString("uri").ifBlank { o.optString("displayName") } }
            )
            if (k != key) filtered.put(o)
        }
        filtered.put(
            JSONObject()
                .put("displayName", exe.displayName)
                .put("path", exe.path)
                .put("uri", exe.uriString ?: "")
        )
        File(root(ctx), PICKED).writeText(filtered.toString(2))
    }

    fun loadPickedExes(ctx: Context): List<DeviceExe> {
        val arr = loadPicked(ctx)
        val out = mutableListOf<DeviceExe>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += DeviceExe(
                displayName = o.optString("displayName", "unknown.exe"),
                path = o.optString("path", ""),
                uriString = o.optString("uri").takeIf { it.isNotBlank() },
            )
        }
        return out
    }

    private fun loadPicked(ctx: Context): JSONArray {
        val f = File(root(ctx), PICKED)
        if (!f.exists()) return JSONArray()
        return runCatching { JSONArray(f.readText()) }.getOrDefault(JSONArray())
    }

    private fun writeMeta(ws: Workspace) {
        val o = JSONObject()
            .put("id", ws.id)
            .put("displayName", ws.displayName)
            .put("sourceExePath", ws.sourceExePath)
            .put("sourceExeName", ws.sourceExeName)
            .put("createdAt", ws.createdAt)
            .put("lastLaunchedAt", ws.lastLaunchedAt)
        File(ws.dir, META).writeText(o.toString(2))
    }

    private fun touchIndex(ctx: Context) {
        val list = listWorkspaces(ctx)
        val arr = JSONArray()
        list.forEach { arr.put(it.id) }
        File(root(ctx), INDEX).writeText(arr.toString(2))
    }

    private fun idFor(exe: DeviceExe): String {
        val seed = (exe.path.ifBlank { exe.uriString ?: exe.displayName }).lowercase(Locale.US)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(seed.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)
        val safe = exe.displayName
            .removeSuffixIgnoreCase(".exe")
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .take(24)
            .ifBlank { "app" }
        return "${safe}_$digest"
    }

    private fun normalizeKey(s: String): String =
        s.trim().lowercase(Locale.US).replace('\\', '/')

    private fun String.removeSuffixIgnoreCase(suffix: String): String =
        if (endsWith(suffix, ignoreCase = true)) substring(0, length - suffix.length) else this
}
