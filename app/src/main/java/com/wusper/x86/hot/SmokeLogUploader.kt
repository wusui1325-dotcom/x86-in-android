package com.wusper.x86.hot

import android.content.Context
import android.provider.Settings
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Upload smoke logs to Drive folder [HotSmokeConfig.DEFAULT_DRIVE_FOLDER_NAME] (`x86-a-log`)
 * or a configurable HTTPS endpoint.
 *
 * After a **successful** Drive upload, trash older `smoke-*.log` siblings in that folder
 * (keep only the just-uploaded file). Never delete on failed upload.
 */
object SmokeLogUploader {
    private const val TAG = "SmokeUpload"

    data class UploadResult(
        val ok: Boolean,
        val mode: String,
        val fileName: String,
        val remoteIdOrUrl: String,
        val pruned: Int,
        val message: String,
    )

    fun buildFileName(versionName: String, deviceTag: String): String {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Hong_Kong")
        }.format(Date())
        val ver = versionName.replace(Regex("[^A-Za-z0-9._+-]"), "_")
        val dev = deviceTag.replace(Regex("[^A-Za-z0-9._+-]"), "_").take(16).ifBlank { "device" }
        return "smoke-$ver-$ts-$dev.log"
    }

    fun deviceTag(ctx: Context): String {
        val id = runCatching {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty()
        return if (id.isBlank()) "unknown" else id.take(12)
    }

    fun upload(
        ctx: Context,
        logFile: File,
        versionName: String,
        cfg: HotSmokeConfig.Snapshot = HotSmokeConfig.load(ctx),
    ): UploadResult {
        require(logFile.isFile && logFile.length() > 0L) { "log missing/empty: ${logFile.absolutePath}" }
        val name = buildFileName(versionName, deviceTag(ctx))
        return when (cfg.uploadMode) {
            "drive" -> uploadDrive(cfg, logFile, name)
            "https" -> uploadHttps(cfg, logFile, name)
            else -> UploadResult(false, cfg.uploadMode, name, "", 0, "unknown uploadMode=${cfg.uploadMode}")
        }
    }

    private fun uploadHttps(cfg: HotSmokeConfig.Snapshot, logFile: File, name: String): UploadResult {
        if (cfg.uploadUrl.isBlank()) {
            return UploadResult(false, "https", name, "", 0, "uploadUrl empty")
        }
        val url = if (cfg.uploadUrl.contains("%s")) {
            cfg.uploadUrl.replace("%s", name)
        } else if (cfg.uploadUrl.endsWith("/")) {
            cfg.uploadUrl + name
        } else {
            cfg.uploadUrl
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 120_000
            doOutput = true
            requestMethod = if (cfg.uploadMethod == "PUT") "PUT" else "POST"
            setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            setRequestProperty("X-Smoke-Filename", name)
            applyHeaders(this, cfg.uploadHeadersJson)
        }
        return try {
            conn.outputStream.use { out -> logFile.inputStream().use { it.copyTo(out) } }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.readBytes()?.toString(Charsets.UTF_8)?.take(500).orEmpty()
            if (code in 200..299) {
                // HTTPS relay is expected to prune Drive-side; device cannot list Drive here.
                UploadResult(true, "https", name, url, pruned = 0, message = "HTTP $code $body")
            } else {
                UploadResult(false, "https", name, url, 0, "HTTP $code $body")
            }
        } catch (t: Throwable) {
            UploadResult(false, "https", name, url, 0, t.message ?: "$t")
        } finally {
            conn.disconnect()
        }
    }

    private fun uploadDrive(
        cfg: HotSmokeConfig.Snapshot,
        logFile: File,
        name: String,
    ): UploadResult {
        if (cfg.driveAccessToken.isBlank()) {
            return UploadResult(
                false,
                "drive",
                name,
                "",
                0,
                "driveAccessToken empty — set prefs x86_hot_smoke.driveAccessToken " +
                    "(OAuth/service). Folder ${HotSmokeConfig.DEFAULT_DRIVE_FOLDER_NAME}=${cfg.driveFolderId}",
            )
        }
        if (cfg.driveFolderId.isBlank()) {
            return UploadResult(false, "drive", name, "", 0, "driveFolderId empty")
        }
        val boundary = "smoke_" + System.currentTimeMillis()
        val meta = JSONObject()
            .put("name", name)
            .put("parents", JSONArray().put(cfg.driveFolderId))
            .toString()
        val media = logFile.readBytes()
        val preamble = buildString {
            append("--$boundary\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(meta)
            append("\r\n--$boundary\r\n")
            append("Content-Type: text/plain\r\n\r\n")
        }.toByteArray(StandardCharsets.UTF_8)
        val closing = "\r\n--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8)

        val uploadUrl =
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name,webViewLink"
        val conn = (URL(uploadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 180_000
            doOutput = true
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer ${cfg.driveAccessToken}")
            setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
        }
        return try {
            conn.outputStream.use { out ->
                out.write(preamble)
                out.write(media)
                out.write(closing)
            }
            val code = conn.responseCode
            val respBytes = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.readBytes() ?: ByteArray(0)
            val resp = respBytes.toString(Charsets.UTF_8)
            if (code !in 200..299) {
                return UploadResult(false, "drive", name, "", 0, "Drive HTTP $code $resp")
            }
            val id = JSONObject(resp).optString("id", "")
            val link = JSONObject(resp).optString("webViewLink", id)
            if (id.isBlank()) {
                return UploadResult(false, "drive", name, "", 0, "Drive ok but no file id: $resp")
            }
            Log.i(TAG, "Drive uploaded id=$id name=$name")
            val pruned = pruneOlderDriveLogs(cfg, keepId = id, keepName = name)
            UploadResult(true, "drive", name, link.ifBlank { id }, pruned, "uploaded id=$id pruned=$pruned")
        } catch (t: Throwable) {
            UploadResult(false, "drive", name, "", 0, t.message ?: "$t")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Trash other smoke-*.log (or all non-folder files older than keep) in x86-a-log.
     * Only called after successful upload of [keepId].
     */
    private fun pruneOlderDriveLogs(cfg: HotSmokeConfig.Snapshot, keepId: String, keepName: String): Int {
        var pruned = 0
        try {
            val q = URLEncoderCompat.encode(
                "'${cfg.driveFolderId}' in parents and trashed=false",
                "UTF-8",
            )
            val listUrl =
                "https://www.googleapis.com/drive/v3/files?q=$q&fields=files(id,name,mimeType)&pageSize=100"
            val conn = (URL(listUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer ${cfg.driveAccessToken}")
                connectTimeout = 20_000
                readTimeout = 60_000
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
            conn.disconnect()
            if (code !in 200..299) {
                Log.w(TAG, "prune list failed HTTP $code $body")
                return 0
            }
            val files = JSONObject(body).optJSONArray("files") ?: JSONArray()
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                val id = f.optString("id")
                val name = f.optString("name")
                val mime = f.optString("mimeType")
                if (id.isBlank() || id == keepId) continue
                if (mime == "application/vnd.google-apps.folder") continue
                // Prefer smoke-* logs; also prune any other plain logs in this folder
                val isSmoke = name.startsWith("smoke-") || name.endsWith(".log")
                if (!isSmoke) continue
                if (trashDriveFile(cfg.driveAccessToken, id)) {
                    pruned++
                    Log.i(TAG, "pruned old log id=$id name=$name (kept $keepName)")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "prune failed: $t")
        }
        return pruned
    }

    private fun trashDriveFile(token: String, fileId: String): Boolean {
        val url = "https://www.googleapis.com/drive/v3/files/$fileId"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "PATCH"
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        return try {
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { w ->
                w.write("""{"trashed":true}""")
            }
            val code = conn.responseCode
            code in 200..299
        } catch (_: Throwable) {
            false
        } finally {
            conn.disconnect()
        }
    }

    private fun applyHeaders(conn: HttpURLConnection, headersJson: String) {
        runCatching {
            val o = JSONObject(headersJson.ifBlank { "{}" })
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                conn.setRequestProperty(k, o.getString(k))
            }
        }
    }
}

/** Tiny URLEncoder wrapper (avoid importing java.net.URLEncoder noise in call sites). */
private object URLEncoderCompat {
    fun encode(s: String, enc: String): String = java.net.URLEncoder.encode(s, enc)
}
