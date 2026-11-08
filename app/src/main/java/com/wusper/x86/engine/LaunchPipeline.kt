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
 * 3) AArch64 → 直接 exec；x86_64 → box64；PE → 回報需 Wine
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
        val env: List<String> = emptyList(),
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
        forceGuestSmoke: Boolean = false,
        /** true＝優先 Android x86_64 測試 ELF；false＝Linux glibc 靜態 hello_x86_64 */
        preferAndroidGuest: Boolean = true,
    ): Plan {
        val logDir = File(ws.dir, "logs").also { it.mkdirs() }
        val logFile = File(logDir, "session.log")

        if (forceHostSmoke) {
            val bins = EngineAssets.ensureTestBinaries(ctx)
            val host = bins.hostArm64
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

        if (forceGuestSmoke) {
            EngineAssets.ensureBox64(ctx)
            val bins = EngineAssets.ensureTestBinaries(ctx)
            val guest = if (preferAndroidGuest) bins.guestAndroidX86_64 else bins.guestLinuxX86_64
            return planForX86(ctx, guest, logFile, ws.dataDir.absolutePath)
        }

        // forceMicrobench 經 Session EXTRA：targetPath 以 asset://microbench 區分
        if (targetPath == "asset://testdata/microbench_host_arm64") {
            val bins = EngineAssets.ensureTestBinaries(ctx)
            val host = bins.microbenchHostArm64
            return Plan(
                argv = listOf(host.absolutePath, "50000000"),
                cwd = ws.dataDir.absolutePath,
                logFile = logFile,
                kind = BinaryKind.fromNative(NativeEngine.nativeProbe(host.absolutePath)),
                kindMessage = "microbench native arm64",
                mode = Mode.HostExec,
                targetFile = host,
            )
        }
        if (targetPath == "asset://testdata/microbench_android_x86_64") {
            EngineAssets.ensureBox64(ctx)
            val bins = EngineAssets.ensureTestBinaries(ctx)
            val guest = bins.microbenchAndroidX86_64
            val plan = planForX86(ctx, guest, logFile, ws.dataDir.absolutePath)
            return plan.copy(argv = plan.argv + "50000000", kindMessage = plan.kindMessage + " · microbench")
        }

        val local = resolveTargetFile(ctx, ws, targetPath, targetUri)
        val kind = BinaryKind.fromNative(NativeEngine.nativeProbe(local.absolutePath))
        val msg = NativeEngine.nativeProbeMessage(local.absolutePath)

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
            BinaryKind.ElfX86_64 -> planForX86(ctx, local, logFile, ws.dataDir.absolutePath)
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

    private fun planForX86(
        ctx: Context,
        local: File,
        logFile: File,
        cwd: String,
    ): Plan {
        val msg = NativeEngine.nativeProbeMessage(local.absolutePath)
        val kind = BinaryKind.fromNative(NativeEngine.nativeProbe(local.absolutePath))
        val box = EngineAssets.findBox64(ctx)
        return if (box != null) {
            box.setExecutable(true, false)
            Plan(
                argv = listOf(box.absolutePath, local.absolutePath),
                cwd = cwd,
                logFile = logFile,
                kind = kind,
                kindMessage = msg + " · 經 Box64（${box.absolutePath}）· preset=performance",
                mode = Mode.Box64,
                targetFile = local,
                env = Box64Preset.performance.toList(),
            )
        } else {
            Plan(
                argv = emptyList(),
                cwd = cwd,
                logFile = logFile,
                kind = kind,
                kindMessage = msg + " · Box64 抽出失敗（見 docs/ENGINE.md）",
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
        plan.targetFile.setExecutable(true, false)
        File(plan.argv[0]).setExecutable(true, false)
        val rc = NativeEngine.nativeExec(
            plan.argv.toTypedArray(),
            plan.cwd,
            plan.logFile.absolutePath,
            plan.env.toTypedArray().ifEmpty { null },
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
        val staged = File(ws.exeDir, "payload.bin")
        if (staged.exists() && staged.length() > 0) return staged

        if (!targetPath.isNullOrBlank() && !targetPath.startsWith("content:") && !targetPath.startsWith("asset:")) {
            val f = File(targetPath)
            if (f.exists()) {
                f.copyTo(staged, overwrite = true)
                return staged
            }
        }

        val uriStr = targetUri?.takeIf { it.isNotBlank() }
            ?: targetPath?.takeIf { it.startsWith("content:") }
        if (!uriStr.isNullOrBlank()) {
            val uri = Uri.parse(uriStr)
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(staged).use { output -> input.copyTo(output) }
            }
            if (staged.exists() && staged.length() > 0) return staged
        }

        val bins = EngineAssets.ensureTestBinaries(ctx)
        return bins.hostArm64
    }
}
