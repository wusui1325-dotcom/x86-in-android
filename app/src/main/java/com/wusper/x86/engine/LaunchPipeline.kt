package com.wusper.x86.engine

import android.content.Context
import android.net.Uri
import com.wusper.x86.Workspace
import java.io.File
import java.io.FileOutputStream

/**
 * 真啟動管線：
 * - AArch64 ELF → 直接 exec
 * - x86_64 ELF → Box64（APK 內 ANDROID=1 或 runtime glibc）
 * - PE／.exe → PRoot＋glibc rootfs＋glibc Box64＋Wine（組件首次下載）
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
        /** PE 啟動前若為 true，Session 需先跑 ComponentInstaller */
        val needsRuntimeInstall: Boolean = false,
    )

    enum class Mode {
        HostExec,
        Box64,
        WineProot,
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
        preferAndroidGuest: Boolean = true,
        forcePeSmoke: Boolean = false,
    ): Plan {
        val logDir = File(ws.dir, "logs").also { it.mkdirs() }
        val logFile = File(logDir, "session.log")

        if (forceHostSmoke) {
            val bins = EngineAssets.ensureTestBinaries(ctx)
            val host = bins.hostArm64
            return Plan(
                argv = listOf(host.absolutePath),
                cwd = ws.dataDir.absolutePath,
                logFile = logFile,
                kind = BinaryKind.fromNative(NativeEngine.nativeProbe(host.absolutePath)),
                kindMessage = NativeEngine.nativeProbeMessage(host.absolutePath),
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

        if (forcePeSmoke) {
            val pe = ensurePeSmokeAsset(ctx, ws)
            return planForPe(ctx, ws, pe, logFile)
        }

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
            BinaryKind.Pe -> planForPe(ctx, ws, local, logFile)
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
                kindMessage = msg + " · Box64 抽出失敗",
                mode = Mode.Unsupported,
                targetFile = local,
            )
        }
    }

    private fun planForPe(
        ctx: Context,
        ws: Workspace,
        pe: File,
        logFile: File,
    ): Plan {
        // 保證工作區內有 app.exe
        val staged = File(ws.exeDir, "app.exe")
        if (pe.absolutePath != staged.absolutePath) {
            pe.copyTo(staged, overwrite = true)
        }
        val ready = ComponentInstaller.isReady(ctx)
        val msg = NativeEngine.nativeProbeMessage(staged.absolutePath)
        if (!ready) {
            return Plan(
                argv = emptyList(),
                cwd = ws.dataDir.absolutePath,
                logFile = logFile,
                kind = BinaryKind.Pe,
                kindMessage = msg + " · 需安裝 Wine／rootfs 運行時（約 160MB+）",
                mode = Mode.WineProot,
                targetFile = staged,
                needsRuntimeInstall = true,
            )
        }
        return buildWineProotPlan(ctx, ws, staged, logFile)
    }

    fun buildWineProotPlan(
        ctx: Context,
        ws: Workspace,
        pe: File,
        logFile: File,
    ): Plan {
        val proot = ComponentInstaller.ensureProot(ctx)
        ComponentInstaller.ensureWineLaunchScript(ctx)
        val runtime = ComponentInstaller.runtimeRoot(ctx)
        val rootfs = File(runtime, "rootfs")
        val prefix = File(ws.dataDir, "wineprefix").also { it.mkdirs() }
        val tmp = File(ctx.filesDir, "engine/proot-tmp").also { it.mkdirs() }
        val launchSh = File(rootfs, "usr/local/bin/wine-launch.sh")
        // 若舊運行時無腳本，當場補寫
        if (!launchSh.exists()) {
            // 觸發安裝器補腳本：簡易內嵌
            launchSh.parentFile?.mkdirs()
        }
        val guestExe = "/mnt/app/app.exe"
        val fbDir = File(ws.dataDir, "xfb").also { it.mkdirs() }
        val argv = listOf(
            proot.absolutePath,
            "--kill-on-exit",
            "-r", rootfs.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "${ws.exeDir.absolutePath}:/mnt/app",
            "-b", "${prefix.absolutePath}:/home/xuser/.wine",
            "-b", "${ws.dataDir.absolutePath}:/mnt/data",
            "-w", "/mnt/app",
            "/bin/sh",
            "/usr/local/bin/wine-launch.sh",
            guestExe,
        )
        val env = mutableListOf(
            "PROOT_TMP_DIR=${tmp.absolutePath}",
            "HOME=/home/xuser",
            "WINEPREFIX=/home/xuser/.wine",
            "WINEDEBUG=-all",
            "WINEARCH=win64",
            "DISPLAY=:0",
            "WINE_DESKTOP_W=1280",
            "WINE_DESKTOP_H=720",
            "BOX64_PATH=/opt/wine/bin",
            "LIBGL_ALWAYS_SOFTWARE=1",
        )
        env += Box64Preset.performance.toList()
        return Plan(
            argv = argv,
            cwd = ws.dataDir.absolutePath,
            logFile = logFile,
            kind = BinaryKind.Pe,
            kindMessage = "Windows PE · Xvfb→XWD→Surface · PRoot→Box64→Wine ${pe.name}",
            mode = Mode.WineProot,
            targetFile = pe,
            env = env,
            needsRuntimeInstall = false,
        )
    }

    fun start(plan: Plan): Result {
        if (plan.needsRuntimeInstall) {
            plan.logFile.writeText("NEED_RUNTIME: ${plan.kindMessage}\n")
            return Result(false, -1, plan.kindMessage, plan)
        }
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

    private fun ensurePeSmokeAsset(ctx: Context, ws: Workspace): File {
        val staged = File(ws.exeDir, "app.exe")
        ctx.assets.open("testdata/wine_whoami.exe").use { input ->
            FileOutputStream(staged).use { output -> input.copyTo(output) }
        }
        return staged
    }

    private fun resolveTargetFile(
        ctx: Context,
        ws: Workspace,
        targetPath: String?,
        targetUri: String?,
    ): File {
        val staged = File(ws.exeDir, "app.exe")
        if (staged.exists() && staged.length() > 0) return staged

        // 兼容舊 payload.bin
        val legacy = File(ws.exeDir, "payload.bin")
        if (legacy.exists() && legacy.length() > 0) {
            legacy.copyTo(staged, overwrite = true)
            return staged
        }

        if (!targetPath.isNullOrBlank() &&
            !targetPath.startsWith("content:") &&
            !targetPath.startsWith("asset:")
        ) {
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
