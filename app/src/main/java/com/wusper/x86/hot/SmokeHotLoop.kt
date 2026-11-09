package com.wusper.x86.hot

import android.content.Context
import android.util.Log
import com.wusper.x86.BuildConfig
import com.wusper.x86.WorkspaceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Poll manifest → hot-update core → collect smoke log → upload → wait.
 * Does not block UI; failures are reported via [Listener].
 */
object SmokeHotLoop {
    private const val TAG = "SmokeHotLoop"
    private val running = AtomicBoolean(false)
    private var pollJob: Job? = null

    interface Listener {
        fun onStatus(line: String)
        fun onRoundDone(ok: Boolean, detail: String)
    }

    data class RoundReport(
        val hot: HotCoreManager.ApplyResult?,
        val logFile: File?,
        val upload: SmokeLogUploader.UploadResult?,
        val ok: Boolean,
        val detail: String,
    )

    fun isBusy(): Boolean = running.get()

    fun startAutoPoll(ctx: Context, scope: CoroutineScope, listener: Listener? = null) {
        if (!BuildConfig.SMOKE_HOT) {
            listener?.onStatus("SMOKE_HOT=false — auto poll disabled")
            return
        }
        val app = ctx.applicationContext
        pollJob?.cancel()
        pollJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val cfg = HotSmokeConfig.load(app)
                if (!cfg.autoLoop) {
                    listener?.onStatus("autoLoop=false — idle")
                    delay(cfg.pollIntervalSec * 1000L)
                    continue
                }
                if (cfg.manifestUrl.isBlank()) {
                    listener?.onStatus("manifestUrl empty — idle")
                    delay(cfg.pollIntervalSec * 1000L)
                    continue
                }
                runCatching {
                    runOneRound(app, triggerHot = true, listener = listener)
                }.onFailure {
                    Log.w(TAG, "round failed: $it")
                    listener?.onRoundDone(false, it.message ?: "$it")
                }
                delay(HotSmokeConfig.load(app).pollIntervalSec * 1000L)
            }
        }
    }

    fun stopAutoPoll() {
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * One round: optional hot-update from manifest, then collect latest session.log and upload.
     * Engine restart is left to the user / next Session launch (overlay applied on ensureWineLaunchScript).
     */
    suspend fun runOneRound(
        ctx: Context,
        triggerHot: Boolean = true,
        listener: Listener? = null,
    ): RoundReport = withContext(Dispatchers.IO) {
        if (!running.compareAndSet(false, true)) {
            return@withContext RoundReport(null, null, null, false, "busy")
        }
        try {
            val cfg = HotSmokeConfig.load(ctx)
            var hotResult: HotCoreManager.ApplyResult? = null
            if (triggerHot && cfg.manifestUrl.isNotBlank()) {
                listener?.onStatus("fetch manifest…")
                val man = HotCoreManager.fetchManifest(cfg.manifestUrl)
                listener?.onStatus("manifest core=${man.coreVersion}")
                hotResult = HotCoreManager.applyIfNewer(ctx, man, BuildConfig.VERSION_CODE)
                listener?.onStatus(hotResult.message)
                if (hotResult.rolledBack) {
                    val detail = "hot rollback: ${hotResult.message}"
                    listener?.onRoundDone(false, detail)
                    return@withContext RoundReport(hotResult, null, null, false, detail)
                }
            } else {
                listener?.onStatus("skip hot (no manifestUrl or triggerHot=false); installed=${HotCoreManager.installedCoreVersion(ctx)}")
            }

            listener?.onStatus("collect session.log…")
            val log = collectSmokeLog(ctx, cfg)
                ?: return@withContext RoundReport(
                    hotResult,
                    null,
                    null,
                    false,
                    "no session.log — launch a workspace once, then re-run smoke round",
                ).also { listener?.onRoundDone(false, it.detail) }

            listener?.onStatus("upload ${log.name} via ${cfg.uploadMode}…")
            val up = SmokeLogUploader.upload(ctx, log, BuildConfig.VERSION_NAME, cfg)
            val ok = up.ok
            val detail = buildString {
                append(up.message)
                if (hotResult != null) append(" | hot=${hotResult.message}")
                append(" | file=${up.fileName}")
                if (up.pruned > 0) append(" | pruned=${up.pruned}")
            }
            listener?.onRoundDone(ok, detail)
            RoundReport(hotResult, log, up, ok, detail)
        } finally {
            running.set(false)
        }
    }

    /**
     * Build a stamped copy under filesDir/engine/hot/smoke-out/ for upload.
     */
    fun collectSmokeLog(ctx: Context, cfg: HotSmokeConfig.Snapshot): File? {
        val src = findSessionLog(ctx, cfg.smokeWorkspaceId) ?: return null
        val outDir = File(HotCoreManager.hotRoot(ctx), "smoke-out").also { it.mkdirs() }
        val stamped = File(outDir, "session-stamped.log")
        val header = buildString {
            appendLine("=== smokeHot stamp ===")
            appendLine("versionName=${BuildConfig.VERSION_NAME}")
            appendLine("versionCode=${BuildConfig.VERSION_CODE}")
            appendLine("gitSha=${BuildConfig.GIT_SHA}")
            appendLine("smokeHot=${BuildConfig.SMOKE_HOT}")
            appendLine("coreVersion=${HotCoreManager.installedCoreVersion(ctx)}")
            appendLine("device=${SmokeLogUploader.deviceTag(ctx)}")
            appendLine("source=${src.absolutePath}")
            appendLine("collectedAtMs=${System.currentTimeMillis()}")
            appendLine("=== end stamp ===")
            appendLine()
        }
        stamped.writeText(header + src.readText())
        // also write a tiny JSON sidecar for relays
        File(outDir, "session-meta.json").writeText(
            JSONObject()
                .put("versionName", BuildConfig.VERSION_NAME)
                .put("versionCode", BuildConfig.VERSION_CODE)
                .put("coreVersion", HotCoreManager.installedCoreVersion(ctx))
                .put("device", SmokeLogUploader.deviceTag(ctx))
                .put("source", src.absolutePath)
                .toString(),
        )
        return stamped
    }

    private fun findSessionLog(ctx: Context, workspaceId: String): File? {
        if (workspaceId.isNotBlank()) {
            val ws = WorkspaceStore.listWorkspaces(ctx).firstOrNull { it.id == workspaceId }
            val f = ws?.dir?.let { File(File(it, "logs"), "session.log") }
            if (f != null && f.isFile && f.length() > 0L) return f
        }
        // newest session.log across workspaces
        return WorkspaceStore.listWorkspaces(ctx)
            .mapNotNull { ws ->
                val f = File(File(ws.dir, "logs"), "session.log")
                if (f.isFile && f.length() > 0L) f else null
            }
            .maxByOrNull { it.lastModified() }
    }
}
