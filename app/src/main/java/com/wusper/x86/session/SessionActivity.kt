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
import android.view.SurfaceView
import com.wusper.x86.WorkspaceStore
import com.wusper.x86.engine.ComponentInstaller
import com.wusper.x86.engine.LaunchPipeline
import com.wusper.x86.engine.NativeEngine
import com.wusper.x86.ui.theme.X86Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

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
                    logText += "模式：${plan.mode}\n"
                    if (plan.needsRuntimeInstall || (plan.mode == LaunchPipeline.Mode.WineProot && !ComponentInstaller.isReady(this@SessionActivity))) {
                        status = "安裝運行時"
                        logText += "首次 PE 啟動：下載 Wine／Ubuntu rootfs／glibc Box64（約 160MB+）…\n"
                        val install = withContext(Dispatchers.IO) {
                            ComponentInstaller.install(this@SessionActivity) { p ->
                                // 進度回寫在 IO；簡化：只記 message
                            }
                        }
                        if (install.isFailure) {
                            status = "安裝失敗"
                            logText += "安裝失敗：${install.exceptionOrNull()?.message}\n"
                            EngineForegroundService.stop(this@SessionActivity)
                            return@LaunchedEffect
                        }
                        logText += "運行時就緒，重建啟動計畫…\n"
                        plan = LaunchPipeline.buildWineProotPlan(
                            this@SessionActivity, ws, plan.targetFile, plan.logFile,
                        )
                        logText += "模式：${plan.mode}\n${plan.kindMessage}\n"
                    }
                    val result = LaunchPipeline.start(plan)
                    status = if (result.ok) "執行中 pid=${result.pid}" else "失敗"
                    logText += result.message + "\n"
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
                        // 客體畫面佔位 Surface（P0：色塊＋日誌；之後接 X11／合成）
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .background(Color(0xFF0A0E14)),
                        ) {
                            AndroidView(
                                factory = { ctx ->
                                    SurfaceView(ctx).apply {
                                        holder.addCallback(object : android.view.SurfaceHolder.Callback {
                                            override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                                                val c = holder.lockCanvas()
                                                try {
                                                    c.drawColor(0xFF12203A.toInt())
                                                    val p = android.graphics.Paint().apply {
                                                        color = 0xFFE8EEF7.toInt()
                                                        textSize = 42f
                                                        isAntiAlias = true
                                                    }
                                                    c.drawText("x86 Surface（P0 佔位）", 48f, 120f, p)
                                                    c.drawText(status, 48f, 180f, p)
                                                } finally {
                                                    holder.unlockCanvasAndPost(c)
                                                }
                                            }
                                            override fun surfaceChanged(
                                                holder: android.view.SurfaceHolder,
                                                format: Int, width: Int, height: Int,
                                            ) {}
                                            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {}
                                        })
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
