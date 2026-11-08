package com.wusper.x86.engine

import android.content.Context
import android.net.Uri
import com.wusper.x86.Workspace
import java.io.File
import java.io.FileOutputStream

/**
 * 真啟動管線（P0）：
 * 1) 解析目標檔（工作區 exe／URI／內建測試 ELF）
 * 2) probe 類型
 * 3) AArch64 → 直接 exec；x86_64 → box64（若有）；PE → 回報需 Wine
 * 4) stdout/stderr → workspace/logs/session.log
 */
object LaunchPipeline {

    data class Plan(
        val argv: List<String>,
        val cwd: String,
        val logFile: File,
        val kind: BinaryKind,
        val kindMessage: String,
        val mode: Mode,
        val targetFile: File,
    )

    enum class Mode {
        HostExec,
        Box64,
        Unsupported,
    }

    data class Result(
        val ok: Boolean,
        val pid: Int,
        val message: String,
        val plan: Plan?,
    )

    fun prepare(
        ctx: Context,
        ws: Workspace,
        targetPath: String?,
        targetUri: String?,
        forceHostSmoke: Boolean = false,
    ): Plan {
        val logDir = File(ws.dir, "logs").also { it.mkdirs() }
        val logFile = File(logDir, "session.log")

        if (forceHostSmoke) {
            val (host, _) = EngineAssets.ensureTestBinaries(ctx)
            val msg = NativeEngine.nativeProbeMessage(host.absolutePath)
            val kind = BinaryKind.fromNative(NativeEngine.nativeProbe(host.absolutePath))
            return Plan(
                argv = listOf(host.absolutePath),
                cwd = ws.dataDir.absolutePath,
                logFile = logFile,
                kind = kind,
                kindMessage = msg,
                mode = Mode.HostExec,
                targetFile = host,
            )
        }

        val local = resolveTargetFile(ctx, ws, targetPath, targetUri)
        val probePath = local.absolutePath
        val kind = BinaryKind.fromNative(NativeEngine.nativeProbe(probePath))
        val msg = NativeEngine.nativeProbeMessage(probePath)

        return when (kind) {
            BinaryKind.ElfAarch64 -> Plan(
                argv = listOf(local.absolutePath),
                cwd = ws.dataDir.absolutePath,
                logFile = logFile,
                kind = kind,
                kindMessage = msg,
                mode = Mode.HostExec,
                targetFile = local,
            )
            BinaryKind.ElfX86_64 -> {
                val box = EngineAssets.findBox64(ctx)
                if (box != null) {
                    Plan(
                        argv = listOf(box.absolutePath, local.absolutePath),
                        cwd = ws.dataDir.absolutePath,
                        logFile = logFile,
                        kind = kind,
                        kindMessage = msg + " · 經 Box64",
                        mode = Mode.Box64,
                        targetFile = local,
                    )
                } else {
                    Plan(
                        argv = emptyList(),
                        cwd = ws.dataDir.absolutePath,
                        logFile = logFile,
                        kind = kind,
                        kindMessage = msg + " · Box64 尚未打包（見 docs/ENGINE.md）",
                        mode = Mode.Unsupported,
                        targetFile = local,
                    )
                }
            }
            BinaryKind.Pe -> Plan(
                argv = emptyList(),
                cwd = ws.dataDir.absolutePath,
                logFile = logFile,
                kind = kind,
                kindMessage = msg + " · Wine 屬 P2",
                mode = Mode.Unsupported,
                targetFile = local,
            )
            else -> Plan(
                argv = emptyList(),
                cwd = ws.dataDir.absolutePath,
                logFile = logFile,
                kind = kind,
                kindMessage = msg,
                mode = Mode.Unsupported,
                targetFile = local,
            )
        }
    }

    fun start(plan: Plan): Result {
        if (plan.mode == Mode.Unsupported || plan.argv.isEmpty()) {
            plan.logFile.writeText("UNSUPPORTED: ${plan.kindMessage}\n")
            return Result(false, -1, plan.kindMessage, plan)
        }
        // 確保可執行
        plan.targetFile.setExecutable(true, false)
        val rc = NativeEngine.nativeExec(
            plan.argv.toTypedArray(),
            plan.cwd,
            plan.logFile.absolutePath,
        )
        return if (rc > 0) {
            Result(true, rc, "已啟動 pid=$rc（${plan.mode}）\n${plan.kindMessage}", plan)
        } else {
            Result(false, rc, "啟動失敗 code=$rc（${plan.kindMessage}）", plan)
        }
    }

    fun stop() {
        NativeEngine.nativeStop()
    }

    private fun resolveTargetFile(
        ctx: Context,
        ws: Workspace,
        targetPath: String?,
        targetUri: String?,
    ): File {
        // 1) 工作區內已有副本
        val staged = File(ws.exeDir, "payload.bin")
        if (staged.exists() && staged.length() > 0) return staged

        // 2) 本機絕對路徑
        if (!targetPath.isNullOrBlank() && !targetPath.startsWith("content:")) {
            val f = File(targetPath)
            if (f.exists()) {
                f.copyTo(staged, overwrite = true)
                return staged
            }
        }

        // 3) content URI → 複製進工作區
        val uriStr = targetUri?.takeIf { it.isNotBlank() }
            ?: targetPath?.takeIf { it.startsWith("content:") }
        if (!uriStr.isNullOrBlank()) {
            val uri = Uri.parse(uriStr)
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(staged).use { output -> input.copyTo(output) }
            }
            if (staged.exists() && staged.length() > 0) return staged
        }

        // 4) 後備：內建 host smoke（讓空殼也能驗收管線）
        val (host, _) = EngineAssets.ensureTestBinaries(ctx)
        return host
    }
}
