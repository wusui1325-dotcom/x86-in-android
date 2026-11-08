package com.wusper.x86

import android.content.Context
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
