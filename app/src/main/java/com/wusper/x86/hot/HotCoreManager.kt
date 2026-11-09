package com.wusper.x86.hot

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Hot-swap wine-launch.sh (+ helper scripts) without reinstalling the APK.
 *
 * Overlay: filesDir/engine/hot/{current,previous,staging}
 * [resolveOverlayFile] is consulted by ComponentInstaller before APK assets.
 */
object HotCoreManager {
    private const val TAG = "HotCore"
    private const val STATE = "state.json"

    data class ManifestFile(
        val path: String,
        val url: String,
        val sha256: String,
        val size: Long,
    )

    data class Manifest(
        val schema: Int,
        val coreVersion: String,
        val channel: String,
        val files: List<ManifestFile>,
        val notes: String,
        val minApkVersionCode: Int,
        val smokeWorkspaceId: String,
        val smokeWaitSeconds: Int,
    )

    data class ApplyResult(
        val updated: Boolean,
        val coreVersion: String,
        val message: String,
        val rolledBack: Boolean = false,
    )

    fun hotRoot(ctx: Context): File =
        File(ctx.filesDir, "engine/hot").also { it.mkdirs() }

    fun currentDir(ctx: Context): File = File(hotRoot(ctx), "current").also { it.mkdirs() }
    fun previousDir(ctx: Context): File = File(hotRoot(ctx), "previous").also { it.mkdirs() }
    fun stagingDir(ctx: Context): File = File(hotRoot(ctx), "staging").also { it.mkdirs() }

    fun installedCoreVersion(ctx: Context): String {
        val f = File(hotRoot(ctx), STATE)
        if (!f.isFile) return ""
        return runCatching { JSONObject(f.readText()).optString("coreVersion", "") }.getOrDefault("")
    }

    /** Prefer hot overlay file when present and non-empty. */
    fun resolveOverlayFile(ctx: Context, relativePath: String): File? {
        val f = File(currentDir(ctx), relativePath)
        return f.takeIf { it.isFile && it.length() > 0L }
    }

    fun fetchManifest(url: String): Manifest {
        require(url.isNotBlank()) { "manifestUrl empty" }
        val body = httpGetBytes(url).toString(Charsets.UTF_8)
        return parseManifest(body)
    }

    fun parseManifest(body: String): Manifest {
        val o = JSONObject(body)
        val schema = o.optInt("schema", 0)
        require(schema == 1) { "unsupported schema=$schema (want 1)" }
        val arr = o.getJSONArray("files")
        val files = buildList {
            for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                add(
                    ManifestFile(
                        path = f.getString("path").trim().trimStart('/'),
                        url = f.getString("url").trim(),
                        sha256 = f.getString("sha256").trim().lowercase(),
                        size = f.optLong("size", -1L),
                    ),
                )
            }
        }
        require(files.isNotEmpty()) { "manifest files[] empty" }
        val smoke = o.optJSONObject("smoke")
        return Manifest(
            schema = schema,
            coreVersion = o.getString("coreVersion").trim(),
            channel = o.optString("channel", "smokeHot"),
            files = files,
            notes = o.optString("notes", ""),
            minApkVersionCode = o.optInt("minApkVersionCode", 0),
            smokeWorkspaceId = smoke?.optString("workspaceId", "") ?: "",
            smokeWaitSeconds = smoke?.optInt("waitSeconds", 90) ?: 90,
        )
    }

    /**
     * Download+verify into staging, then atomic promote. On failure, rollback to previous.
     */
    fun applyIfNewer(ctx: Context, manifest: Manifest, apkVersionCode: Int): ApplyResult {
        val installed = installedCoreVersion(ctx)
        if (manifest.coreVersion.isBlank()) {
            return ApplyResult(false, installed, "blank coreVersion")
        }
        if (manifest.minApkVersionCode > 0 && apkVersionCode < manifest.minApkVersionCode) {
            return ApplyResult(
                false,
                installed,
                "apk vc $apkVersionCode < minApkVersionCode ${manifest.minApkVersionCode}",
            )
        }
        if (installed == manifest.coreVersion) {
            return ApplyResult(false, installed, "already at ${manifest.coreVersion}")
        }

        val staging = stagingDir(ctx)
        staging.deleteRecursively()
        staging.mkdirs()

        return try {
            for (f in manifest.files) {
                require(!f.path.contains("..")) { "unsafe path ${f.path}" }
                val dest = File(staging, f.path)
                dest.parentFile?.mkdirs()
                val bytes = httpGetBytes(f.url)
                if (f.size > 0 && bytes.size.toLong() != f.size) {
                    error("size mismatch ${f.path}: got ${bytes.size} want ${f.size}")
                }
                val dig = sha256Hex(bytes)
                if (dig != f.sha256) {
                    error("sha256 mismatch ${f.path}: got $dig want ${f.sha256}")
                }
                FileOutputStream(dest).use { it.write(bytes) }
                if (f.path.endsWith(".sh") || f.path.endsWith(".py")) {
                    dest.setExecutable(true, false)
                }
                Log.i(TAG, "staged ${f.path} sha=$dig bytes=${bytes.size}")
            }
            promoteStaging(ctx, manifest.coreVersion)
            ApplyResult(true, manifest.coreVersion, "hot-applied ${manifest.coreVersion}")
        } catch (t: Throwable) {
            Log.e(TAG, "apply failed: $t", t)
            val rb = rollback(ctx)
            ApplyResult(
                updated = false,
                coreVersion = installedCoreVersion(ctx),
                message = "apply failed: ${t.message}; rollback=${rb}",
                rolledBack = rb,
            )
        }
    }

    fun rollback(ctx: Context): Boolean {
        val prev = previousDir(ctx)
        val cur = currentDir(ctx)
        if (!prev.isDirectory || prev.list().isNullOrEmpty()) {
            Log.w(TAG, "rollback: no previous overlay")
            return false
        }
        val tmp = File(hotRoot(ctx), "rollback-tmp")
        tmp.deleteRecursively()
        if (cur.exists()) cur.renameTo(tmp)
        val ok = prev.renameTo(cur)
        if (!ok) {
            // best-effort restore
            if (tmp.exists()) tmp.renameTo(cur)
            return false
        }
        tmp.deleteRecursively()
        previousDir(ctx).mkdirs()
        Log.i(TAG, "rollback → current restored from previous")
        return true
    }

    private fun promoteStaging(ctx: Context, coreVersion: String) {
        val staging = stagingDir(ctx)
        val cur = currentDir(ctx)
        val prev = previousDir(ctx)
        prev.deleteRecursively()
        if (cur.exists() && !cur.list().isNullOrEmpty()) {
            cur.renameTo(prev)
        } else {
            prev.mkdirs()
        }
        val newCur = currentDir(ctx)
        newCur.deleteRecursively()
        require(staging.renameTo(newCur)) { "promote staging→current failed" }
        val state = JSONObject()
            .put("coreVersion", coreVersion)
            .put("installedAt", System.currentTimeMillis())
            .put("previousVersion", runCatching {
                JSONObject(File(hotRoot(ctx), STATE).readText()).optString("coreVersion", "")
            }.getOrDefault(""))
        File(hotRoot(ctx), STATE).writeText(state.toString())
        stagingDir(ctx).mkdirs()
        Log.i(TAG, "promoted coreVersion=$coreVersion")
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun httpGetBytes(url: String): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.readBytes() ?: ByteArray(0)
            if (code !in 200..299) {
                error("HTTP $code for $url: ${body.toString(Charsets.UTF_8).take(200)}")
            }
            return body
        } finally {
            conn.disconnect()
        }
    }
}
