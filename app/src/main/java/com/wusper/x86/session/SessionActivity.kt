package com.wusper.x86.session

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.wusper.x86.WorkspaceStore
import com.wusper.x86.engine.ComponentInstaller
import com.wusper.x86.engine.ExitStatusMessages
import com.wusper.x86.engine.GpuStack
import com.wusper.x86.x11.InProcessX
import com.wusper.x86.audio.InProcessAudio
import com.wusper.x86.x11.InProcessXInput
import com.wusper.x86.engine.NativeEngine
import com.wusper.x86.engine.GraphicsBackend
import com.wusper.x86.engine.LaunchPipeline
import com.wusper.x86.graphics.SurfaceFrameBridge
import com.wusper.x86.graphics.PresentSurfaceBridge
import com.wusper.x86.graphics.X11InputBridge
import com.wusper.x86.ui.theme.X86Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class SessionActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                LaunchPipeline.stop()
                EngineForegroundService.stop(this@SessionActivity)
                finish()
            }
        })

        val workspaceId = intent.getStringExtra(EXTRA_WORKSPACE_ID).orEmpty()
        val forceSmoke = intent.getBooleanExtra(EXTRA_FORCE_SMOKE, false)
        val forceGuestSmoke = intent.getBooleanExtra(EXTRA_FORCE_GUEST_SMOKE, false)
        val preferAndroidGuest = intent.getBooleanExtra(EXTRA_PREFER_ANDROID_GUEST, true)
        val forcePeSmoke = intent.getBooleanExtra(EXTRA_FORCE_PE_SMOKE, false)
        val ws = WorkspaceStore.listWorkspaces(this).firstOrNull { it.id == workspaceId }

        setContent {
            X86Theme {
                var logText by remember { mutableStateOf("準備啟動…\n") }
                val logScroll = rememberScrollState()
                val scope = rememberCoroutineScope()
                var logHighlightEpoch by remember { mutableIntStateOf(0) }
                var logExpanded by remember { mutableStateOf(false) }
                fun annotatedLog(src: String) = buildAnnotatedString {
                    if (src.isEmpty()) { append(""); return@buildAnnotatedString }
                    val lower = src.lowercase()
                    val marks = BooleanArray(src.length)
                    for (pat in KEY_LOG_PATTERNS) {
                        val p = pat.lowercase()
                        var i = 0
                        while (true) {
                            val j = lower.indexOf(p, i)
                            if (j < 0) break
                            for (k in j until (j + pat.length).coerceAtMost(src.length)) marks[k] = true
                            i = j + 1
                        }
                    }
                    // expand marks to full lines
                    val lineMarks = BooleanArray(src.length)
                    var lineStart = 0
                    for (i in src.indices) {
                        if (src[i] == '\n' || i == src.lastIndex) {
                            val end = if (src[i] == '\n') i else i + 1
                            var hit = false
                            for (k in lineStart until end) if (marks[k]) { hit = true; break }
                            if (hit) for (k in lineStart until end) lineMarks[k] = true
                            lineStart = i + 1
                        }
                    }
                    var i = 0
                    while (i < src.length) {
                        if (!lineMarks[i]) {
                            val start = i
                            while (i < src.length && !lineMarks[i]) i++
                            append(src.substring(start, i))
                        } else {
                            val start = i
                            while (i < src.length && lineMarks[i]) i++
                            withStyle(
                                SpanStyle(
                                    background = Color(0x66FFEB3B),
                                    color = Color(0xFF1A1A1A),
                                    fontWeight = FontWeight.SemiBold,
                                ),
                            ) { append(src.substring(start, i)) }
                        }
                    }
                }
                fun copyFullLog() {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("x86-session-log", logText))
                    Toast.makeText(this@SessionActivity, getString(com.wusper.x86.R.string.toast_log_copied), Toast.LENGTH_SHORT).show()
                }
                fun jumpToKeyLog() {
                    val lines = logText.split('\n')
                    var idx = -1
                    for ((i, line) in lines.withIndex()) {
                        val low = line.lowercase()
                        if (KEY_LOG_PATTERNS.any { it.lowercase() in low }) { idx = i; break }
                    }
                    if (idx < 0) {
                        Toast.makeText(this@SessionActivity, getString(com.wusper.x86.R.string.toast_key_not_found), Toast.LENGTH_SHORT).show()
                        return
                    }
                    logHighlightEpoch++
                    // approx 14sp monospace ≈ 42px per line
                    val y = (idx * 42).coerceAtLeast(0)
                    scope.launch {
                        logScroll.animateScrollTo(y.coerceAtMost(logScroll.maxValue))
                    }
                    Toast.makeText(this@SessionActivity, getString(com.wusper.x86.R.string.toast_jumped_key), Toast.LENGTH_SHORT).show()
                }
                var status by remember { mutableStateOf("初始化") }
                var installProgress by remember { mutableStateOf<ComponentInstaller.Progress?>(null) }
                var installing by remember { mutableStateOf(false) }
                var logFile by remember { mutableStateOf<File?>(null) }
                var frameBridge by remember { mutableStateOf<SurfaceFrameBridge?>(null) }
                var presentBridge by remember { mutableStateOf<PresentSurfaceBridge?>(null) }
                var inProcessAudio by remember { mutableStateOf<InProcessAudio?>(null) }
                var inProcessX by remember { mutableStateOf<InProcessX?>(null) }
                val surfaceHolderRef = remember { AtomicReference<SurfaceHolder?>(null) }
                val inputBridgeRef = remember { AtomicReference<X11InputBridge?>(null) }
                val inProcessInputRef = remember { AtomicReference<InProcessXInput?>(null) }

                var sessionEpoch by remember { mutableIntStateOf(0) }
                var showErrorCard by remember { mutableStateOf(false) }
                var errorTitle by remember { mutableStateOf("") }
                var errorDetail by remember { mutableStateOf("") }
                var errorRaw by remember { mutableStateOf("") }
                var gpuFailed by remember { mutableStateOf(false) }
                var gpuRetryEpoch by remember { mutableIntStateOf(0) }
                var pendingDownloadConfirm by remember { mutableStateOf(false) }
                var downloadConfirmAck by remember { mutableStateOf(false) }
                var showClearConfirm by remember { mutableStateOf(false) }
                var menuExpanded by remember { mutableStateOf(false) }
                var launchFailedBeforeStart by remember { mutableStateOf(false) }
                var pendingKgslConfirm by remember { mutableStateOf(false) }
                var kgslConfirmAck by remember { mutableStateOf(false) }
                var kgslConfirmSkip by remember { mutableStateOf(false) }

                fun presentFailure(title: String, detail: String, raw: String = "") {
                    errorTitle = title
                    var d = detail
                    if (ExitStatusMessages.shouldSuggestReinstall(detail) &&
                        "重裝運行時" !in detail
                    ) {
                        d = detail + "\n" + ComponentInstaller.USER_HINT_REINSTALL_RUNTIME
                    }
                    errorDetail = d
                    errorRaw = raw
                    showErrorCard = true
                    status = title
                }

                fun stopBridges() {
                    inputBridgeRef.get()?.shutdown()
                    inProcessInputRef.get()?.shutdown()
                    frameBridge?.shutdown()
                    presentBridge?.shutdown()
                    inProcessAudio?.stop()
                    inProcessX?.stop()
                    LaunchPipeline.stop()
                    EngineForegroundService.stop(this@SessionActivity)
                }

                fun clearAndRetry() {
                    showClearConfirm = false
                    showErrorCard = false
                    gpuFailed = false
                    launchFailedBeforeStart = false
                    downloadConfirmAck = false
                    pendingDownloadConfirm = false
                    kgslConfirmAck = false
                    kgslConfirmSkip = false
                    pendingKgslConfirm = false
                    stopBridges()
                    val freed = ComponentInstaller.clearRuntime(this@SessionActivity, alsoClearCache = true)
                    val mb = "%.1f".format(freed / 1e6)
                    logText = "已清除運行時（約 ${mb}MB）。重新準備下載…\n"
                    status = "已清除運行時"
                    sessionEpoch++
                }

                fun softRetry() {
                    showErrorCard = false
                    gpuFailed = false
                    launchFailedBeforeStart = false
                    stopBridges()
                    logText = "重試啟動…\n"
                    status = "重試"
                    sessionEpoch++
                }

                LaunchedEffect(workspaceId, forceSmoke, forceGuestSmoke, preferAndroidGuest, forcePeSmoke, sessionEpoch) {
                    showErrorCard = false
                    gpuFailed = false
                    launchFailedBeforeStart = false
                    if (ws == null) {
                        presentFailure("錯誤", "找不到工作區：$workspaceId")
                        logText += "找不到工作區：$workspaceId\n"
                        return@LaunchedEffect
                    }
                    ensurePostNotificationsPermission()
                    EngineForegroundService.start(this@SessionActivity, ws.displayName)
                    val plan0 = try {
                        LaunchPipeline.prepare(
                            ctx = this@SessionActivity,
                            ws = ws,
                            targetPath = ws.sourceExePath,
                            targetUri = ws.sourceExePath.takeIf { it.startsWith("content:") },
                            forceHostSmoke = forceSmoke,
                            forceGuestSmoke = forceGuestSmoke,
                            preferAndroidGuest = preferAndroidGuest,
                            forcePeSmoke = forcePeSmoke,
                        )
                    } catch (t: Throwable) {
                        presentFailure(
                            "無法解析啟動目標",
                            t.message ?: "未知錯誤",
                            t.javaClass.simpleName,
                        )
                        logText += "準備失敗：${t.message}\n"
                        EngineForegroundService.stop(this@SessionActivity)
                        launchFailedBeforeStart = true
                        return@LaunchedEffect
                    }
                    var plan = plan0
                    logFile = plan.logFile
                    logText += "目標：${plan.targetFile.absolutePath}\n"
                    logText += "類型：${plan.kindMessage}\n"
                    logText += "模式：${plan.mode}／圖形：${plan.graphicsBackend}\n"
                    if (plan.needsRuntimeInstall || (plan.mode == LaunchPipeline.Mode.WineProot && !ComponentInstaller.isReady(this@SessionActivity))) {
                        if (!downloadConfirmAck) {
                            pendingDownloadConfirm = true
                            status = "等待確認下載"
                            // 等用戶確認或取消（由對話框改 sessionEpoch／ack）
                            while (pendingDownloadConfirm && !downloadConfirmAck) {
                                delay(200)
                            }
                            if (!downloadConfirmAck) {
                                status = "已取消"
                                presentFailure(
                                    "已取消下載",
                                    "未下載運行時，無法啟動 Windows 程式。",
                                )
                                EngineForegroundService.stop(this@SessionActivity)
                                return@LaunchedEffect
                            }
                        }
                        try {
                            ComponentInstaller.assertEnoughSpace(this@SessionActivity)
                        } catch (t: Throwable) {
                            presentFailure(
                                "儲存空間不足",
                                t.message ?: "請腾出空間後再試。",
                            )
                            logText += "空間預檢失敗：${t.message}\n"
                            EngineForegroundService.stop(this@SessionActivity)
                            launchFailedBeforeStart = true
                            return@LaunchedEffect
                        }
                        status = "安裝運行時"
                        installing = true
                        installProgress = ComponentInstaller.Progress("download", message = "準備下載…")
                        val sizeLabel = ComponentInstaller.approxDownloadMbLabel(this@SessionActivity)
                        logText += "首次 PE：下載 Wine／glibc rootfs／Box64（$sizeLabel，可取消／斷點續傳）…\n"
                        val install = withContext(Dispatchers.IO) {
                            ComponentInstaller.install(this@SessionActivity) { p ->
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    installProgress = p
                                    status = when (p.stage) {
                                        "download" -> {
                                            val pct = if (p.total > 0) " ${p.current * 100 / p.total}%" else ""
                                            val mb = if (p.total > 0)
                                                " ${"%.1f".format(p.current / 1e6)}/${"%.1f".format(p.total / 1e6)}MB"
                                            else ""
                                            val n = if (p.componentCount > 0)
                                                " (${p.componentIndex}/${p.componentCount})"
                                            else ""
                                            "下載 ${p.componentId.ifBlank { "組件" }}$n$pct$mb"
                                        }
                                        "extract" -> "解壓 ${p.componentId.ifBlank { "組件" }}…"
                                        else -> p.message.ifBlank { p.stage }
                                    }
                                }
                            }
                        }
                        installing = false
                        installProgress = null
                        if (install.isFailure) {
                            val err = install.exceptionOrNull()
                            val title = if (err is ComponentInstaller.CancelledException) "已取消" else "安裝失敗"
                            presentFailure(
                                title,
                                (err?.message ?: "未知錯誤") + "\n" + ComponentInstaller.USER_HINT_REINSTALL_RUNTIME,
                                err?.javaClass?.simpleName ?: "",
                            )
                            logText += "安裝失敗：${err?.message}\n"
                            EngineForegroundService.stop(this@SessionActivity)
                            launchFailedBeforeStart = true
                            return@LaunchedEffect
                        }
                        logText += "Wine 運行時就緒，重建計畫…\n"
                        plan = try {
                            LaunchPipeline.buildWineProotPlan(
                                this@SessionActivity, ws, plan.targetFile, plan.logFile,
                            )
                        } catch (t: Throwable) {
                            presentFailure(
                                "運行時校驗失敗",
                                (t.message ?: "未知錯誤"),
                                t.javaClass.simpleName,
                            )
                            logText += "重建計畫失敗：${t.message}\n"
                            EngineForegroundService.stop(this@SessionActivity)
                            launchFailedBeforeStart = true
                            return@LaunchedEffect
                        }
                        logText += "模式：${plan.mode}／${plan.graphicsBackend}\n${plan.kindMessage}\n"
                    }
                    // GpuStack：可重試
                    if (plan.needsGpuStackInstall ||
                        (plan.graphicsBackend == GraphicsBackend.GpuStack && !GpuStack.componentsInstalled(this@SessionActivity))
                    ) {
                        status = "安裝 GPU 棧"
                        logText += "下載 Turnip＋DXVK（GpuStack 組件）…\n"
                        val gpu = withContext(Dispatchers.IO) {
                            GpuStack.installComponents(this@SessionActivity) { msg ->
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    status = msg
                                }
                            }
                        }
                        if (gpu.isFailure) {
                            gpuFailed = true
                            val msg = gpu.exceptionOrNull()?.message ?: "未知錯誤"
                            logText += "GPU 組件下載失敗：$msg\n"
                            presentFailure(
                                "GPU 組件下載失敗",
                                "$msg\n可點「重試」再下載 Turnip／DXVK，無需離開此頁。",
                                "gpu",
                            )
                        } else {
                            logText += "GPU 組件：${GpuStack.statusMessage(this@SessionActivity)}\n"
                            plan = LaunchPipeline.buildWineProotPlan(
                                this@SessionActivity, ws, plan.targetFile, plan.logFile,
                            )
                            logText += "重建：${plan.mode}\n${plan.kindMessage}\n"
                        }
                    }
                    // #10：無 KGSL／非 Adreno → 啟動前警告（可繼續）
                    if (plan.mode == LaunchPipeline.Mode.WineProot &&
                        plan.graphicsBackend == GraphicsBackend.GpuStack &&
                        !plan.gpuBlocked &&
                        !GpuStack.hasKgslDevice()
                    ) {
                        if (!kgslConfirmAck && !kgslConfirmSkip) {
                            pendingKgslConfirm = true
                            status = "裝置相容性警告"
                            logText += "未偵測到 /dev/kgsl-3d0（非 Adreno／無 KGSL），可能無畫面。\n"
                            while (pendingKgslConfirm && !kgslConfirmAck && !kgslConfirmSkip) {
                                delay(200)
                            }
                            if (kgslConfirmSkip || (!kgslConfirmAck && !pendingKgslConfirm)) {
                                status = "已取消"
                                presentFailure(
                                    "已取消啟動",
                                    "此裝置可能無法顯示畫面（需 Adreno／KGSL）。詳見相容說明。",
                                )
                                EngineForegroundService.stop(this@SessionActivity)
                                launchFailedBeforeStart = true
                                return@LaunchedEffect
                            }
                        }
                    }

                    // SoftLegacy 輸入橋
                    if (plan.mode == LaunchPipeline.Mode.WineProot &&
                        plan.graphicsBackend == GraphicsBackend.SoftLegacy &&
                        !plan.needsRuntimeInstall
                    ) {
                        val input = X11InputBridge(ws.dataDir)
                        input.prepareFifo()
                        val agentCopy = java.io.File(ws.dataDir, "x11-input-agent.sh")
                        this@SessionActivity.assets.open("engine/x11-input-agent.sh").use { inp ->
                            agentCopy.outputStream().use { out -> inp.copyTo(out) }
                        }
                        agentCopy.setExecutable(true, false)
                        inputBridgeRef.set(input)
                        logText += "輸入橋：【SoftLegacy】xdotool\n"
                    }
                    if (plan.mode == LaunchPipeline.Mode.WineProot &&
                        plan.graphicsBackend == GraphicsBackend.GpuStack
                    ) {
                        status = "啟動進程內 X"
                        logText += "啟動 Winlator Java X → Present…\n"
                        val x = InProcessX(this@SessionActivity)
                        NativeEngine.nativePresentCreate(1280, 720)
                        x.start()
                        inProcessX = x
                        val audio = InProcessAudio(this@SessionActivity)
                        audio.start()
                        inProcessAudio = audio
                        logText += "音訊：ALSA→AAudio socket=${audio.socketPath()}\n"
                        val xs = x.xServer
                        if (xs != null) {
                            val xin = InProcessXInput(xs)
                            xin.updateBlitFromSurface(1280, 720)
                            inProcessInputRef.set(xin)
                            logText += "輸入橋：WinHandler 級（相對滑鼠／雙指右鍵／長按右鍵／滾輪／可選手把）\n"
                        }
                        logText += "InProcessX imagefs=${x.imageFsRoot.absolutePath}\n"
                    }
                    val result = LaunchPipeline.start(plan)
                    status = when {
                        result.ok -> "執行中 pid=${result.pid}"
                        plan.mode == LaunchPipeline.Mode.WineGpuPending -> "GpuStack 待組件"
                        else -> "失敗"
                    }
                    logText += result.message + "\n"
                    if (!result.ok && plan.mode != LaunchPipeline.Mode.WineGpuPending) {
                        presentFailure(
                            "啟動失敗",
                            result.message,
                            "start",
                        )
                        launchFailedBeforeStart = true
                    }

                    var waited = 0
                    while (surfaceHolderRef.get() == null && waited < 50) {
                        delay(100); waited++
                    }
                    val holder = surfaceHolderRef.get()
                    val surf = holder?.surface

                    if (result.ok && plan.mode == LaunchPipeline.Mode.WineProot &&
                        plan.graphicsBackend == GraphicsBackend.GpuStack && surf != null && surf.isValid
                    ) {
                        presentBridge?.shutdown()
                        frameBridge?.shutdown()
                        NativeEngine.nativePresentAttachSurface(surf)
                        logText += "Present：進程內 X Drawable → AHB → Surface（已 attach）\n"
                        val bridge = PresentSurfaceBridge(
                            dataDir = ws.dataDir,
                            surface = surf,
                            targetFps = 15,
                            allowXwdFeeder = false,
                            allowStub = false,
                        )
                        presentBridge = bridge
                    } else if (result.ok && plan.mode == LaunchPipeline.Mode.WineProot &&
                        plan.graphicsBackend == GraphicsBackend.SoftLegacy && holder != null
                    ) {
                        val fbDir = java.io.File(ws.dataDir, "xfb").also { it.mkdirs() }
                        frameBridge?.shutdown()
                        val input = inputBridgeRef.get()
                        val bridge = SurfaceFrameBridge(
                            fbDir, holder, targetFps = 15,
                            onBlitRect = { r -> input?.blitRect = r },
                        )
                        frameBridge = bridge
                        bridge.start()
                        logText += "【SoftLegacy】Xvfb XWD→Canvas（非產品預設）\n"
                    }

                    if (plan.mode == LaunchPipeline.Mode.WineGpuPending || plan.gpuBlocked) {
                        if (surf != null && surf.isValid) {
                            presentBridge?.shutdown()
                            val bridge = PresentSurfaceBridge(
                                dataDir = ws.dataDir,
                                surface = surf,
                                targetFps = 20,
                                allowXwdFeeder = false,
                                allowStub = true,
                            )
                            presentBridge = bridge
                            bridge.start()
                            logText += "Present stub 演示（組件未齊／待啟動）\n"
                            status = "Present stub"
                        }
                        if (!result.ok) {
                            presentFailure(
                                "GPU 棧尚未就緒",
                                plan.kindMessage.ifBlank { "缺少 Turnip／DXVK 或其他 GpuStack 條件。" },
                                "gpu-pending",
                            )
                            EngineForegroundService.stop(this@SessionActivity)
                            return@LaunchedEffect
                        }
                    }
                    if (!result.ok) {
                        EngineForegroundService.stop(this@SessionActivity)
                        return@LaunchedEffect
                    }
                    while (true) {
                        delay(400)
                        val wait = NativeEngine.nativeWaitNonBlocking()
                        val lf = logFile
                        if (lf != null && lf.exists()) {
                            val session = lf.readText()
                            val gpuLog = ws?.dataDir?.let { File(it, "gpu-stack.log") }
                            logText = if (gpuLog != null && gpuLog.exists()) {
                                session + "\n--- gpu-stack.log ---\n" + gpuLog.readText()
                            } else {
                                session
                            }
                        }
                        if (wait != 1) {
                            val pid = NativeEngine.nativeRunningPid()
                            if (pid <= 0) {
                                val decoded = ExitStatusMessages.decode(wait)
                                status = ExitStatusMessages.statusLine(wait)
                                if (decoded.isFailure) {
                                    presentFailure(
                                        decoded.title,
                                        decoded.detail,
                                        "原始碼 ${decoded.rawCode}" +
                                            (decoded.exitStatus?.let { " · exit=$it" } ?: "") +
                                            (decoded.signal?.let { " · signal=$it" } ?: ""),
                                    )
                                }
                                EngineForegroundService.stop(this@SessionActivity)
                                break
                            }
                        }
                    }
                }

                // GPU 單項重試（不整頁重跑 runtime install）
                LaunchedEffect(gpuRetryEpoch) {
                    if (gpuRetryEpoch == 0) return@LaunchedEffect
                    if (ws == null) return@LaunchedEffect
                    showErrorCard = false
                    gpuFailed = false
                    status = "重試 GPU 棧"
                    logText += "重試下載 Turnip＋DXVK…\n"
                    val gpu = withContext(Dispatchers.IO) {
                        GpuStack.installComponents(this@SessionActivity) { msg ->
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                status = msg
                            }
                        }
                    }
                    if (gpu.isFailure) {
                        gpuFailed = true
                        presentFailure(
                            "GPU 組件下載失敗",
                            (gpu.exceptionOrNull()?.message ?: "未知錯誤") + "\n可再次重試。",
                            "gpu",
                        )
                    } else {
                        logText += "GPU 組件就緒，重新啟動工作階段…\n"
                        softRetry()
                    }
                }

                DisposableEffect(Unit) {
                    onDispose {
                        stopBridges()
                    }
                }

                if (pendingDownloadConfirm && !downloadConfirmAck) {
                    AlertDialog(
                        onDismissRequest = {
                            pendingDownloadConfirm = false
                            downloadConfirmAck = false
                        },
                        title = { Text(getString(com.wusper.x86.R.string.dialog_download_title)) },
                        text = {
                            Text(
                                getString(
                                    com.wusper.x86.R.string.dialog_download_message,
                                    ComponentInstaller.approxDownloadMbLabel(this@SessionActivity),
                                ),
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                downloadConfirmAck = true
                                pendingDownloadConfirm = false
                            }) {
                                Text(getString(com.wusper.x86.R.string.dialog_download_confirm))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                downloadConfirmAck = false
                                pendingDownloadConfirm = false
                            }) {
                                Text(getString(com.wusper.x86.R.string.dialog_cancel))
                            }
                        },
                    )
                }

                if (pendingKgslConfirm && !kgslConfirmAck && !kgslConfirmSkip) {
                    AlertDialog(
                        onDismissRequest = {
                            pendingKgslConfirm = false
                            kgslConfirmSkip = true
                        },
                        title = { Text(getString(com.wusper.x86.R.string.dialog_kgsl_title)) },
                        text = { Text(getString(com.wusper.x86.R.string.dialog_kgsl_message)) },
                        confirmButton = {
                            TextButton(onClick = {
                                kgslConfirmAck = true
                                pendingKgslConfirm = false
                            }) {
                                Text(getString(com.wusper.x86.R.string.dialog_kgsl_continue))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                kgslConfirmSkip = true
                                pendingKgslConfirm = false
                            }) {
                                Text(getString(com.wusper.x86.R.string.dialog_cancel))
                            }
                        },
                    )
                }

                if (showClearConfirm) {
                    AlertDialog(
                        onDismissRequest = { showClearConfirm = false },
                        title = { Text(getString(com.wusper.x86.R.string.dialog_clear_runtime_title)) },
                        text = { Text(getString(com.wusper.x86.R.string.dialog_clear_runtime_message)) },
                        confirmButton = {
                            TextButton(onClick = { clearAndRetry() }) {
                                Text(getString(com.wusper.x86.R.string.dialog_clear_runtime_confirm))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showClearConfirm = false }) {
                                Text(getString(com.wusper.x86.R.string.dialog_cancel))
                            }
                        },
                    )
                }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Column {
                                    Text(ws?.displayName ?: getString(com.wusper.x86.R.string.session_title))
                                    Text(
                                        text = getString(com.wusper.x86.R.string.session_desktop_hint),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            navigationIcon = {
                                IconButton(onClick = {
                                    stopBridges()
                                    finish()
                                }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                                }
                            },
                            actions = {
                                IconButton(onClick = { menuExpanded = true }) {
                                    Icon(
                                        Icons.Default.MoreVert,
                                        contentDescription = getString(com.wusper.x86.R.string.menu_more),
                                    )
                                }
                                DropdownMenu(
                                    expanded = menuExpanded,
                                    onDismissRequest = { menuExpanded = false },
                                ) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(getString(com.wusper.x86.R.string.btn_clear_runtime))
                                        },
                                        onClick = {
                                            menuExpanded = false
                                            showClearConfirm = true
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.Delete, contentDescription = null)
                                        },
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                            ),
                        )
                    },
                ) { padding ->
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(padding),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .background(Color(0xFF0A0E14)),
                        ) {
                            AndroidView(
                                factory = { ctx ->
                                    SurfaceView(ctx).apply {
                                        isFocusable = true
                                        isFocusableInTouchMode = true
                                        holder.addCallback(object : SurfaceHolder.Callback {
                                            override fun surfaceCreated(holder: SurfaceHolder) {
                                                surfaceHolderRef.set(holder)
                                                requestFocus()
                                            }
                                            override fun surfaceChanged(
                                                holder: SurfaceHolder,
                                                format: Int, width: Int, height: Int,
                                            ) {
                                                surfaceHolderRef.set(holder)
                                                inProcessInputRef.get()?.updateBlitFromSurface(width, height)
                                                inputBridgeRef.get()?.blitRect =
                                                    com.wusper.x86.graphics.DesktopLayout.fitRect(
                                                        1280, 720, width, height,
                                                    )
                                            }
                                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                                frameBridge?.shutdown()
                                                frameBridge = null
                                                presentBridge?.shutdown()
                                                presentBridge = null
                                                NativeEngine.nativePresentDetach()
                                                surfaceHolderRef.set(null)
                                            }
                                        })
                                        setOnTouchListener { _, ev ->
                                            inProcessInputRef.get()?.onTouch(ev)
                                                ?: inputBridgeRef.get()?.onTouch(ev)
                                                ?: false
                                        }
                                        setOnGenericMotionListener { _, ev ->
                                            inProcessInputRef.get()?.onGenericMotion(ev)
                                                ?: inputBridgeRef.get()?.onGenericMotion(ev)
                                                ?: false
                                        }
                                        setOnKeyListener { _, _, ev ->
                                            inProcessInputRef.get()?.onKey(ev)
                                                ?: inputBridgeRef.get()?.onKey(ev)
                                                ?: false
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (status.startsWith("執行中") && !showErrorCard) {
                                Text(
                                    text = getString(com.wusper.x86.R.string.session_waiting_surface),
                                    color = Color(0x99FFFFFF),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.align(Alignment.Center),
                                )
                            }
                            if (showErrorCard) {
                                Card(
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .padding(20.dp)
                                        .fillMaxWidth(0.92f),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                                    ),
                                ) {
                                    Column(Modifier.padding(16.dp)) {
                                        Text(
                                            text = errorTitle.ifBlank { getString(com.wusper.x86.R.string.error_card_title) },
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            text = errorDetail,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        if (errorRaw.isNotBlank()) {
                                            Spacer(Modifier.height(6.dp))
                                            Text(
                                                text = errorRaw,
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontFamily = FontFamily.Monospace,
                                                ),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Spacer(Modifier.height(14.dp))
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Button(
                                                onClick = {
                                                    if (gpuFailed) {
                                                        gpuRetryEpoch++
                                                    } else {
                                                        softRetry()
                                                    }
                                                },
                                                modifier = Modifier.weight(1f),
                                            ) {
                                                Text(getString(com.wusper.x86.R.string.btn_retry))
                                            }
                                            OutlinedButton(
                                                onClick = { showClearConfirm = true },
                                                modifier = Modifier.weight(1f),
                                            ) {
                                                Text(getString(com.wusper.x86.R.string.btn_clear_runtime))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Text(
                            text = "狀態：$status",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        Text(
                            text = getString(com.wusper.x86.R.string.session_desktop_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 0.dp),
                        )
                        if (installing) {
                            val p = installProgress
                            val frac = if (p != null && p.total > 0L) {
                                (p.current.toFloat() / p.total.toFloat()).coerceIn(0f, 1f)
                            } else 0f
                            LinearProgressIndicator(
                                progress = { frac },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = p?.message?.ifBlank { status } ?: status,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
                                Button(onClick = {
                                    ComponentInstaller.requestCancel()
                                    logText += "正在取消安裝…\n"
                                }) {
                                    Text("取消下載")
                                }
                            }
                        }
                        // 執行中預設收合日誌，把版面留給 Surface；錯誤／安裝時自動展開
                        val running = status.startsWith("執行中")
                        val showLogPanel = logExpanded || showErrorCard || installing || !running
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = { logExpanded = !logExpanded },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    getString(
                                        if (showLogPanel) com.wusper.x86.R.string.btn_toggle_log_hide
                                        else com.wusper.x86.R.string.btn_toggle_log_show,
                                    ),
                                )
                            }
                            OutlinedButton(
                                onClick = { copyFullLog() },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(getString(com.wusper.x86.R.string.btn_copy_log))
                            }
                            OutlinedButton(
                                onClick = {
                                    logExpanded = true
                                    jumpToKeyLog()
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(getString(com.wusper.x86.R.string.btn_jump_key))
                            }
                        }
                        if (running && !showLogPanel) {
                            Text(
                                text = getString(com.wusper.x86.R.string.session_waiting_surface),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                        if (showLogPanel) {
                            SelectionContainer {
                                Text(
                                    text = annotatedLog(logText + if (logHighlightEpoch >= 0) "" else ""),
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(if (running && !showErrorCard) 0.35f else 0.7f)
                                        .padding(horizontal = 16.dp, vertical = 4.dp)
                                        .verticalScroll(logScroll),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /** API 33+：啟動前景服務前請求通知權限（#21）。拒絕不阻斷啟動。 */
    private fun ensurePostNotificationsPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        runCatching {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIFICATIONS)
        }
    }

    companion object {
        private const val REQ_POST_NOTIFICATIONS = 8621
        /** Session log 關鍵行（跳轉／高亮） */
        private val KEY_LOG_PATTERNS = listOf(
            "FATAL:",
            "WINE_LAUNCH_STAMP",
            "NTDLL_ANON_PE",
            "HOLEFIT",
            "PREFIX_EXEC",
            "noexec",
            "map_image",
            "stack overflow",
            "WINEBOOT_INIT",
            "WINE_VERSION_SMOKE",
            "WINE_PE_SMOKE",
            "WINE_PRERESERVE",
            "TIMEOUT",
            "still running",
            "prereserve",
            "LdrInitializeThunk",
            "KiUserExceptionDispatcher",
            "Emit Signal 11",
            "c0000018",
            "shared user data",
            "granularity_mask",
            "set_mem_area_state",
            "Illegal instruction",
            "SIGILL",
            "SIGABRT",
            "SIGSEGV",
            "BOX64_SELFTEST",
            "BOX64_FLAGS",
            "BOX64_X64LIB_STAMP",
            "BOX64_WINE64K_STAMP",
            "NTDLL=",
            "WINE_EXIT=",
            "WINE_START",
            "WINELOADER=",
            "WINEPRELOADRESERVE",
            "could not load ntdll",
            "Cannot dlopen",
            "Missing operand",
        )

        const val EXTRA_WORKSPACE_ID = "workspace_id"
        const val EXTRA_FORCE_SMOKE = "force_smoke"
        const val EXTRA_FORCE_GUEST_SMOKE = "force_guest_smoke"
        const val EXTRA_PREFER_ANDROID_GUEST = "prefer_android_guest"
        const val EXTRA_FORCE_PE_SMOKE = "force_pe_smoke"
    }
}
