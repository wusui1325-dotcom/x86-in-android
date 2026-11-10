package com.wusper.x86.session

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
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
import androidx.core.content.FileProvider
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.pointerInteropFilter
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.wusper.x86.WorkspaceStore
import com.wusper.x86.BuildConfig
import com.wusper.x86.engine.ComponentInstaller
import com.wusper.x86.engine.ExitStatusMessages
import com.wusper.x86.engine.GpuStack
import com.winlator.xserver.DesktopHelper
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
    @OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
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
        val dxwrapperExtra = intent.getStringExtra(EXTRA_DXWRAPPER)
        val ws = WorkspaceStore.listWorkspaces(this).firstOrNull { it.id == workspaceId }

        setContent {
            X86Theme {
                var logText by remember { mutableStateOf("準備啟動…\n") }
                val logListState = rememberLazyListState()
                var userScrolledLog by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                var logHighlightEpoch by remember { mutableIntStateOf(0) }
                var logExpanded by remember { mutableStateOf(false) }
                // 0.1.106: user hide must win — never let !running force-open log over Present
                var logForceHidden by remember { mutableStateOf(false) }
                var inputStyleDirect by remember { mutableStateOf(true) }
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
                fun exportFullLog() {
                    try {
                        val dataDir = ws?.dataDir
                        val stamp = Regex("""WINE_LAUNCH_STAMP=([0-9.]+)""")
                            .find(logText)?.groupValues?.getOrNull(1) ?: BuildConfig.VERSION_NAME
                        val tip = BuildConfig.GIT_SHA.take(7)
                        val ts = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                            .format(java.util.Date())
                        val name = "x86-log-${stamp}-${tip}-${ts}.txt"
                        val outDir = File(cacheDir, "log-export").also { it.mkdirs() }
                        val out = File(outDir, name)
                        val hdr = buildString {
                            appendLine("=== x86-in-android full session log ===")
                            appendLine("versionName=${BuildConfig.VERSION_NAME} versionCode=${BuildConfig.VERSION_CODE}")
                            appendLine("GIT_SHA=${BuildConfig.GIT_SHA}")
                            appendLine("WINE_LAUNCH_STAMP=$stamp")
                            appendLine("workspace=${ws?.id ?: "-"} display=${ws?.displayName ?: "-"}")
                            appendLine("exportedAt=$ts (device local)")
                            ws?.let { w ->
                                runCatching {
                                    appendLine(com.wusper.x86.WorkspaceApps.stampLine(w))
                                }
                            }
                            appendLine()
                        }
                        out.outputStream().buffered().use { os ->
                            os.write(hdr.toByteArray())
                            fun appendFile(label: String, f: File?) {
                                os.write("\n===== $label =====\n".toByteArray())
                                if (f != null && f.exists()) {
                                    f.inputStream().use { it.copyTo(os) }
                                    os.write("\n".toByteArray())
                                } else {
                                    os.write("(missing)\n".toByteArray())
                                }
                            }
                            os.write("\n===== Session logText (KEY + gpu-stack merge) =====\n".toByteArray())
                            os.write(logText.toByteArray())
                            os.write("\n".toByteArray())
                            // 0.1.91 K0a: nativeExec writes ws.dir/logs/session.log (not dataDir/)
                            appendFile("session.log (logs/)", ws?.dir?.let { File(File(it, "logs"), "session.log") })
                            appendFile("session.log (dataDir legacy)", dataDir?.let { File(it, "session.log") })
                            // 0.1.91 K0b: durable APP_LOAD stamps (survive gpu-stack truncate)
                            appendFile("app-load.stamp", dataDir?.let { File(it, "app-load.stamp") })
                            appendFile("gpu-stack.log", dataDir?.let { File(it, "gpu-stack.log") })
                            appendFile("wine-run.out", dataDir?.let { File(it, "wine-run.out") })
                            appendFile("wine-version.out (WINE_PE_SMOKE)", dataDir?.let { File(it, "wine-version.out") })
                            appendFile("wineboot-init.out", dataDir?.let { File(it, "wineboot-init.out") })
                            appendFile("box64-selftest.out", dataDir?.let { File(it, "box64-selftest.out") })
                            appendFile("wine-prereserve-probe.out", dataDir?.let { File(it, "wine-prereserve-probe.out") })
                            appendFile("ws-apps.json", dataDir?.let { File(it, "ws-apps.json") })
                            appendFile("ws-apps.out", dataDir?.let { File(it, "ws-apps.out") })
                            os.write("\n===== surface-shot (P1-T, files stay in logs/) =====\n".toByteArray())
                            val shotDir = ws?.dir?.let { File(it, "logs") }
                            val shots = shotDir?.listFiles { f ->
                                f.isFile && f.name.startsWith("surface-shot") && f.name.endsWith(".png")
                            }
                            if (shots.isNullOrEmpty()) {
                                os.write("(none)\n".toByteArray())
                            } else {
                                shots.sortedBy { it.name }.forEach { f ->
                                    os.write("path=${f.absolutePath} size=${f.length()}\n".toByteArray())
                                }
                            }
                        }
                        val uri = FileProvider.getUriForFile(
                            this@SessionActivity,
                            "${packageName}.fileprovider",
                            out,
                        )
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, name)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            clipData = ClipData.newUri(contentResolver, name, uri)
                        }
                        startActivity(Intent.createChooser(send, getString(com.wusper.x86.R.string.btn_export_log)))
                        Toast.makeText(
                            this@SessionActivity,
                            getString(com.wusper.x86.R.string.toast_log_exported),
                            Toast.LENGTH_SHORT,
                        ).show()
                    } catch (t: Throwable) {
                        android.util.Log.e("SessionActivity", "exportFullLog", t)
                        Toast.makeText(
                            this@SessionActivity,
                            getString(com.wusper.x86.R.string.toast_log_export_fail) + ": ${t.message}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
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
                    userScrolledLog = true
                    scope.launch {
                        logListState.animateScrollToItem(idx.coerceIn(0, (lines.size - 1).coerceAtLeast(0)))
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
                var guestPresented by remember { mutableStateOf(false) }
                var presentDiagLines by remember { mutableIntStateOf(0) }
                var lastPresentDiag by remember { mutableStateOf("") }
                // 0.1.107: 藍屏人話錯誤 — 依失敗原因鎖住；禁止 salt 每秒輪換；永不空白
                var blueScreenLines by remember { mutableStateOf<List<String>>(emptyList()) }
                var blueScreenReasonKey by remember { mutableStateOf("") }
                var statusTick by remember { mutableIntStateOf(0) }
                var autoCollapsedOnWineStart by remember { mutableStateOf(false) }
                // 0.1.64: sticky UI lines survive session.log overwrite every 400ms
                var uiStickyLog by remember { mutableStateOf("") }
                fun appendSticky(line: String) {
                    val l = if (line.endsWith("\n")) line else "$line\n"
                    uiStickyLog += l
                    logText += l
                }
                val surfaceHolderRef = remember { AtomicReference<SurfaceHolder?>(null) }
                fun upsertStickyLastDiag(stats: String) {
                    val l = "PRESENT_DIAG_LAST $stats\n"
                    val re = Regex("(?m)^PRESENT_DIAG_LAST .*\n?")
                    uiStickyLog = if (uiStickyLog.contains("PRESENT_DIAG_LAST ")) {
                        uiStickyLog.replace(re, l)
                    } else {
                        uiStickyLog + l
                    }
                    logText = if (logText.contains("PRESENT_DIAG_LAST ")) {
                        logText.replace(re, l)
                    } else {
                        logText + l
                    }
                }
                var surfaceTestBusy by remember { mutableStateOf(false) }
                fun captureSurfaceShot(): Triple<File, Int, Int>? {
                    val holder = surfaceHolderRef.get() ?: return null
                    val surface = holder.surface ?: return null
                    if (!surface.isValid) return null
                    val frame = holder.surfaceFrame
                    val w = frame.width().coerceAtLeast(1)
                    val h = frame.height().coerceAtLeast(1)
                    val bmp = android.graphics.Bitmap.createBitmap(
                        w, h, android.graphics.Bitmap.Config.ARGB_8888,
                    )
                    val latch = java.util.concurrent.CountDownLatch(1)
                    val rcBox = intArrayOf(-1)
                    try {
                        android.view.PixelCopy.request(
                            surface,
                            bmp,
                            { rc ->
                                rcBox[0] = rc
                                latch.countDown()
                            },
                            android.os.Handler(android.os.Looper.getMainLooper()),
                        )
                    } catch (t: Throwable) {
                        bmp.recycle()
                        android.util.Log.e("SessionActivity", "PixelCopy.request", t)
                        return null
                    }
                    if (!latch.await(3, java.util.concurrent.TimeUnit.SECONDS)) {
                        bmp.recycle()
                        return null
                    }
                    if (rcBox[0] != android.view.PixelCopy.SUCCESS) {
                        bmp.recycle()
                        return null
                    }
                    val ts = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                        .format(java.util.Date())
                    val logsDir = (ws?.dir?.let { File(it, "logs") } ?: File(filesDir, "logs"))
                        .also { it.mkdirs() }
                    val out = File(logsDir, "surface-shot-$ts.png")
                    out.outputStream().use { os ->
                        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, os)
                    }
                    try {
                        val exp = File(cacheDir, "log-export").also { it.mkdirs() }
                        out.copyTo(File(exp, out.name), overwrite = true)
                    } catch (_: Throwable) {
                    }
                    bmp.recycle()
                    return Triple(out, w, h)
                }
                fun runSurfaceTest() {
                    if (surfaceTestBusy) return
                    surfaceTestBusy = true
                    scope.launch {
                        val t0 = android.os.SystemClock.uptimeMillis()
                        var shotPath = "-"
                        var shotSize = 0L
                        try {
                            val pump = inProcessX?.framePump
                            if (pump == null || !NativeEngine.nativePresentReady()) {
                                appendSticky("SURFACE_SHOT path=- size=0 fail=no_surface")
                                appendSticky("SURFACE_TEST show→hide dt=0 path=- size=0 fail=no_surface")
                                return@launch
                            }
                            pump.showSurfaceTest(3000L)
                            delay(280)
                            val shot = withContext(Dispatchers.IO) { captureSurfaceShot() }
                            if (shot != null) {
                                val (f, w, h) = shot
                                shotPath = f.absolutePath
                                shotSize = f.length()
                                appendSticky("SURFACE_SHOT path=$shotPath size=$shotSize ${w}x${h} during=test_pattern")
                            } else {
                                appendSticky("SURFACE_SHOT path=- size=0 fail=pixelcopy")
                            }
                            val remain = 3000L - (android.os.SystemClock.uptimeMillis() - t0)
                            if (remain > 0) delay(remain)
                            delay(40)
                            val dt = android.os.SystemClock.uptimeMillis() - t0
                            appendSticky("SURFACE_TEST show→hide dt=$dt path=$shotPath size=$shotSize")
                        } catch (t: Throwable) {
                            android.util.Log.e("SessionActivity", "runSurfaceTest", t)
                            val dt = android.os.SystemClock.uptimeMillis() - t0
                            appendSticky("SURFACE_TEST show→hide dt=$dt path=$shotPath size=$shotSize fail=${t.message}")
                        } finally {
                            surfaceTestBusy = false
                        }
                    }
                }
                val inputBridgeRef = remember { AtomicReference<X11InputBridge?>(null) }
                val inProcessInputRef = remember { AtomicReference<InProcessXInput?>(null) }

                var sessionEpoch by remember { mutableIntStateOf(0) }
                var showErrorCard by remember { mutableStateOf(false) }
                // Follow new log lines unless user scrolled away
                LaunchedEffect(logText, logExpanded, showErrorCard) {
                    val showPanel = logExpanded || showErrorCard
                    if (!showPanel) return@LaunchedEffect
                    if (userScrolledLog) return@LaunchedEffect
                    val last = (logText.count { it == '\n' }).coerceAtLeast(0)
                    runCatching { logListState.scrollToItem(last) }
                }
                // 0.1.106: once WINE_START, default-collapse log so Present Surface is visible
                LaunchedEffect(logText) {
                    if (!autoCollapsedOnWineStart && "WINE_START" in logText && !showErrorCard) {
                        autoCollapsedOnWineStart = true
                        logExpanded = false
                        // do not set logForceHidden — user can still expand; wineInit no longer forces
                    }
                }
                LaunchedEffect(logListState.isScrollInProgress) {
                    if (logListState.isScrollInProgress) {
                        val info = logListState.layoutInfo
                        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                        val total = info.totalItemsCount
                        if (total > 0 && lastVisible < total - 3) {
                            userScrolledLog = true
                        }
                    }
                }
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

                LaunchedEffect(workspaceId, forceSmoke, forceGuestSmoke, preferAndroidGuest, forcePeSmoke, dxwrapperExtra, sessionEpoch) {
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
                            dxwrapper = dxwrapperExtra,
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
                        guestPresented = false
                        // 0.1.107: never leave BSOD body empty — seed locked GENERIC
                        run {
                            val seed = BlueScreenMessages.pick("", maxLines = 2)
                            blueScreenLines = seed.lines
                            blueScreenReasonKey = BlueScreenMessages.reasonFingerprint("")
                        }
                        statusTick = 0
                        presentDiagLines = 0
                        lastPresentDiag = ""
                        uiStickyLog = ""
                        appendSticky("啟動 Winlator Java X → Present…")
                        // 0.1.75: clear+set desktop ready marker before XServer (DesktopHelper.attachTo)
                        val readyMarker = java.io.File(ws.dataDir, ".x86-x11-ready")
                        DesktopHelper.setReadyMarker(readyMarker)
                        DesktopHelper.stopNoHwndPoller()
                        DesktopHelper.clearReadyMarker()
                        val x = InProcessX(this@SessionActivity)
                        NativeEngine.nativePresentCreate(1280, 720)
                        x.start()
                        inProcessX = x
                        val audio = InProcessAudio(this@SessionActivity)
                        audio.start()
                        inProcessAudio = audio
                        appendSticky("音訊：ALSA→AAudio socket=${audio.socketPath()}")
                        val xs = x.xServer
                        if (xs != null) {
                            val xin = InProcessXInput(xs, style = InProcessXInput.Style.TOUCHSCREEN)
                            xin.onStamp = { line -> appendSticky(line) }
                            val holder = surfaceHolderRef.get()
                            val sw = holder?.surfaceFrame?.width() ?: 0
                            val sh = holder?.surfaceFrame?.height() ?: 0
                            if (sw > 0 && sh > 0) xin.updateBlitFromSurface(sw, sh)
                            else xin.updateBlitFromSurface(1280, 720)
                            inProcessInputRef.set(xin)
                            x.getFramePump()?.setCursorOverlayEnabled(false)
                            appendSticky("輸入橋：預設直接觸控（按哪點哪／拖=滾輪／長按=右鍵；頂欄可切觸控板）")
                            appendSticky("INPUT_STYLE style=direct cursor=off")
                        }
                        appendSticky("InProcessX imagefs=${x.imageFsRoot.absolutePath}")
                        ws?.let { w -> runCatching { appendSticky(com.wusper.x86.WorkspaceApps.stampLine(w)) } }
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
                        // 0.1.64: do NOT create PresentSurfaceBridge here — it is unused
                        // (never start()) and shutdown() used to detach the live Surface.
                        presentBridge?.shutdown()
                        presentBridge = null
                        frameBridge?.shutdown()
                        val attachRc = NativeEngine.nativePresentAttachSurface(surf)
                        appendSticky(
                            "Present：進程內 X Drawable → AHB → Surface（已 attach） rc=$attachRc ready=${NativeEngine.nativePresentReady()}",
                        )
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
                            val filePart = if (gpuLog != null && gpuLog.exists()) {
                                session + "\n--- gpu-stack.log ---\n" + gpuLog.readText()
                            } else {
                                session
                            }
                            // Keep Present/UI sticky lines at top so export + on-screen keep them
                            logText = if (uiStickyLog.isNotBlank()) {
                                uiStickyLog + "\n--- session.log ---\n" + filePart
                            } else {
                                filePart
                            }
                            // Prefer wineboot progress over generic 「執行中」/Present wait
                            val sawWineStart = "WINE_START" in logText
                            val wbHb = Regex("""WINEBOOT_INIT hb t=(\d+)s.*?system\.reg=(\d+)B""")
                                .findAll(logText).lastOrNull()
                            val softCont = Regex("""WINEBOOT_INIT soft-continue t=(\d+)s""")
                                .findAll(logText).lastOrNull()
                            val rpcBusy = "RPCSS_PRESTART" in logText && "WINE_START" !in logText
                            // 0.1.105 UX: ≥100 dynamic status lines (zh-TW), rotate by tick+phase
                            statusTick += 1
                            val hb = Regex("""WINE_HB t=(\d+)s pid=(\d+)""").findAll(logText).lastOrNull()
                            // 0.1.115 P0-J: RUNNING/WAIT_PRESENT metric = hb or empty — never status body
                            val metric = when {
                                !sawWineStart && rpcBusy -> {
                                    if ("RpcSs ready" in logText) "RpcSs ready" else "RPCSS"
                                }
                                !sawWineStart && (wbHb != null || "WINEBOOT_INIT begin" in logText) -> {
                                    val tSec = softCont?.groupValues?.getOrNull(1)
                                        ?: wbHb?.groupValues?.getOrNull(1)
                                        ?: "?"
                                    val regB = wbHb?.groupValues?.getOrNull(2) ?: "?"
                                    "t=${tSec}s reg=${regB}B"
                                }
                                hb != null -> "pid=${hb.groupValues[2]} t=${hb.groupValues[1]}s"
                                // 0.1.115 P0-J: never derive metric from status (Present nest spam)
                                else -> ""
                            }
                            val hint = when {
                                !sawWineStart && rpcBusy -> "正在初始化 RpcSs…"
                                !sawWineStart && (wbHb != null || "WINEBOOT_INIT begin" in logText) ->
                                    "正在初始化 Wine…"
                                hb != null || status.startsWith("執行中") -> "執行中"
                                else -> status
                            }
                            status = LaunchStatusLines.pick(
                                logText = logText,
                                statusHint = hint,
                                tick = statusTick,
                                metric = metric,
                            )
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
                // 0.1.63: PRESENT_DIAG every 2s while running; clear overlay on first present
                LaunchedEffect(inProcessX, status) {
                    val x = inProcessX ?: return@LaunchedEffect
                    while (true) {
                        delay(2000)
                        // 0.1.106: engine liveness from log/pid — not LaunchStatusLines prefix
                        val runningNow = "WINE_START" in logText || "WINEBOOT_INIT" in logText ||
                            status.startsWith("Present") || guestPresented
                        if (!runningNow) break
                        val stats = try { x.pathStats() } catch (_: Throwable) { "pathStats-error" }
                        val line = "PRESENT_DIAG $stats"
                        // 0.1.116 P1-S: keep first 14 unique DIAG lines AND always
                        // upsert PRESENT_DIAG_LAST with the latest full pathStats.
                        if (line != lastPresentDiag && presentDiagLines < 14) {
                            lastPresentDiag = line
                            presentDiagLines++
                            appendSticky(line)
                        } else if (line != lastPresentDiag) {
                            lastPresentDiag = line
                        }
                        upsertStickyLastDiag(stats)
                        // 0.1.64: ready=true means window_ attached; only then honor presentOk
                        // 0.1.90: do NOT clear overlay on explorer desktopOnly (masks missing-Loaded)
                        // 0.1.93: require nonBlackGuest>0 OR strict Loaded fingerprint
                        // (092 cleared empty blue desk when desktopOnly flipped 0 without app)
                        val ready = "ready=true" in stats
                        val deskOnly = Regex("""\bdesktopOnly=(\d+)""").find(stats)
                            ?.groupValues?.getOrNull(1) == "1"
                        val presented = try { x.hasPresented() } catch (_: Throwable) { false }
                        // 0.1.107 UX: 藍屏依失敗原因鎖住 — 刪 blueScreenSalt++；永不 blank
                        if (!guestPresented && (deskOnly || "create_fail=" in logText || "loaded_app=0" in logText || blueScreenLines.isEmpty())) {
                            val key = BlueScreenMessages.reasonFingerprint(logText)
                            if (key != blueScreenReasonKey || blueScreenLines.isEmpty()) {
                                val pick = BlueScreenMessages.pick(logText, maxLines = 3)
                                blueScreenLines = pick.lines
                                blueScreenReasonKey = key
                            }
                        }
                        // 0.1.115 P0-M Dig114: guestPresented requires hwnd≠0 (ban bare mappedKids);
                        // exclude rundll32 — present_black clear only on live App hwnd.
                        // Dig111/112 anti-FP kept (no latch+Loaded alone / no desktopOnly=0 FP).
                        val mappedKids = Regex("""\bmappedKids=(\d+)""").find(stats)
                            ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                        val hwndTok = Regex("""\bhwnd=(0x[0-9a-fA-F]+)""").find(stats)
                            ?.groupValues?.getOrNull(1) ?: "0x0"
                        val hwndOk = hwndTok != "0x0" && hwndTok != "0x"
                        val guestSample = Regex("""\bguestSample=(\w+)""").find(stats)
                            ?.groupValues?.getOrNull(1) ?: "none"
                        val wmClass = Regex("""\bwmClass=([^\s]+)""").find(stats)
                            ?.groupValues?.getOrNull(1) ?: "-"
                        val helperClass = wmClass.contains("rundll32", ignoreCase = true)
                            || wmClass.contains("wineboot", ignoreCase = true)
                            || wmClass.contains("services", ignoreCase = true)
                        val loadedHint =
                            ("APP_LOAD fingerprint seen" in logText) ||
                                (
                                    ("Loaded L\"" in logText) &&
                                        (
                                            ("App\\app.exe" in logText) ||
                                                ("App/app.exe" in logText) ||
                                                Regex("""Loaded L".*[\\/]App[\\/]app\.exe""")
                                                    .containsMatchIn(logText)
                                            )
                                    )
                        // Sticky desktopOnly=1 after Loaded+App → FAIL present_black (keep BSOD)
                        if (!guestPresented && ready && deskOnly && loadedHint) {
                            if ("present_black=1" !in uiStickyLog) {
                                appendSticky(
                                    "PRESENT_DIAG present_black=1 desktopOnly=1 mappedKids=$mappedKids " +
                                        "hwnd=$hwndTok loadedHint=1 sticky_after_Loaded (0.1.112 Dig111)"
                                )
                            }
                        }
                        if (!guestPresented && ready && !deskOnly) {
                            val okMatch = Regex("""\bok=(\d+)""").find(stats)
                            val ok = okMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                            val nbg = Regex("""\bnonBlackGuest=(\d+)""").find(stats)
                                ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                            // 0.1.115 P0-M: hwnd≠0 required; ban mappedKids-only + helpers
                            val liveApp = hwndOk && !helperClass
                            if ((presented || ok > 0) && loadedHint && liveApp) {
                                guestPresented = true
                                appendSticky(
                                    "PRESENT_DIAG guestPresented=true ok=$ok nonBlackGuest=$nbg " +
                                        "loadedHint=1 desktopOnly=0 mappedKids=$mappedKids hwnd=$hwndTok " +
                                        "wmClass=$wmClass guestSample=$guestSample " +
                                        "overlay_keep=live_hwnd_required (0.1.115)"
                                )
                                // 0.1.113 P1-D: clear sticky present_black ONLY on live App sample
                                if ("present_black=1" in uiStickyLog && "present_black=0" !in uiStickyLog) {
                                    appendSticky(
                                        "PRESENT_DIAG present_black=0 cleared live_app " +
                                            "desktopOnly=0 mappedKids=$mappedKids hwnd=$hwndTok (0.1.115)"
                                    )
                                }
                            } else if ((presented || ok > 0) && loadedHint && !liveApp) {
                                if ("overlay_keep=Loaded_without_live_app" !in uiStickyLog) {
                                    appendSticky(
                                        "PRESENT_DIAG overlay_keep=Loaded_without_live_app " +
                                            "desktopOnly=0 mappedKids=$mappedKids hwnd=$hwndTok " +
                                            "loadedHint=1 (0.1.112 forbid latch FP)"
                                    )
                                }
                            } else if (nbg > 0 && !loadedHint) {
                                if ("overlay_keep=nonBlackGuest_without_Loaded" !in uiStickyLog) {
                                    appendSticky(
                                        "PRESENT_DIAG overlay_keep=nonBlackGuest_without_Loaded " +
                                            "nonBlackGuest=$nbg loadedHint=0 (0.1.107; dig106 false clear)"
                                    )
                                }
                            }
                        }
                    }
                }

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
                                TextButton(
                                    onClick = {
                                        val next = !inputStyleDirect
                                        inputStyleDirect = next
                                        val xin = inProcessInputRef.get()
                                        if (xin != null) {
                                            xin.style = if (next) {
                                                InProcessXInput.Style.TOUCHSCREEN
                                            } else {
                                                InProcessXInput.Style.TOUCHPAD
                                            }
                                        }
                                        inProcessX?.getFramePump()?.setCursorOverlayEnabled(!next)
                                        appendSticky(
                                            if (next) "INPUT_STYLE style=direct"
                                            else "INPUT_STYLE style=pad cursor=on",
                                        )
                                    },
                                ) {
                                    Text(
                                        getString(
                                            if (inputStyleDirect) com.wusper.x86.R.string.btn_input_direct
                                            else com.wusper.x86.R.string.btn_input_pad,
                                        ),
                                    )
                                }
                                TextButton(
                                    onClick = { runSurfaceTest() },
                                    enabled = !surfaceTestBusy,
                                ) {
                                    Text(getString(com.wusper.x86.R.string.btn_surface_test))
                                }
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
                                // 0.1.105 UX: classic Windows BSOD blue (#0000AA), not Win10 teal
                                .background(Color(0xFF0000AA)),
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
                                update = { view ->
                                    // P1-X: rebind letterbox on size change (e.g. log collapse)
                                    if (view.width > 0 && view.height > 0) {
                                        inProcessInputRef.get()?.updateBlitFromSurface(view.width, view.height)
                                        inputBridgeRef.get()?.blitRect =
                                            com.wusper.x86.graphics.DesktopLayout.fitRect(
                                                1280, 720, view.width, view.height,
                                            )
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInteropFilter { ev ->
                                        inProcessInputRef.get()?.onTouch(ev)
                                            ?: inputBridgeRef.get()?.onTouch(ev)
                                            ?: false
                                    },
                            )
                            // 0.1.106: wine-init from log stamps (status lines no longer start with 正在初始化/RpcSs)
                            val wineInitPhase = (("WINEBOOT_INIT" in logText || "RPCSS_PRESTART" in logText) &&
                                "WINE_START" !in logText)
                            // 0.1.63: clear 「等待客體」once Present posts (or stub-fallback)
                            val sessionLiveHint = "WINE_START" in logText || wineInitPhase || guestPresented
                            val showSurfaceHint = !showErrorCard && !guestPresented && sessionLiveHint
                            if (showSurfaceHint) {
                                // 0.1.107: never blank BSOD — fallback locked GENERIC if empty
                                val bodyLines = blueScreenLines
                                    .filter { it != BlueScreenMessages.FOOTER }
                                    .ifEmpty {
                                        BlueScreenMessages.pick(logText, maxLines = 2).lines
                                            .filter { it != BlueScreenMessages.FOOTER }
                                    }
                                Column(
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .padding(24.dp)
                                        .fillMaxWidth(0.92f),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        text = if (wineInitPhase) {
                                            getString(com.wusper.x86.R.string.session_wine_init)
                                        } else {
                                            "啟動說明：（"
                                        },
                                        color = Color(0xFFFFFFFF),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    if (!wineInitPhase) {
                                        Spacer(Modifier.height(10.dp))
                                        bodyLines.forEach { line ->
                                            Text(
                                                text = line,
                                                color = Color(0xFFFFFFFF),
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontFamily = FontFamily.Monospace,
                                                ),
                                                modifier = Modifier.padding(vertical = 2.dp),
                                            )
                                        }
                                    }
                                    // Fixed BSOD footer (always last on blue screen)
                                    Spacer(Modifier.height(16.dp))
                                    Text(
                                        text = BlueScreenMessages.FOOTER,
                                        color = Color(0xFFE0E0FF),
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                        ),
                                    )
                                }
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
                        // 0.1.106 P0 UX: running = engine/log liveness — NOT status.startsWith("執行中")
                        // Dig 105: LaunchStatusLines rewrote status → !running forced fullscreen log forever.
                        val running = "WINE_START" in logText || guestPresented ||
                            (("WINEBOOT_INIT" in logText || "RPCSS_PRESTART" in logText) && "WINE_EXIT" !in logText)
                        val wineInitPhasePanel = (("WINEBOOT_INIT" in logText || "RPCSS_PRESTART" in logText) &&
                            "WINE_START" !in logText)
                        // User hide wins; default collapse once WINE_START so Present Surface visible.
                        // NEVER force-open via !running.
                        val showLogPanel = when {
                            showErrorCard || installing -> true
                            logForceHidden -> false
                            logExpanded -> true
                            wineInitPhasePanel -> true
                            else -> false
                        }
                        val showLogPanelEffective = showLogPanel
                        val logLines = remember(logText, logHighlightEpoch) { logText.split('\n') }
                        val keyLower = remember { KEY_LOG_PATTERNS.map { it.lowercase() } }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    if (showLogPanel) {
                                        // 0.1.106: hide must stick even during wineInitPhasePanel
                                        logForceHidden = true
                                        logExpanded = false
                                    } else {
                                        logForceHidden = false
                                        logExpanded = true
                                    }
                                },
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
                                onClick = { exportFullLog() },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(getString(com.wusper.x86.R.string.btn_export_log))
                            }
                            OutlinedButton(
                                onClick = {
                                    logForceHidden = false
                                    logExpanded = true
                                    jumpToKeyLog()
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(getString(com.wusper.x86.R.string.btn_jump_key))
                            }
                        }
                        if (running && !showLogPanelEffective && !wineInitPhasePanel && !guestPresented) {
                            Text(
                                text = getString(com.wusper.x86.R.string.session_waiting_surface),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        } else if (wineInitPhasePanel && !showLogPanel) {
                            Text(
                                text = status.ifBlank { getString(com.wusper.x86.R.string.session_wine_init) },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                        if (showLogPanelEffective) {
                            // weight MUST be on Column child (not inside SelectionContainer) or scroll breaks
                            SelectionContainer(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(if (running && !showErrorCard && !wineInitPhasePanel) 0.35f else 0.55f)
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                            ) {
                                LazyColumn(
                                    state = logListState,
                                    modifier = Modifier.fillMaxSize(),
                                ) {
                                    itemsIndexed(logLines, key = { idx, _ -> idx }) { _, line ->
                                        val hit = keyLower.any { it in line.lowercase() }
                                        Text(
                                            text = line.ifEmpty { " " },
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = if (hit) FontWeight.SemiBold else FontWeight.Normal,
                                            ),
                                            color = if (hit) {
                                                Color(0xFF1A1A1A)
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            modifier = if (hit) {
                                                Modifier
                                                    .fillMaxWidth()
                                                    .background(Color(0x66FFEB3B))
                                                    .padding(horizontal = 2.dp)
                                            } else {
                                                Modifier.fillMaxWidth()
                                            },
                                        )
                                    }
                                }
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
            "WINEBOOT_WIPE",
            "WINEBOOT_UPDATE",
            "reuse large",
            "wipe stub",
            "soft-ok",
            "WINE_VERSION_SMOKE",
            "WINE_PE_SMOKE",
            "soft-pass",
            "soft-continue",
            "WINE_RUN",
            "WINE_HB",
            "DISPLAY_DIAG",
            "DRIVER_DIAG",
            "RPCSS_DIAG",
            "RPCSS_PRESTART",
            "WINE_PRESTART",
            "Failed to open RpcSs",
            "MountMgr",
            "winex11",
            "nodrv",
            "graphics driver",
            "prefix complete",
            "prefix incomplete",
            "cached+binary",
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
            "PRESENT_DIAG",
            "PRESENT_DIAG_LAST",
            "SURFACE_SHOT",
            "SURFACE_TEST",
            "INPUT_TOUCH",
            "INPUT_STYLE",
            "FONT_CJK",
            "FONT_SMOOTH",
            "THEME_LIGHT",
            "WS_APPS",
            "WS_LAUNCH",
            "COMCTL32",
            "SXS_COMCTL",
            "NTDLL_VA_RESERVE",
            "VA_RESERVE",
            "x11_render",
            "guestPresented",
            "stub-fallback",
            "APP_LOAD",
            "APP_RELAUNCH",
            "APP_SEED",
            "APP_PE",
            "APP_LOAD_FATAL",
            "Loaded L\"",
            "ANativeWindow_lock",
            "lockFail",
            "PRESENT path",
            "cpu-first",
            "ahb_skip",
            "nonBlack",
            "start /unix",
            "desktopOnly",
            "mappedKids",
            "APP_LOAD retry",
            "APP_PE WARN",
            "DXVK_DIAG",
            "DXVK_HUD",
            "guestBlack",
            "guestBlack=0 recovered",
            "guestSample",
            "nonBlackGuest",
            "ahb_gpu",
            "forceGpu",
            "d3d11.dll",
            "freedreno",
            "TURNIP_ICD",
            "TURNIP_PROBE",
            "TURNIP_DLOPEN",
            "TURNIP_ICD library_path",
            "dlloverrides",
            "KGSL=",
            "VK_LOADER_DEBUG",
            "Adapter",
            "vkCreate",
            "DXVK:",
            "err:vulkan",
            "wrapper=wined3d",
            "DXVK_CONFIG",
            "deferSurface",
            "maxDeviceMemory",
            "VideoMemorySize",
            "GrabFullscreen",
            "presentknobs",
            "BISECT_HINT",
            "WINEESYNC",
            "TURNIP_QUEUE",
            "MESA_SHADER",
            "noubwc",
            "sysmem",
            "mesa_cache",
            "dma_heap",
            "UseXRandR",
            "UseXVidMode",
            "ScreenDepth",
            "Decorated",
            "DXGrab",
            "hwnd=0x",
            "enableDialogMode",
            "allowFse",
            "TURNIP_GMEM",
            "TURNIP_GMEM=auto",
            "TURNIP_GMEM=on",
            "TURNIP_GMEM=off",
            "decision=gmem",
            "decision=sysmem",
            "X86_ADRENO_MODEL",
            "X86_TU_GMEM",
            "MAP_WAIT",
            "MAP_RAISE",
            "mapState=",
            "mapWait=",
            "pendingHwnd=",
            "maxFrameLatency",
            "numBackBuffers",
            ".x86-x11-ready",
            "APP_HWND_WAIT",
            "Loaded-but-no-HWND",
            "NOHWND_MAP",
            "nohwnd_scan",
            "nohwnd_req",
            "desk-steal-block",
            "AppActivate",
            "subsystem=",
            "APP_CUI_WRAP",
            "APP_CREATEWINDOW_MISS",
            "APP_CRT_SEED",
            "APP_CRT_RELAUNCH",
            "APP_CRT_SIG",
            "APP_D3DCOMPILER_SEED",
            "d3dcompiler_",
            "APP_VULKAN_SEED",
            "vulkan-1",
            "winevulkan",
            "APP_WOW64_SEED",
            "wow64=1",
            "wow64cpu",
            "decide=start_win",
            "decide=direct_win",
            "wow64_start",
            "wow64_direct",
            "wow64_explorer",
            "APP_SEED visible",
            "fail_hr=",
            "ladder advance",
            "APP_DOSDEVICES",
            "APP_SEED gate",
            "APP_LOAD_STAMP",
            "WINE_RUN_OUT",
            "RPCSS_PRESTART done",
            "RPCSS_PRESTART heartbeat",
            "RPCSS_PRESTART soft-continue",
            "app-load.stamp",
            "# STAMP",
            "APP_MSGBOX_DETECT",
            "+msgbox",
            "#32770",
            "title_nudge",
            "syncInterval",
            "presentInterval",
            "X86_DXGI_SYNC",
            "PresentExtension GPUImage",
            "InProcessX GPUImage",
            "ahbCpuAccess",
            "ahbCpuAccess0",
            "cpuAccess=false",
            "wineconsole",
            "reason=",
            "path_seen",
            "strict Loaded",
            "bringToFront",
            "hud_sample_policy",
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
        /** 0.1.69: "wined3d" or "dxvk" — writes workspace dxwrapper + X86_DXWRAPPER */
        const val EXTRA_DXWRAPPER = "dxwrapper"
    }
}
