package com.wusper.x86.session

import android.os.Bundle
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.wusper.x86.WorkspaceStore
import com.wusper.x86.engine.ComponentInstaller
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
import android.view.MotionEvent
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
                var status by remember { mutableStateOf("初始化") }
                var logFile by remember { mutableStateOf<File?>(null) }
                var frameBridge by remember { mutableStateOf<SurfaceFrameBridge?>(null) }
                var presentBridge by remember { mutableStateOf<PresentSurfaceBridge?>(null) }
                var inProcessAudio by remember { mutableStateOf<InProcessAudio?>(null) }
                var inProcessX by remember { mutableStateOf<InProcessX?>(null) }
                val surfaceHolderRef = remember { AtomicReference<SurfaceHolder?>(null) }
                val inputBridgeRef = remember { AtomicReference<X11InputBridge?>(null) }
                val inProcessInputRef = remember { AtomicReference<InProcessXInput?>(null) }

                LaunchedEffect(workspaceId, forceSmoke, forceGuestSmoke, preferAndroidGuest, forcePeSmoke) {
                    if (ws == null) {
                        status = "錯誤"
                        logText += "找不到工作區：$workspaceId\n"
                        return@LaunchedEffect
                    }
                    EngineForegroundService.start(this@SessionActivity, ws.displayName)
                    var plan = LaunchPipeline.prepare(
                        ctx = this@SessionActivity,
                        ws = ws,
                        targetPath = ws.sourceExePath,
                        targetUri = ws.sourceExePath.takeIf { it.startsWith("content:") },
                        forceHostSmoke = forceSmoke,
                        forceGuestSmoke = forceGuestSmoke,
                        preferAndroidGuest = preferAndroidGuest,
                        forcePeSmoke = forcePeSmoke,
                    )
                    logFile = plan.logFile
                    logText += "目標：${plan.targetFile.absolutePath}\n"
                    logText += "類型：${plan.kindMessage}\n"
                    logText += "模式：${plan.mode}／圖形：${plan.graphicsBackend}\n"
                    if (plan.needsRuntimeInstall || (plan.mode == LaunchPipeline.Mode.WineProot && !ComponentInstaller.isReady(this@SessionActivity))) {
                        status = "安裝運行時"
                        logText += "首次 PE：下載 Wine／glibc rootfs／Box64…\n"
                        val install = withContext(Dispatchers.IO) {
                            ComponentInstaller.install(this@SessionActivity) { }
                        }
                        if (install.isFailure) {
                            status = "安裝失敗"
                            logText += "安裝失敗：${install.exceptionOrNull()?.message}\n"
                            EngineForegroundService.stop(this@SessionActivity)
                            return@LaunchedEffect
                        }
                        logText += "Wine 運行時就緒，重建計畫…\n"
                        plan = LaunchPipeline.buildWineProotPlan(
                            this@SessionActivity, ws, plan.targetFile, plan.logFile,
                        )
                        logText += "模式：${plan.mode}／${plan.graphicsBackend}\n${plan.kindMessage}\n"
                    }
                    // GpuStack：先備料 Turnip／DXVK（Present 橋未就緒也下）
                    if (plan.needsGpuStackInstall ||
                        (plan.graphicsBackend == GraphicsBackend.GpuStack && !GpuStack.componentsInstalled(this@SessionActivity))
                    ) {
                        status = "安裝 GPU 棧"
                        logText += "下載 Turnip＋DXVK（GpuStack 組件）…\n"
                        val gpu = withContext(Dispatchers.IO) {
                            GpuStack.installComponents(this@SessionActivity) { msg ->
                                // IO 執行緒；最終以 statusMessage 為準
                            }
                        }
                        if (gpu.isFailure) {
                            logText += "GPU 組件下載失敗：${gpu.exceptionOrNull()?.message}\n（可稍後重試）\n"
                        } else {
                            logText += "GPU 組件：${GpuStack.statusMessage(this@SessionActivity)}\n"
                            plan = LaunchPipeline.buildWineProotPlan(
                                this@SessionActivity, ws, plan.targetFile, plan.logFile,
                            )
                            logText += "重建：${plan.mode}\n${plan.kindMessage}\n"
                        }
                    }
                    // SoftLegacy：xdotool 檔案佇列；GpuStack：稍後接 InProcessX.inject*
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
                    // 進程內 X（Winlator）先於客體 Wine；輸入走 inject*
                    if (plan.mode == LaunchPipeline.Mode.WineProot &&
                        plan.graphicsBackend == GraphicsBackend.GpuStack
                    ) {
                        status = "啟動進程內 X"
                        logText += "啟動 Winlator Java X → Present…\n"
                        val x = InProcessX(this@SessionActivity)
                        NativeEngine.nativePresentCreate(1280, 720)
                        // null sink：PresentXFramePump → AHB／BGRA native（非 CPU RGBA 回呼）
                        x.start()
                        inProcessX = x
                        val audio = InProcessAudio(this@SessionActivity)
                        audio.start()
                        inProcessAudio = audio
                        logText += "音訊：ALSA→AAudio socket=${audio.socketPath()}\n"
                        val xs = x.xServer
                        if (xs != null) {
                            val xin = InProcessXInput(xs)
                            // 預設 letterbox；surfaceChanged 會更新
                            xin.updateBlitFromSurface(1280, 720)
                            inProcessInputRef.set(xin)
                            logText += "輸入橋：InProcessX.inject*（非 xdotool）\n"
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

                    // 等 Surface
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
                        // 可選：shm 旁路仍可用
                        val bridge = PresentSurfaceBridge(
                            dataDir = ws.dataDir,
                            surface = surf,
                            targetFps = 15,
                            allowXwdFeeder = false,
                            allowStub = false,
                        )
                        // 不 start 輪詢（幀由 PresentXFramePump 推）；僅保留物件以便 shutdown 路徑一致
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
                        // 仍可掛 Present stub 證明通路
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
                        // 不 return：若只有 pending 且已演示，仍結束服務輪詢
                        if (!result.ok) {
                            EngineForegroundService.stop(this@SessionActivity)
                            return@LaunchedEffect
                        }
                    }
                    while (true) {
                        delay(400)
                        val wait = NativeEngine.nativeWaitNonBlocking()
                        val lf = logFile
                        if (lf != null && lf.exists()) {
                            logText = lf.readText()
                        }
                        if (wait != 1) {
                            val pid = NativeEngine.nativeRunningPid()
                            if (pid <= 0) {
                                status = if (wait < 0) "已結束 ($wait)" else "已結束"
                                EngineForegroundService.stop(this@SessionActivity)
                                break
                            }
                        }
                    }
                }

                DisposableEffect(Unit) {
                    onDispose {
                        inputBridgeRef.get()?.shutdown()
                        inProcessInputRef.get()?.shutdown()
                        frameBridge?.shutdown()
                        presentBridge?.shutdown()
                        inProcessAudio?.stop()
                        inProcessX?.stop()
                        LaunchPipeline.stop()
                        EngineForegroundService.stop(this@SessionActivity)
                    }
                }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(ws?.displayName ?: getString(com.wusper.x86.R.string.session_title)) },
                            navigationIcon = {
                                IconButton(onClick = {
                                    inputBridgeRef.get()?.shutdown()
                                    inProcessInputRef.get()?.shutdown()
                                    frameBridge?.shutdown()
                                    presentBridge?.shutdown()
                                    inProcessAudio?.stop()
                        inProcessX?.stop()
                                    LaunchPipeline.stop()
                                    EngineForegroundService.stop(this@SessionActivity)
                                    finish()
                                }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
                        // 客體畫面：Xvfb -fbdir XWD → SurfaceFrameBridge blit
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
                        }
                        Text(
                            text = "狀態：$status",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        Text(
                            text = logText,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(0.7f)
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_WORKSPACE_ID = "workspace_id"
        const val EXTRA_FORCE_SMOKE = "force_smoke"
        const val EXTRA_FORCE_GUEST_SMOKE = "force_guest_smoke"
        const val EXTRA_PREFER_ANDROID_GUEST = "prefer_android_guest"
        const val EXTRA_FORCE_PE_SMOKE = "force_pe_smoke"
    }
}
