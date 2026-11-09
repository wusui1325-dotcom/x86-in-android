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
 * - manifestUrl, pollIntervalSec, autoLoop, autoLoopUserDisabled, configGen
 * - uploadMode: "drive" | "https"
 * - uploadUrl, uploadMethod (PUT|POST), uploadHeadersJson
 * - driveFolderId, driveAccessToken, driveAccountEmail, driveOAuthClientId
 * - smokeWorkspaceId, smokeWaitSeconds
 *
 * configGen migration (0.1.96-hot.4+): hot.2/hot.3 seeded empty manifestUrl +
 * autoLoop=false via prefs.contains guards — assets alone never flip them.
 * When configGen < [CONFIG_GEN], force official manifestUrl and autoLoop=true
 * unless [KEY_AUTO_LOOP_USER_DISABLED] is set.
 */
object HotSmokeConfig {
    const val PREFS = "x86_hot_smoke"
    /** Boss Drive folder literally named x86-a-log */
    const val DEFAULT_DRIVE_FOLDER_ID = "1n2ZPm-GOQV66ikxfnPh6XRLbio5PE6fz"
    const val DEFAULT_DRIVE_FOLDER_NAME = "x86-a-log"

    /** Bump when shipping a prefs migration that must override prior seeds. */
    const val CONFIG_GEN = 4
    const val KEY_CONFIG_GEN = "configGen"
    const val KEY_AUTO_LOOP_USER_DISABLED = "autoLoopUserDisabled"

    /**
     * Official live manifest (schema=1). Hosted on GitHub Releases tag hot-core-*.
     * Hardcoded fallback when prefs URL is blank (belt + suspenders).
     */
    const val OFFICIAL_MANIFEST_URL =
        "https://github.com/wusui1325-dotcom/x86-in-android/releases/download/hot-core-0.1.98.1/manifest.json"

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
        val configGen: Int,
        val autoLoopUserDisabled: Boolean,
    ) {
        fun driveReady(): Boolean = driveAccessToken.isNotBlank() || driveAccountEmail.isNotBlank()
        fun httpsReady(): Boolean = uploadUrl.isNotBlank()
        /** Effective URL: prefs, else official. */
        fun effectiveManifestUrl(): String =
            manifestUrl.ifBlank { OFFICIAL_MANIFEST_URL }
    }

    fun load(ctx: Context): Snapshot {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        ensureDefaultsSeeded(ctx, prefs)
        migrateConfigGen(ctx, prefs)
        val rawUrl = prefs.getString("manifestUrl", "") ?: ""
        return Snapshot(
            manifestUrl = rawUrl.ifBlank { OFFICIAL_MANIFEST_URL },
            pollIntervalSec = prefs.getInt("pollIntervalSec", 300).coerceIn(30, 86_400),
            autoLoop = prefs.getBoolean("autoLoop", true),
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
            configGen = prefs.getInt(KEY_CONFIG_GEN, 0),
            autoLoopUserDisabled = prefs.getBoolean(KEY_AUTO_LOOP_USER_DISABLED, false),
        )
    }

    fun edit(ctx: Context, block: (android.content.SharedPreferences.Editor) -> Unit) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().also(block).apply()
    }

    /**
     * One-tap: official manifestUrl + autoLoop=true, clear user-disable, bump configGen.
     * Returns the URL written.
     */
    fun enableOfficialAutoLoop(ctx: Context): String {
        edit(ctx) {
            it.putString("manifestUrl", OFFICIAL_MANIFEST_URL)
            it.putBoolean("autoLoop", true)
            it.putBoolean(KEY_AUTO_LOOP_USER_DISABLED, false)
            it.putInt(KEY_CONFIG_GEN, CONFIG_GEN)
            if (!ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains("pollIntervalSec")) {
                it.putInt("pollIntervalSec", 300)
            }
        }
        return OFFICIAL_MANIFEST_URL
    }

    /** Explicit user opt-out of auto loop (survives future configGen bumps that only force URL). */
    fun disableAutoLoop(ctx: Context) {
        edit(ctx) {
            it.putBoolean("autoLoop", false)
            it.putBoolean(KEY_AUTO_LOOP_USER_DISABLED, true)
        }
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
                    putString(
                        "manifestUrl",
                        json.optString("manifestUrl", OFFICIAL_MANIFEST_URL)
                            .ifBlank { OFFICIAL_MANIFEST_URL },
                    )
                }
                if (!prefs.contains("pollIntervalSec")) {
                    putInt("pollIntervalSec", json.optInt("pollIntervalSec", 300))
                }
                if (!prefs.contains("autoLoop")) {
                    putBoolean("autoLoop", json.optBoolean("autoLoop", true))
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
            } else {
                if (!prefs.contains("manifestUrl")) {
                    putString("manifestUrl", OFFICIAL_MANIFEST_URL)
                }
                if (!prefs.contains("autoLoop")) {
                    putBoolean("autoLoop", true)
                }
                if (!prefs.contains("driveFolderId")) {
                    putString("driveFolderId", DEFAULT_DRIVE_FOLDER_ID)
                }
            }
            putBoolean("_seeded", true)
            apply()
        }
    }

    /**
     * Force-write official URL + autoLoop for devices that already ran hot.1–hot.3
     * (prefs.contains guards left blank URL / autoLoop=false permanently).
     */
    private fun migrateConfigGen(ctx: Context, prefs: android.content.SharedPreferences) {
        val gen = prefs.getInt(KEY_CONFIG_GEN, 0)
        if (gen >= CONFIG_GEN) return
        val assetUrl = runCatching {
            val text = ctx.assets.open("hot/smoke-hot-defaults.json").bufferedReader().readText()
            JSONObject(text).optString("manifestUrl", OFFICIAL_MANIFEST_URL)
        }.getOrDefault(OFFICIAL_MANIFEST_URL).ifBlank { OFFICIAL_MANIFEST_URL }
        val userDisabled = prefs.getBoolean(KEY_AUTO_LOOP_USER_DISABLED, false)
        prefs.edit().apply {
            putString("manifestUrl", assetUrl.ifBlank { OFFICIAL_MANIFEST_URL })
            if (!userDisabled) {
                putBoolean("autoLoop", true)
            }
            if (!prefs.contains("pollIntervalSec")) {
                putInt("pollIntervalSec", 300)
            } else {
                // clamp absurd values from older experiments
                val cur = prefs.getInt("pollIntervalSec", 300)
                if (cur !in 30..86_400) putInt("pollIntervalSec", 300)
            }
            putInt(KEY_CONFIG_GEN, CONFIG_GEN)
            apply()
        }
    }
}
