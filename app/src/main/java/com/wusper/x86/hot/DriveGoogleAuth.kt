package com.wusper.x86.hot

import android.accounts.Account
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import org.json.JSONObject

/**
 * In-app Google Sign-In for smokeHot Drive uploads.
 *
 * Stores account email + access token into prefs [HotSmokeConfig.PREFS].
 * Token is refreshed via [GoogleAuthUtil.getToken] (no hand-pasted OAuth token).
 *
 * Cloud Console (once): create an **Android** OAuth client with
 * package `com.wusper.x86.smokehot` and the release keystore SHA-1
 * (see docs/SMOKE_HOT_GOOGLE_AUTH.md). Optional Web client id may be set as
 * `driveOAuthClientId` for future server-auth; not required for basic Drive scope.
 */
object DriveGoogleAuth {
    private const val TAG = "DriveGoogleAuth"

    /** Least privilege that still lets this app upload + prune its own smoke-*.log files. */
    const val SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
    private const val OAUTH2_PREFIX = "oauth2:"

    data class Status(
        val connected: Boolean,
        val email: String,
        val hasToken: Boolean,
        val clientIdConfigured: Boolean,
        val summary: String,
    )

    fun status(ctx: Context): Status {
        val prefs = ctx.getSharedPreferences(HotSmokeConfig.PREFS, Context.MODE_PRIVATE)
        val email = prefs.getString("driveAccountEmail", "") ?: ""
        val token = prefs.getString("driveAccessToken", "") ?: ""
        val clientId = oauthClientId(ctx)
        val account = GoogleSignIn.getLastSignedInAccount(ctx)
        val connected = !email.isNullOrBlank() || (account != null && !account.email.isNullOrBlank())
        val effectiveEmail = email.ifBlank { account?.email.orEmpty() }
        val summary = when {
            connected && token.isNotBlank() -> "已連 Google（$effectiveEmail）"
            connected -> "已登入 $effectiveEmail（待刷新 token）"
            else -> "未連 Google"
        }
        return Status(
            connected = connected,
            email = effectiveEmail,
            hasToken = token.isNotBlank(),
            clientIdConfigured = clientId.isNotBlank(),
            summary = summary,
        )
    }

    fun oauthClientId(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(HotSmokeConfig.PREFS, Context.MODE_PRIVATE)
        val fromPrefs = prefs.getString("driveOAuthClientId", "") ?: ""
        if (fromPrefs.isNotBlank()) return fromPrefs.trim()
        val asset = runCatching {
            ctx.assets.open("hot/smoke-hot-defaults.json").bufferedReader().readText()
        }.getOrNull()
        val json = asset?.let { runCatching { JSONObject(it) }.getOrNull() }
        return json?.optString("driveOAuthClientId", "")?.trim().orEmpty()
    }

    fun hasUsableLogin(ctx: Context): Boolean {
        val st = status(ctx)
        if (st.hasToken) return true
        val account = GoogleSignIn.getLastSignedInAccount(ctx)
        return account?.account != null
    }

    fun signInIntent(activity: Activity): Intent {
        val clientId = oauthClientId(activity)
        val builder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(SCOPE_DRIVE_FILE))
        // Web client id optional — enables server auth code; Android client is matched by package+SHA-1
        if (clientId.isNotBlank()) {
            runCatching { builder.requestServerAuthCode(clientId, true) }
        }
        val gso = builder.build()
        return GoogleSignIn.getClient(activity, gso).signInIntent
    }

    fun signOut(ctx: Context, onDone: (() -> Unit)? = null) {
        val clientId = oauthClientId(ctx)
        val builder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(SCOPE_DRIVE_FILE))
        if (clientId.isNotBlank()) {
            runCatching { builder.requestServerAuthCode(clientId, true) }
        }
        GoogleSignIn.getClient(ctx, builder.build()).signOut().addOnCompleteListener {
            clearStoredAuth(ctx)
            onDone?.invoke()
        }
    }

    fun handleSignInResult(ctx: Context, data: Intent?): Result {
        return try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(ApiException::class.java)
            if (account == null) {
                return Result(false, "Google 登入取消或失敗（無帳號）")
            }
            persistAccount(ctx, account)
            val token = fetchAccessTokenBlocking(ctx, account)
            if (token.isNullOrBlank()) {
                Result(
                    false,
                    "已登入 ${account.email}，但尚未取得 Drive token。請再點「連 Google」，" +
                        "或確認 Cloud Console 已登錄 Android OAuth（package com.wusper.x86.smokehot）。",
                )
            } else {
                Result(true, "已連 Google：${account.email}")
            }
        } catch (e: ApiException) {
            val msg = when (e.statusCode) {
                10 -> // DEVELOPER_ERROR
                    "Google 設定錯誤（code 10）：請在 Cloud Console 建立 Android OAuth 用戶端，" +
                        "package=com.wusper.x86.smokehot，SHA-1=41:AE:CC:47:82:33:A8:B0:AF:98:3E:22:5C:B3:6B:3A:C6:DA:24:F3。" +
                        "詳見 docs/SMOKE_HOT_GOOGLE_AUTH.md"
                12501 -> "已取消 Google 登入"
                else -> "Google 登入失敗：code=${e.statusCode} ${e.message}"
            }
            Log.w(TAG, msg, e)
            Result(false, msg)
        } catch (t: Throwable) {
            Log.w(TAG, "sign-in failed", t)
            Result(false, t.message ?: "$t")
        }
    }

    /**
     * Ensure prefs contain a fresh access token. Safe to call from a background thread.
     * Returns null if user must complete interactive sign-in / consent.
     */
    fun ensureAccessToken(ctx: Context): TokenOutcome {
        val app = ctx.applicationContext
        val existing = app.getSharedPreferences(HotSmokeConfig.PREFS, Context.MODE_PRIVATE)
            .getString("driveAccessToken", "").orEmpty()
        val account = GoogleSignIn.getLastSignedInAccount(app)
            ?: return if (existing.isNotBlank()) {
                TokenOutcome.Ok(existing)
            } else {
                TokenOutcome.NeedInteractive("未連 Google — 請點「連 Google」授權 Drive")
            }

        return try {
            val token = GoogleAuthUtil.getToken(
                app,
                account.account ?: Account(account.email!!, "com.google"),
                OAUTH2_PREFIX + SCOPE_DRIVE_FILE,
            )
            if (token.isNullOrBlank()) {
                TokenOutcome.NeedInteractive("無法取得 Drive token，請重新「連 Google」")
            } else {
                HotSmokeConfig.edit(app) {
                    it.putString("driveAccessToken", token)
                    it.putString("driveAccountEmail", account.email.orEmpty())
                    it.putLong("driveTokenObtainedAtMs", System.currentTimeMillis())
                    it.putString("uploadMode", "drive")
                }
                TokenOutcome.Ok(token)
            }
        } catch (e: UserRecoverableAuthException) {
            Log.i(TAG, "need user recoverable auth", e)
            TokenOutcome.NeedInteractive(
                "需要重新授權 Google Drive",
                recoverIntent = e.intent,
            )
        } catch (e: GoogleAuthException) {
            Log.w(TAG, "GoogleAuthException", e)
            TokenOutcome.NeedInteractive("Google 授權失敗：${e.message}")
        } catch (t: Throwable) {
            Log.w(TAG, "ensureAccessToken", t)
            if (existing.isNotBlank()) TokenOutcome.Ok(existing)
            else TokenOutcome.NeedInteractive(t.message ?: "$t")
        }
    }

    fun clearTokenCache(ctx: Context) {
        val prefs = ctx.getSharedPreferences(HotSmokeConfig.PREFS, Context.MODE_PRIVATE)
        val token = prefs.getString("driveAccessToken", "") ?: ""
        if (token.isNotBlank()) {
            runCatching { GoogleAuthUtil.clearToken(ctx, token) }
        }
        prefs.edit().remove("driveAccessToken").remove("driveTokenObtainedAtMs").apply()
    }

    private fun persistAccount(ctx: Context, account: GoogleSignInAccount) {
        HotSmokeConfig.edit(ctx) {
            it.putString("driveAccountEmail", account.email.orEmpty())
            it.putString("uploadMode", "drive")
        }
    }

    private fun fetchAccessTokenBlocking(ctx: Context, account: GoogleSignInAccount): String? {
        return try {
            val token = GoogleAuthUtil.getToken(
                ctx.applicationContext,
                account.account ?: Account(account.email!!, "com.google"),
                OAUTH2_PREFIX + SCOPE_DRIVE_FILE,
            )
            if (!token.isNullOrBlank()) {
                HotSmokeConfig.edit(ctx) {
                    it.putString("driveAccessToken", token)
                    it.putLong("driveTokenObtainedAtMs", System.currentTimeMillis())
                }
            }
            token
        } catch (e: UserRecoverableAuthException) {
            Log.i(TAG, "token needs recoverable UI after sign-in", e)
            null
        } catch (t: Throwable) {
            Log.w(TAG, "fetchAccessTokenBlocking", t)
            null
        }
    }

    private fun clearStoredAuth(ctx: Context) {
        HotSmokeConfig.edit(ctx) {
            it.remove("driveAccessToken")
            it.remove("driveAccountEmail")
            it.remove("driveTokenObtainedAtMs")
            it.remove("driveRefreshToken")
        }
    }

    data class Result(val ok: Boolean, val message: String)

    sealed class TokenOutcome {
        data class Ok(val token: String) : TokenOutcome()
        data class NeedInteractive(
            val message: String,
            val recoverIntent: Intent? = null,
        ) : TokenOutcome()
    }
}
