package com.wusper.x86.engine

import android.content.Context
import android.net.Uri
import com.wusper.x86.Workspace
import java.io.File
import java.io.FileOutputStream

/**
 * 真啟動管線（Boss 定案 A · 真 x86 主線）：
 * - AArch64 ELF → 直接 exec
 * - x86_64 ELF → Box64
 * - PE／.exe → PRoot＋glibc＋Box64＋Wine；圖形預設 [GraphicsBackend.GpuStack]
 *   （Turnip／DXVK／真 Surface）。Xvfb→XWD 軟渲僅 [forceSoftLegacy]。
 */
object LaunchPipeline {

    /** 工程保底：強制走 SoftLegacy（Xvfb XWD）。產品預設 false。 */
    @Volatile
    var forceSoftLegacy: Boolean = false

    data class Plan(
        val argv: List<String>,
        val cwd: String,
        val logFile: File,
        val kind: BinaryKind,
        val kindMessage: String,
        val mode: Mode,
        val targetFile: File,
        val env: List<String> = emptyList(),
        val needsRuntimeInstall: Boolean = false,
        /** GpuStack 組件（Turnip／DXVK）可先下，即使 Present 未就緒 */
        val needsGpuStackInstall: Boolean = false,
        val graphicsBackend: GraphicsBackend = GraphicsBackend.GpuStack,
        /** GpuStack 但 Present／組件未齊：不啟動客體，只回報缺口 */
        val gpuBlocked: Boolean = false,
        val gapMessage: String = "",
    )

    enum class Mode {
        HostExec,
        Box64,
        WineProot,
        WineGpuPending,
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
                kindMessage = msg + " · 經 Box64（${box.name}）· preset=performance",
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
                kindMessage = msg + " · 需安裝 Wine／glibc rootfs／Box64 運行時",
                mode = Mode.WineProot,
                targetFile = staged,
                needsRuntimeInstall = true,
                needsGpuStackInstall = !forceSoftLegacy,
                graphicsBackend = if (forceSoftLegacy) GraphicsBackend.SoftLegacy else GraphicsBackend.GpuStack,
            )
        }
        return buildWineProotPlan(ctx, ws, staged, logFile)
    }


    /**
     * 合併 KEY=VALUE 列表；同 key 後寫覆蓋前寫。
     * [LD_LIBRARY_PATH] 特例：把 [hostLibDir] 永遠置於最前（宿主 linker 解析 libproot 依賴），
     * 其餘路徑去重後接在後面（客體路徑多半由 wine-launch.sh 再 prepend）。
     */
    fun mergeEnv(entries: List<String>, hostLibDir: String? = null): List<String> {
        val map = linkedMapOf<String, String>()
        for (e in entries) {
            val i = e.indexOf('=')
            if (i <= 0) continue
            map[e.substring(0, i)] = e.substring(i + 1)
        }
        if (hostLibDir != null) {
            val parts = mutableListOf(hostLibDir)
            map["LD_LIBRARY_PATH"]?.split(':')?.forEach { p ->
                if (p.isNotEmpty() && p != hostLibDir && p !in parts) parts += p
            }
            map["LD_LIBRARY_PATH"] = parts.joinToString(":")
        }
        return map.map { (k, v) -> "$k=$v" }
    }

    fun buildWineProotPlan(
        ctx: Context,
        ws: Workspace,
        pe: File,
        logFile: File,
    ): Plan {
        val backend =
            if (forceSoftLegacy) GraphicsBackend.SoftLegacy else GraphicsBackend.GpuStack

        if (backend == GraphicsBackend.GpuStack) {
            val needGpu = !GpuStack.componentsInstalled(ctx)
            if (!GpuStack.canLaunchGpuSession(ctx)) {
                val gaps = GpuStack.gaps(ctx).joinToString("\n• ", prefix = "• ")
                return Plan(
                    argv = emptyList(),
                    cwd = ws.dataDir.absolutePath,
                    logFile = logFile,
                    kind = BinaryKind.Pe,
                    kindMessage =
                        "Windows PE · 圖形組件尚未就緒（${GpuStack.statusMessage(ctx)}）\n" +
                            "需要：\n$gaps\n" +
                            "請先完成下載，或稍後重試。",
                    mode = Mode.WineGpuPending,
                    targetFile = pe,
                    needsRuntimeInstall = false,
                    needsGpuStackInstall = needGpu,
                    graphicsBackend = GraphicsBackend.GpuStack,
                    gpuBlocked = true,
                    gapMessage = gaps,
                )
            }
        }

        val prootBundle = ComponentInstaller.ensureProotBundle(ctx)
        val proot = prootBundle.proot
        ExecBits.force(proot)
        ExecBits.force(prootBundle.loader)
        ComponentInstaller.ensureWineLaunchScript(ctx)
        val rootfsDir = File(ComponentInstaller.runtimeRoot(ctx), "rootfs")
        if (!ComponentInstaller.hasGlibcBox64(rootfsDir)) {
            throw IllegalStateException(
                "運行時缺少可用的 Box64 轉譯器（或仍是舊版）。" +
                    ComponentInstaller.USER_HINT_REINSTALL_RUNTIME,
            )
        }
        if (!ComponentInstaller.hasBox64X64lib(rootfsDir)) {
            throw IllegalStateException(
                "運行時缺少 Box64 的 x86_64 依賴庫（libgcc_s；Wine 無法載入 ntdll.so）。" +
                    ComponentInstaller.USER_HINT_REINSTALL_RUNTIME,
            )
        }
        if (!ComponentInstaller.hasWineTree(rootfsDir)) {
            throw IllegalStateException(
                "運行時 Wine 不完整（缺少 ntdll.so）。" +
                    ComponentInstaller.USER_HINT_REINSTALL_RUNTIME,
            )
        }
        ComponentInstaller.ensureUsrmergeShell(rootfsDir)
        if (!ComponentInstaller.hasValidRootfs(rootfsDir)) {
            val shell = ComponentInstaller.resolveShellElf(rootfsDir)?.guestPath ?: "(none)"
            val ld = ComponentInstaller.hasDynamicLinker(rootfsDir)
            throw IllegalStateException(
                "運行時不完整（系統殼層或連結器缺失；內部：shell=$shell ld=$ld）。" +
                    ComponentInstaller.USER_HINT_REINSTALL_RUNTIME,
            )
        }
        ExecBits.forceEngineBins(EngineAssets.engineRoot(ctx), ComponentInstaller.runtimeRoot(ctx))
        // 再補一次（forceEngineBins 不應破壞 symlink；保險）
        ComponentInstaller.ensureUsrmergeShell(rootfsDir)
        val guestShell = ComponentInstaller.shellPathInRootfs(rootfsDir)
        val runtime = ComponentInstaller.runtimeRoot(ctx)
        val prefix = File(ws.dataDir, "wineprefix").also { it.mkdirs() }
        val tmp = prootBundle.tmpDir
        File(ws.dataDir, "xfb").mkdirs()
        ws.exeDir.mkdirs()
        // 確保綁定源存在（缺目錄會導致 chdir /mnt/app 失敗）
        if (!File(ws.exeDir, "app.exe").exists() && pe.exists()) {
            pe.copyTo(File(ws.exeDir, "app.exe"), overwrite = true)
        }
        val guestExe = "/mnt/app/app.exe"

        val bindGpu = mutableListOf<String>()
        if (backend == GraphicsBackend.GpuStack && GpuStack.hasTurnip(ctx)) {
            GpuStack.turnipSo(ctx).setExecutable(true, false)
            bindGpu += listOf("-b", "${GpuStack.turnipDir(ctx).absolutePath}:/mnt/gpu/turnip")
            // /dev 已 bind；KGSL 節點若存在則客體可見
            val kgsl = java.io.File("/dev/kgsl-3d0")
            if (kgsl.exists()) {
                bindGpu += listOf("-b", "/dev/kgsl-3d0:/dev/kgsl-3d0")
            }
        }
        if (backend == GraphicsBackend.GpuStack && GpuStack.hasDxvk(ctx)) {
            bindGpu += listOf("-b", "${GpuStack.dxvkDir(ctx).absolutePath}:/mnt/gpu/dxvk")
            GpuStack.stageDxvkIntoPrefix(ctx, prefix)
        }
        if (backend == GraphicsBackend.GpuStack) {
            GpuStack.stageGladioIntoRootfs(ctx)
            GpuStack.stageAlsaAAudioIntoRootfs(ctx)
        }

        val appBindSrc = ws.exeDir.absolutePath
        val argv = buildList {
            add(proot.absolutePath)
            add("--kill-on-exit")
            add("--link2symlink")
            add("--sysvipc")
            add("--kernel-release=6.2.1")
            add("-r"); add(File(runtime, "rootfs").absolutePath)
            add("-b"); add("/dev")
            add("-b"); add("/proc")
            add("-b"); add("/sys")
            add("-b"); add("${appBindSrc}:/mnt/app")
            add("-b"); add("${prefix.absolutePath}:/home/xuser/.wine")
            add("-b"); add("${ws.dataDir.absolutePath}:/mnt/data")
            // 進程內 X／SysVSHM／音訊 socket（宿主 imagefs）
            val imageFs = File(ctx.filesDir, "engine/imagefs")
            val x11 = File(imageFs, "tmp/.X11-unix").also { it.mkdirs() }
            val shm = File(imageFs, "tmp/.sysvshm").also { it.mkdirs() }
            val sound = File(imageFs, "tmp/.sound").also { it.mkdirs() }
            add("-b"); add("${x11.absolutePath}:/tmp/.X11-unix")
            add("-b"); add("${shm.absolutePath}:/tmp/.sysvshm")
            add("-b"); add("${sound.absolutePath}:/tmp/.sound")
            addAll(bindGpu)
            add("-w"); add("/mnt/app")
            add(guestShell)
            add("/usr/local/bin/wine-launch.sh")
            add(guestExe)
        }

        val env = mutableListOf(
            "HOME=/home/xuser",
            "USER=xuser",
            "LOGNAME=xuser",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "X86_GUEST_SHELL=$guestShell",
            "WINEPREFIX=/home/xuser/.wine",
            "WINEDEBUG=-all",
            "WINEARCH=win64",
            "DISPLAY=:0",
            "WINE_DESKTOP_W=1280",
            "WINE_DESKTOP_H=720",
            "BOX64_PATH=/opt/wine/bin",
            // Box64 搜尋 x86_64 模擬庫（與 aarch64 LD_LIBRARY_PATH 分離）；含 Wine unix＋x64lib
            "BOX64_LD_LIBRARY_PATH=/opt/wine/lib/wine/x86_64-unix:/usr/lib/box64-x86_64-linux-gnu:/lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu",
            "WINEDLLPATH=/opt/wine/lib/wine",
            // WINELOADER 必須是帶 wine_main_preload_info 的 x86_64-unix/wine（避 c0000018）
            "WINELOADER=/opt/wine/lib/wine/x86_64-unix/wine",
            "WINELOADERNOEXEC=1",
            // 0.1.34: Winlator-style; Wine11 granularity needs Box64 wine64k prereserve
            "BOX64_MMAP32=0",
            "WINEPRELOADRESERVE=",
            "WINE_REAL=/opt/wine/lib/wine/x86_64-unix/wine",
            "WINESERVER_REAL=/opt/wine/bin/wineserver",
            "ANDROID_SYSVSHM_SERVER=/tmp/.sysvshm/SM0",
            "LANG=C.UTF-8",
            "LD_PRELOAD=", // 清掉可能的 termux-exec
        )
        env += prootBundle.envVars()
        env += Box64Preset.performance.toList()

        val kindMessage: String
        when (backend) {
            GraphicsBackend.GpuStack -> {
                env += GpuStack.guestEnv(ctx, ws.dataDir.absolutePath)
                kindMessage =
                    "Windows PE · GpuStack＋進程內 X（Winlator）→AHB Present · PRoot→Box64→Wine ${pe.name}"
            }
            GraphicsBackend.SoftLegacy -> {
                env += "GRAPHICS_BACKEND=soft"
                env += "LIBGL_ALWAYS_SOFTWARE=1"
                env += "GALLIUM_DRIVER=llvmpipe"
                kindMessage =
                    "Windows PE · 【工程 SoftLegacy】Xvfb→XWD→Surface · PRoot→Box64→Wine ${pe.name}"
            }
        }

        // 去重＋強制 nativeLibraryDir 在 LD_LIBRARY_PATH 最前（防 guestEnv 覆寫）
        val mergedEnv = mergeEnv(env, hostLibDir = prootBundle.libDir.absolutePath)

        return Plan(
            argv = argv,
            cwd = ws.dataDir.absolutePath,
            logFile = logFile,
            kind = BinaryKind.Pe,
            kindMessage = kindMessage,
            mode = Mode.WineProot,
            targetFile = pe,
            env = mergedEnv,
            needsRuntimeInstall = false,
            graphicsBackend = backend,
        )
    }

    fun start(plan: Plan): Result {
        if (plan.gpuBlocked || plan.mode == Mode.WineGpuPending) {
            val text = "GPU_STACK_PENDING:\n${plan.kindMessage}\n"
            plan.logFile.writeText(text)
            return Result(false, -1, plan.kindMessage, plan)
        }
        if (plan.needsRuntimeInstall) {
            plan.logFile.writeText("NEED_RUNTIME: ${plan.kindMessage}\n")
            return Result(false, -1, plan.kindMessage, plan)
        }
        if (plan.mode == Mode.Unsupported || plan.argv.isEmpty()) {
            plan.logFile.writeText("UNSUPPORTED: ${plan.kindMessage}\n")
            return Result(false, -1, plan.kindMessage, plan)
        }
        ExecBits.force(plan.targetFile)
        val exe = File(plan.argv[0])
        ExecBits.force(exe)
        // 若 files/proot 仍不可執行，改走 native libproot
        if (exe.name == "proot" && !exe.canExecute()) {
            // 由呼叫端 ensureProot 保證；此處再保險
        }
        val rc = NativeEngine.nativeExec(
            plan.argv.toTypedArray(),
            plan.cwd,
            plan.logFile.absolutePath,
            plan.env.toTypedArray().ifEmpty { null },
        )
        return if (rc > 0) {
            Result(true, rc, "已啟動 pid=$rc（${plan.mode}／${plan.graphicsBackend}）\n${plan.kindMessage}", plan)
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
            if (f.exists() && f.isFile && f.length() > 0L) {
                f.copyTo(staged, overwrite = true)
                return staged
            }
            if (!f.exists()) {
                throw IllegalStateException(
                    "找不到來源檔案：${f.absolutePath}。請重新選取 .exe。",
                )
            }
        }

        val uriStr = targetUri?.takeIf { it.isNotBlank() }
            ?: targetPath?.takeIf { it.startsWith("content:") }
        if (!uriStr.isNullOrBlank()) {
            val uri = Uri.parse(uriStr)
            val opened = runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(staged).use { output -> input.copyTo(output) }
                }
            }.getOrElse { e ->
                throw IllegalStateException(
                    "無法讀取已選檔案（權限可能已失效）。請重新選取 .exe。${e.message?.let { "（$it）" } ?: ""}",
                    e,
                )
            }
            if (opened == null) {
                throw IllegalStateException(
                    "無法開啟已選檔案（content URI）。請重新選取 .exe。",
                )
            }
            if (staged.exists() && staged.length() > 0) return staged
            throw IllegalStateException(
                "已選檔案為空或複製失敗。請重新選取 .exe。",
            )
        }

        // #15：禁止 silently 回落 hostArm64，避免「看起來成功、跑的不是用戶檔」
        throw IllegalStateException(
            "無法解析啟動目標（工作區尚無副本，且來源路徑／URI 無效）。請重新選取 .exe。",
        )
    }
}
