package com.wusper.x86.hot

import android.content.Context
import org.json.JSONObject

/**
 * smokeHot channel config.
 *
 * Prefs name: [PREFS]
 * Defaults also read from assets `hot/smoke-hot-defaults.json` on first access.
 *
 * Keys:
 * - manifestUrl, pollIntervalSec, autoLoop
 * - uploadMode: "drive" | "https"
 * - uploadUrl, uploadMethod (PUT|POST), uploadHeadersJson
 * - driveFolderId, driveAccessToken, driveAccountEmail, driveOAuthClientId
 * - smokeWorkspaceId, smokeWaitSeconds
 */
object HotSmokeConfig {
    const val PREFS = "x86_hot_smoke"
    /** Boss Drive folder literally named x86-a-log */
    const val DEFAULT_DRIVE_FOLDER_ID = "1n2ZPm-GOQV66ikxfnPh6XRLbio5PE6fz"
    const val DEFAULT_DRIVE_FOLDER_NAME = "x86-a-log"

    data class Snapshot(
        val manifestUrl: String,
        val pollIntervalSec: Int,
        val autoLoop: Boolean,
        val uploadMode: String,
        val uploadUrl: String,
        val uploadMethod: String,
        val uploadHeadersJson: String,
        val driveFolderId: String,
        val driveAccessToken: String,
        val driveAccountEmail: String,
        val driveOAuthClientId: String,
        val smokeWorkspaceId: String,
        val smokeWaitSeconds: Int,
    ) {
        fun driveReady(): Boolean = driveAccessToken.isNotBlank() || driveAccountEmail.isNotBlank()
        fun httpsReady(): Boolean = uploadUrl.isNotBlank()
    }

    fun load(ctx: Context): Snapshot {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        ensureDefaultsSeeded(ctx, prefs)
        return Snapshot(
            manifestUrl = prefs.getString("manifestUrl", "") ?: "",
            pollIntervalSec = prefs.getInt("pollIntervalSec", 300).coerceIn(30, 86_400),
            autoLoop = prefs.getBoolean("autoLoop", false),
            uploadMode = (prefs.getString("uploadMode", "drive") ?: "drive").lowercase(),
            uploadUrl = prefs.getString("uploadUrl", "") ?: "",
            uploadMethod = (prefs.getString("uploadMethod", "POST") ?: "POST").uppercase(),
            uploadHeadersJson = prefs.getString("uploadHeadersJson", "{}") ?: "{}",
            driveFolderId = prefs.getString("driveFolderId", DEFAULT_DRIVE_FOLDER_ID)
                ?: DEFAULT_DRIVE_FOLDER_ID,
            driveAccessToken = prefs.getString("driveAccessToken", "") ?: "",
            driveAccountEmail = prefs.getString("driveAccountEmail", "") ?: "",
            driveOAuthClientId = prefs.getString("driveOAuthClientId", "") ?: "",
            smokeWorkspaceId = prefs.getString("smokeWorkspaceId", "") ?: "",
            smokeWaitSeconds = prefs.getInt("smokeWaitSeconds", 90).coerceIn(15, 600),
        )
    }

    fun edit(ctx: Context, block: (android.content.SharedPreferences.Editor) -> Unit) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().also(block).apply()
    }

    private fun ensureDefaultsSeeded(ctx: Context, prefs: android.content.SharedPreferences) {
        if (prefs.getBoolean("_seeded", false)) return
        val asset = runCatching {
            ctx.assets.open("hot/smoke-hot-defaults.json").bufferedReader().readText()
        }.getOrNull()
        val json = asset?.let { runCatching { JSONObject(it) }.getOrNull() }
        prefs.edit().apply {
            if (json != null) {
                if (!prefs.contains("manifestUrl")) {
                    putString("manifestUrl", json.optString("manifestUrl", ""))
                }
                if (!prefs.contains("pollIntervalSec")) {
                    putInt("pollIntervalSec", json.optInt("pollIntervalSec", 300))
                }
                if (!prefs.contains("autoLoop")) {
                    putBoolean("autoLoop", json.optBoolean("autoLoop", false))
                }
                if (!prefs.contains("uploadMode")) {
                    putString("uploadMode", json.optString("uploadMode", "drive"))
                }
                if (!prefs.contains("uploadUrl")) {
                    putString("uploadUrl", json.optString("uploadUrl", ""))
                }
                if (!prefs.contains("uploadMethod")) {
                    putString("uploadMethod", json.optString("uploadMethod", "POST"))
                }
                if (!prefs.contains("uploadHeadersJson")) {
                    putString(
                        "uploadHeadersJson",
                        json.optJSONObject("uploadHeaders")?.toString() ?: "{}",
                    )
                }
                if (!prefs.contains("driveFolderId")) {
                    putString(
                        "driveFolderId",
                        json.optString("driveFolderId", DEFAULT_DRIVE_FOLDER_ID),
                    )
                }
                if (!prefs.contains("driveOAuthClientId")) {
                    putString("driveOAuthClientId", json.optString("driveOAuthClientId", ""))
                }
                if (!prefs.contains("smokeWaitSeconds")) {
                    putInt("smokeWaitSeconds", json.optInt("smokeWaitSeconds", 90))
                }
            } else if (!prefs.contains("driveFolderId")) {
                putString("driveFolderId", DEFAULT_DRIVE_FOLDER_ID)
            }
            putBoolean("_seeded", true)
            apply()
        }
    }
}
