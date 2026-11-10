package com.wusper.x86

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wusper.x86.engine.ComponentInstaller
import com.wusper.x86.hot.DriveGoogleAuth
import com.wusper.x86.hot.HotSmokeConfig
import com.wusper.x86.hot.SmokeHotLoop
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf as mutableStateOfRt
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wusper.x86.session.SessionActivity
import com.wusper.x86.ui.theme.X86Theme

class MainActivity : ComponentActivity() {
    /** Compose-observable gates for smokeHot auth / HTTPS config (set from non-Composable methods). */
    private val hotAuthGate = mutableStateOfRt<String?>(null)
    private val hotHttpsGate = mutableStateOfRt(false)
    private val hotAuthStatusTick = mutableStateOfRt(0)
    private var pendingSmokeAfterAuth = false

    private val googleSignInLauncher = registerForActivityResult(StartActivityForResult()) { result ->
        lifecycleScope.launch(Dispatchers.IO) {
            val outcome = DriveGoogleAuth.handleSignInResult(this@MainActivity, result.data)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, outcome.message, Toast.LENGTH_LONG).show()
                hotAuthStatusTick.value = hotAuthStatusTick.value + 1
                if (outcome.ok) {
                    hotAuthGate.value = null
                    if (pendingSmokeAfterAuth) {
                        pendingSmokeAfterAuth = false
                        runHotSmokeRound(skipAuthGate = true)
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            X86Theme {
                var tick by remember { mutableIntStateOf(0) }
                val prefs = remember { getSharedPreferences("x86_ui", Context.MODE_PRIVATE) }
                var developerMode by remember {
                    mutableStateOf(prefs.getBoolean(PREF_DEV, false))
                }
                var versionTapCount by remember { mutableIntStateOf(0) }
                var menuExpanded by remember { mutableStateOf(false) }
                var showClearRuntime by remember { mutableStateOf(false) }
                var showAbout by remember { mutableStateOf(false) }
                val authGate by hotAuthGate
                val httpsGate by hotHttpsGate
                val authTick by hotAuthStatusTick
                val googleStatus = remember(authTick, tick) { DriveGoogleAuth.status(this) }
                var httpsUrlInput by remember { mutableStateOf(HotSmokeConfig.load(this).uploadUrl) }
                var workspacePendingDelete by remember { mutableStateOf<Workspace?>(null) }
                var workspacePendingRebuild by remember { mutableStateOf<Workspace?>(null) }
                var showNewWorkspace by remember { mutableStateOf(false) }
                var scannedExes by remember { mutableStateOf<List<DeviceExe>>(emptyList()) }
                var scanMessage by remember { mutableStateOf<String?>(null) }

                val notifPerm = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { /* 拒絕不阻斷 */ }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        val ok = ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!ok) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    if (BuildConfig.SMOKE_HOT) {
                        SmokeHotLoop.startAutoPoll(
                            this@MainActivity,
                            lifecycleScope,
                            object : SmokeHotLoop.Listener {
                                override fun onStatus(line: String) {
                                    android.util.Log.i("SmokeHotLoop", line)
                                }
                                override fun onRoundDone(ok: Boolean, detail: String) {
                                    android.util.Log.i("SmokeHotLoop", "done ok=$ok $detail")
                                }
                            },
                        )
                        if (intent?.getBooleanExtra("hot_smoke_now", false) == true) {
                            intent.removeExtra("hot_smoke_now")
                            runHotSmokeRound()
                        }
                    }
                }

                val workspaces = remember(tick) { WorkspaceStore.listWorkspaces(this) }
                val workspaceApps = remember(tick, workspaces) {
                    workspaces.associate { it.id to WorkspaceApps.scan(it) }
                }
                val picked = remember(tick) { WorkspaceStore.loadPickedExes(this) }
                val hasAllFiles = remember(tick) {
                    if (Build.VERSION.SDK_INT >= 30) {
                        Environment.isExternalStorageManager()
                    } else {
                        ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                        ) == PackageManager.PERMISSION_GRANTED
                    }
                }
                val deviceExes = remember(tick, scannedExes, picked, hasAllFiles) {
                    val base = if (scannedExes.isNotEmpty()) scannedExes else picked
                    // merge scan + picked (scan wins on path)
                    val map = LinkedHashMap<String, DeviceExe>()
                    for (e in picked) map[e.path.ifBlank { e.displayName }.lowercase()] = e
                    for (e in scannedExes) map[e.path.ifBlank { e.displayName }.lowercase()] = e
                    WorkspaceStore.filterVisibleExes(this, map.values.toList())
                }

                fun requestAllFilesAccess() {
                    if (Build.VERSION.SDK_INT >= 30) {
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = Uri.parse("package:$packageName")
                            }
                            startActivity(intent)
                        } catch (_: Exception) {
                            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        }
                        Toast.makeText(this, getString(R.string.toast_all_files_needed), Toast.LENGTH_LONG).show()
                    } else {
                        // legacy: request READ
                    }
                }

                fun doScanDeviceExes() {
                    if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
                        requestAllFilesAccess()
                        return
                    }
                    val found = WorkspaceStore.scanDeviceExes()
                    scannedExes = found
                    scanMessage = getString(R.string.toast_scan_done, found.size)
                    Toast.makeText(this, scanMessage, Toast.LENGTH_SHORT).show()
                    tick++
                }

                val legacyReadPerm = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    if (granted) doScanDeviceExes() else {
                        Toast.makeText(this, getString(R.string.toast_all_files_needed), Toast.LENGTH_LONG).show()
                    }
                }

                // ＋號：精確匯入 → 新乾淨工作區（複製 exe），不模糊掃描、不假桌面
                val pickExe = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri: Uri? ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    runCatching {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }.onFailure {
                        Toast.makeText(
                            this,
                            getString(R.string.uri_permission_failed),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "unknown.exe"
                    val exe = DeviceExe(
                        displayName = if (name.endsWith(".exe", true)) name else "$name.exe",
                        path = uri.toString(),
                        uriString = uri.toString(),
                    )
                    val stream = runCatching { contentResolver.openInputStream(uri) }.getOrNull()
                    val ws = WorkspaceStore.importExeToNewWorkspace(this, exe, copyFrom = stream)
                    Toast.makeText(
                        this,
                        getString(R.string.workspace_imported, ws.displayName),
                        Toast.LENGTH_LONG,
                    ).show()
                    tick++
                    // 不自動開 session — 讓用戶確認是新乾淨工作區再點
                }

                if (showClearRuntime) {
                    AlertDialog(
                        onDismissRequest = { showClearRuntime = false },
                        title = { Text(stringResource(R.string.dialog_clear_runtime_title)) },
                        text = { Text(stringResource(R.string.dialog_clear_runtime_message)) },
                        confirmButton = {
                            TextButton(onClick = {
                                val freed = ComponentInstaller.clearRuntime(this, alsoClearCache = true)
                                val mb = "%.1f".format(freed / 1e6)
                                Toast.makeText(
                                    this,
                                    getString(R.string.clear_runtime_done, "${mb}MB"),
                                    Toast.LENGTH_LONG,
                                ).show()
                                showClearRuntime = false
                                tick++
                            }) {
                                Text(stringResource(R.string.dialog_clear_runtime_confirm))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showClearRuntime = false }) {
                                Text(stringResource(R.string.dialog_cancel))
                            }
                        },
                    )
                }

                if (showAbout) {
                    AlertDialog(
                        onDismissRequest = { showAbout = false },
                        title = { Text(stringResource(R.string.dialog_about_title)) },
                        text = {
                            Text(
                                stringResource(
                                    R.string.dialog_about_body,
                                    BuildConfig.VERSION_NAME,
                                    BuildConfig.VERSION_CODE,
                                    BuildConfig.GIT_SHA,
                                ),
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { showAbout = false }) {
                                Text(stringResource(R.string.dialog_about_ok))
                            }
                        },
                    )
                }

                if (BuildConfig.SMOKE_HOT && authGate != null) {
                    AlertDialog(
                        onDismissRequest = {
                            hotAuthGate.value = null
                            pendingSmokeAfterAuth = false
                        },
                        title = { Text(stringResource(R.string.dialog_hot_auth_title)) },
                        text = {
                            Text(
                                stringResource(R.string.dialog_hot_auth_body) +
                                    "\n\n" + (authGate ?: "") +
                                    "\n" + googleStatus.summary,
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                pendingSmokeAfterAuth = true
                                googleSignInLauncher.launch(DriveGoogleAuth.signInIntent(this@MainActivity))
                            }) {
                                Text(stringResource(R.string.btn_hot_connect_google))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                hotAuthGate.value = null
                                hotHttpsGate.value = true
                            }) {
                                Text(stringResource(R.string.btn_hot_https_upload))
                            }
                        },
                    )
                }

                if (BuildConfig.SMOKE_HOT && httpsGate) {
                    AlertDialog(
                        onDismissRequest = { hotHttpsGate.value = false },
                        title = { Text(stringResource(R.string.dialog_hot_https_title)) },
                        text = {
                            Column {
                                Text(stringResource(R.string.dialog_hot_https_body))
                                Spacer(Modifier.height(12.dp))
                                OutlinedTextField(
                                    value = httpsUrlInput,
                                    onValueChange = { httpsUrlInput = it },
                                    label = { Text(stringResource(R.string.dialog_hot_https_hint)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                val url = httpsUrlInput.trim()
                                HotSmokeConfig.edit(this@MainActivity) {
                                    it.putString("uploadMode", "https")
                                    it.putString("uploadUrl", url)
                                    it.putString("uploadMethod", "POST")
                                }
                                hotHttpsGate.value = false
                                Toast.makeText(
                                    this@MainActivity,
                                    getString(R.string.toast_hot_https_saved),
                                    Toast.LENGTH_SHORT,
                                ).show()
                                if (pendingSmokeAfterAuth && url.isNotBlank()) {
                                    pendingSmokeAfterAuth = false
                                    runHotSmokeRound(skipAuthGate = true)
                                }
                            }) {
                                Text(stringResource(R.string.dialog_about_ok))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                hotHttpsGate.value = false
                                pendingSmokeAfterAuth = false
                            }) {
                                Text(stringResource(R.string.dialog_cancel))
                            }
                        },
                    )
                }

                workspacePendingDelete?.let { ws ->
                    AlertDialog(
                        onDismissRequest = { workspacePendingDelete = null },
                        title = { Text(stringResource(R.string.dialog_delete_ws_title)) },
                        text = {
                            Text(stringResource(R.string.dialog_delete_ws_message, ws.displayName))
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                WorkspaceStore.deleteWorkspace(this, ws, deleteFiles = true)
                                workspacePendingDelete = null
                                tick++
                            }) {
                                Text(stringResource(R.string.dialog_delete_ws_confirm))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { workspacePendingDelete = null }) {
                                Text(stringResource(R.string.dialog_cancel))
                            }
                        },
                    )
                }

                workspacePendingRebuild?.let { ws ->
                    AlertDialog(
                        onDismissRequest = { workspacePendingRebuild = null },
                        title = { Text(stringResource(R.string.dialog_rebuild_title)) },
                        text = {
                            Text(stringResource(R.string.dialog_rebuild_message, ws.displayName))
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                WorkspaceStore.rebuildPrefix(this, ws)
                                Toast.makeText(
                                    this,
                                    getString(R.string.toast_rebuild_done, ws.displayName),
                                    Toast.LENGTH_LONG,
                                ).show()
                                workspacePendingRebuild = null
                                launchWorkspace(ws)
                                tick++
                            }) {
                                Text(stringResource(R.string.dialog_rebuild_confirm))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                workspacePendingRebuild = null
                                launchWorkspace(ws)
                                tick++
                            }) {
                                Text("仍要啟動")
                            }
                        },
                    )
                }

                if (showNewWorkspace) {
                    AlertDialog(
                        onDismissRequest = { showNewWorkspace = false },
                        title = { Text(stringResource(R.string.dialog_new_ws_title)) },
                        text = { Text(stringResource(R.string.dialog_new_ws_message)) },
                        confirmButton = {
                            TextButton(onClick = {
                                val ws = WorkspaceStore.createCleanWorkspace(
                                    this,
                                    displayName = "新工作區",
                                )
                                Toast.makeText(
                                    this,
                                    getString(R.string.workspace_clean_created, ws.displayName),
                                    Toast.LENGTH_LONG,
                                ).show()
                                showNewWorkspace = false
                                tick++
                            }) {
                                Text(stringResource(R.string.dialog_new_ws_confirm))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showNewWorkspace = false }) {
                                Text(stringResource(R.string.dialog_cancel))
                            }
                        },
                    )
                }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Column {
                                    Text(stringResource(R.string.app_name))
                                    Text(
                                        text = stringResource(
                                            R.string.version_label,
                                            BuildConfig.VERSION_NAME,
                                            BuildConfig.VERSION_CODE,
                                        ) + " · " + BuildConfig.GIT_SHA +
                                            "\n" + stringResource(R.string.abi_min_sdk_note),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.clickable {
                                            versionTapCount++
                                            if (versionTapCount >= 7) {
                                                versionTapCount = 0
                                                developerMode = true
                                                prefs.edit().putBoolean(PREF_DEV, true).apply()
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    getString(R.string.dev_options_enabled),
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                        },
                                    )
                                }
                            },
                            actions = {
                                IconButton(onClick = { tick++ }) {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = stringResource(R.string.btn_refresh),
                                    )
                                }
                                IconButton(onClick = { menuExpanded = true }) {
                                    Icon(
                                        Icons.Default.MoreVert,
                                        contentDescription = stringResource(R.string.menu_more),
                                    )
                                }
                                DropdownMenu(
                                    expanded = menuExpanded,
                                    onDismissRequest = { menuExpanded = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.btn_clear_runtime)) },
                                        onClick = {
                                            menuExpanded = false
                                            showClearRuntime = true
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.Delete, contentDescription = null)
                                        },
                                    )
                                    if (BuildConfig.SMOKE_HOT) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.btn_hot_smoke_round)) },
                                            onClick = {
                                                menuExpanded = false
                                                runHotSmokeRound()
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Default.Science, contentDescription = null)
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    if (googleStatus.connected) {
                                                        stringResource(
                                                            R.string.status_hot_google_connected,
                                                            googleStatus.email.ifBlank { "OK" },
                                                        )
                                                    } else {
                                                        stringResource(R.string.btn_hot_connect_google)
                                                    },
                                                )
                                            },
                                            onClick = {
                                                menuExpanded = false
                                                if (googleStatus.connected) {
                                                    DriveGoogleAuth.signOut(this@MainActivity) {
                                                        hotAuthStatusTick.value = hotAuthStatusTick.value + 1
                                                        Toast.makeText(
                                                            this@MainActivity,
                                                            getString(R.string.status_hot_google_none),
                                                            Toast.LENGTH_SHORT,
                                                        ).show()
                                                    }
                                                } else {
                                                    googleSignInLauncher.launch(
                                                        DriveGoogleAuth.signInIntent(this@MainActivity),
                                                    )
                                                }
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.btn_hot_https_upload)) },
                                            onClick = {
                                                menuExpanded = false
                                                httpsUrlInput = HotSmokeConfig.load(this@MainActivity).uploadUrl
                                                hotHttpsGate.value = true
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.btn_hot_enable_autoloop)) },
                                            onClick = {
                                                menuExpanded = false
                                                val url = HotSmokeConfig.enableOfficialAutoLoop(this@MainActivity)
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    getString(R.string.toast_hot_autoloop_on),
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                                android.util.Log.i("SmokeHotLoop", "enableOfficialAutoLoop url=$url")
                                                // restart poll with new prefs + one immediate round
                                                SmokeHotLoop.startAutoPoll(
                                                    this@MainActivity,
                                                    lifecycleScope,
                                                    object : SmokeHotLoop.Listener {
                                                        override fun onStatus(line: String) {
                                                            android.util.Log.i("SmokeHotLoop", line)
                                                        }
                                                        override fun onRoundDone(ok: Boolean, detail: String) {
                                                            android.util.Log.i("SmokeHotLoop", "done ok=$ok $detail")
                                                        }
                                                    },
                                                )
                                                runHotSmokeRound(skipAuthGate = false)
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Default.Refresh, contentDescription = null)
                                            },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.btn_about)) },
                                        onClick = {
                                            menuExpanded = false
                                            showAbout = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (developerMode) {
                                                    stringResource(R.string.btn_developer_options) + " ✓"
                                                } else {
                                                    stringResource(R.string.dev_options_hint)
                                                },
                                            )
                                        },
                                        onClick = {
                                            menuExpanded = false
                                            if (!developerMode) {
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    getString(R.string.dev_options_hint),
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            } else {
                                                developerMode = false
                                                prefs.edit().putBoolean(PREF_DEV, false).apply()
                                            }
                                        },
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                            ),
                        )
                    },
                    floatingActionButton = {
                        FloatingActionButton(
                            onClick = {
                                pickExe.launch(
                                    arrayOf("*/*", "application/octet-stream", "application/x-msdownload"),
                                )
                            },
                        ) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.btn_import_exe))
                        }
                    },
                ) { padding ->
                    HomeScreen(
                        padding = padding,
                        workspaces = workspaces,
                        workspaceApps = workspaceApps,
                        visibleExes = deviceExes,
                        hasAllFiles = hasAllFiles,
                        showDevChips = developerMode,
                        onNewWorkspace = { showNewWorkspace = true },
                        onGrantAllFiles = {
                            if (Build.VERSION.SDK_INT >= 30) requestAllFilesAccess()
                            else legacyReadPerm.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                        },
                        onScanExes = {
                            if (Build.VERSION.SDK_INT >= 30) doScanDeviceExes()
                            else if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                                == PackageManager.PERMISSION_GRANTED) doScanDeviceExes()
                            else legacyReadPerm.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                        },
                        onLaunchWorkspace = { ws ->
                            if (WorkspaceStore.isWorkspaceDirty(ws)) {
                                workspacePendingRebuild = ws
                            } else {
                                launchWorkspace(ws)
                                tick++
                            }
                        },
                        onLaunchWorkspaceApp = { ws, app ->
                            if (WorkspaceStore.isWorkspaceDirty(ws)) {
                                workspacePendingRebuild = ws
                            } else {
                                launchWorkspace(ws, app)
                                tick++
                            }
                        },
                        onDeleteWorkspace = { ws -> workspacePendingDelete = ws },
                        onRebuildWorkspace = { ws -> workspacePendingRebuild = ws },
                        onLaunchExe = { exe ->
                            // 裝置列表啟動：若尚無工作區 → 新乾淨工作區＋複製；有則沿用
                            val existing = WorkspaceStore.findByExe(this, exe)
                            if (existing == null) {
                                val src = runCatching { java.io.File(exe.path) }.getOrNull()
                                val stream = when {
                                    exe.uriString != null ->
                                        runCatching { contentResolver.openInputStream(Uri.parse(exe.uriString)) }.getOrNull()
                                    src != null && src.isFile -> src.inputStream()
                                    else -> null
                                }
                                val ws = WorkspaceStore.importExeToNewWorkspace(this, exe, copyFrom = stream)
                                Toast.makeText(
                                    this,
                                    getString(R.string.workspace_imported, ws.displayName),
                                    Toast.LENGTH_SHORT,
                                ).show()
                                launchWorkspace(ws)
                            } else {
                                launchWorkspace(existing)
                            }
                            tick++
                        },
                        onHostSmoke = {
                            startSmoke(
                                displayName = "hello_host_arm64",
                                path = "asset://testdata/hello_host_arm64",
                                forceHost = true,
                                forceGuest = false,
                            )
                            tick++
                        },
                        onGuestAndroidSmoke = {
                            startSmoke(
                                displayName = "hello_android_x86_64",
                                path = "asset://testdata/hello_android_x86_64",
                                forceHost = false,
                                forceGuest = true,
                                preferAndroidGuest = true,
                            )
                            tick++
                        },
                        onGuestLinuxSmoke = {
                            startSmoke(
                                displayName = "hello_x86_64",
                                path = "asset://testdata/hello_x86_64",
                                forceHost = false,
                                forceGuest = true,
                                preferAndroidGuest = false,
                            )
                            tick++
                        },
                        onMicrobenchNative = {
                            startSmoke(
                                displayName = "microbench_host_arm64",
                                path = "asset://testdata/microbench_host_arm64",
                                forceHost = false,
                                forceGuest = false,
                            )
                            tick++
                        },
                        onMicrobenchBox64 = {
                            startSmoke(
                                displayName = "microbench_android_x86_64",
                                path = "asset://testdata/microbench_android_x86_64",
                                forceHost = false,
                                forceGuest = false,
                            )
                            tick++
                        },
                        onPeSmoke = {
                            startSmoke(
                                displayName = "wine_whoami.exe",
                                path = "asset://testdata/wine_whoami.exe",
                                forceHost = false,
                                forceGuest = false,
                                forcePe = true,
                            )
                            tick++
                        },
                        onClearRuntime = { showClearRuntime = true },
                    )
                }
            }
        }
    }

    private fun launchExe(exe: DeviceExe) {
        val ws = WorkspaceStore.ensureWorkspace(this, exe)
        WorkspaceStore.touchLaunch(this, ws)
        startActivity(
            Intent(this, SessionActivity::class.java)
                .putExtra(SessionActivity.EXTRA_WORKSPACE_ID, ws.id),
        )
    }

    private fun launchWorkspace(ws: Workspace, app: WorkspaceApp? = null) {
        WorkspaceStore.touchLaunch(this, ws)
        try {
            WorkspaceApps.prepareLaunchExe(ws, app)
        } catch (t: Throwable) {
            Toast.makeText(
                this,
                t.message ?: getString(R.string.workspace_app_missing),
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        startActivity(
            Intent(this, SessionActivity::class.java)
                .putExtra(SessionActivity.EXTRA_WORKSPACE_ID, ws.id),
        )
    }

    private fun startSmoke(
        displayName: String,
        path: String,
        forceHost: Boolean,
        forceGuest: Boolean,
        preferAndroidGuest: Boolean = true,
        forcePe: Boolean = false,
    ) {
        val smoke = DeviceExe(displayName = displayName, path = path, uriString = null)
        val ws = WorkspaceStore.ensureWorkspace(this, smoke)
        WorkspaceStore.touchLaunch(this, ws)
        startActivity(
            Intent(this, SessionActivity::class.java)
                .putExtra(SessionActivity.EXTRA_WORKSPACE_ID, ws.id)
                .putExtra(SessionActivity.EXTRA_FORCE_SMOKE, forceHost)
                .putExtra(SessionActivity.EXTRA_FORCE_GUEST_SMOKE, forceGuest)
                .putExtra(SessionActivity.EXTRA_PREFER_ANDROID_GUEST, preferAndroidGuest)
                .putExtra(SessionActivity.EXTRA_FORCE_PE_SMOKE, forcePe),
        )
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return c.getString(idx)
                }
            }
        return null
    }

    override fun onResume() {
        super.onResume()
        // bump via intent flag — Compose tick is local; use shared pref pulse
        getSharedPreferences("x86_ui", Context.MODE_PRIVATE)
            .edit().putLong("resume_pulse", System.currentTimeMillis()).apply()
    }


    private fun runHotSmokeRound(skipAuthGate: Boolean = false) {
        if (!BuildConfig.SMOKE_HOT) return
        if (SmokeHotLoop.isBusy()) {
            Toast.makeText(this, getString(R.string.toast_hot_smoke_busy), Toast.LENGTH_SHORT).show()
            return
        }
        val cfg = HotSmokeConfig.load(this)
        if (!skipAuthGate && cfg.uploadMode == "drive" && !DriveGoogleAuth.hasUsableLogin(this) && !cfg.httpsReady()) {
            pendingSmokeAfterAuth = true
            hotAuthGate.value = getString(R.string.toast_hot_need_auth)
            return
        }
        val authSummary = DriveGoogleAuth.status(this).summary
        Toast.makeText(
            this,
            getString(R.string.toast_hot_smoke_started) + " · " + authSummary,
            Toast.LENGTH_SHORT,
        ).show()
        lifecycleScope.launch {
            val report = SmokeHotLoop.runOneRound(
                this@MainActivity,
                triggerHot = true,
                listener = object : SmokeHotLoop.Listener {
                    override fun onStatus(line: String) {
                        android.util.Log.i("SmokeHotLoop", line)
                    }
                    override fun onRoundDone(ok: Boolean, detail: String) { }
                },
            )
            val needAuth = report.upload?.needAuth == true || report.detail.contains("NEED_AUTH")
            if (!report.ok && needAuth) {
                pendingSmokeAfterAuth = true
                hotAuthGate.value = report.detail.take(240)
            }
            Toast.makeText(
                this@MainActivity,
                getString(
                    R.string.toast_hot_smoke_done,
                    if (report.ok) "OK ${report.detail.take(120)}" else "FAIL ${report.detail.take(120)}",
                ),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    companion object {
        private const val PREF_DEV = "developer_options"
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeScreen(
    padding: PaddingValues,
    workspaces: List<Workspace>,
    workspaceApps: Map<String, List<WorkspaceApp>>,
    visibleExes: List<DeviceExe>,
    hasAllFiles: Boolean,
    showDevChips: Boolean,
    onNewWorkspace: () -> Unit,
    onGrantAllFiles: () -> Unit,
    onScanExes: () -> Unit,
    onLaunchWorkspace: (Workspace) -> Unit,
    onLaunchWorkspaceApp: (Workspace, WorkspaceApp) -> Unit,
    onDeleteWorkspace: (Workspace) -> Unit,
    onRebuildWorkspace: (Workspace) -> Unit,
    onLaunchExe: (DeviceExe) -> Unit,
    onHostSmoke: () -> Unit,
    onGuestAndroidSmoke: () -> Unit,
    onGuestLinuxSmoke: () -> Unit,
    onMicrobenchNative: () -> Unit,
    onMicrobenchBox64: () -> Unit,
    onPeSmoke: () -> Unit,
    onClearRuntime: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 96.dp, top = 8.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.home_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            // 0.1.44: always-visible 重裝運行時 (also still in ⋮ menu)
            AssistChip(
                onClick = onClearRuntime,
                label = { Text(stringResource(R.string.btn_clear_runtime)) },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
            )
            Spacer(Modifier.height(8.dp))
            if (showDevChips) {
                Text(
                    text = stringResource(R.string.btn_developer_options),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = onHostSmoke,
                        label = { Text(stringResource(R.string.btn_host_smoke)) },
                        leadingIcon = { Icon(Icons.Default.Science, contentDescription = null) },
                    )
                    AssistChip(
                        onClick = onGuestAndroidSmoke,
                        label = { Text(stringResource(R.string.btn_guest_android_smoke)) },
                        leadingIcon = { Icon(Icons.Default.Science, contentDescription = null) },
                    )
                    AssistChip(
                        onClick = onGuestLinuxSmoke,
                        label = { Text(stringResource(R.string.btn_guest_linux_smoke)) },
                        leadingIcon = { Icon(Icons.Default.Science, contentDescription = null) },
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = onMicrobenchNative,
                        label = { Text(stringResource(R.string.btn_microbench_native)) },
                    )
                    AssistChip(
                        onClick = onMicrobenchBox64,
                        label = { Text(stringResource(R.string.btn_microbench_box64)) },
                    )
                    AssistChip(
                        onClick = onPeSmoke,
                        label = { Text(stringResource(R.string.btn_pe_smoke)) },
                    )
                }
            }
        }

        item {
            SectionTitle(stringResource(R.string.title_workspaces))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = onNewWorkspace,
                    label = { Text(stringResource(R.string.btn_new_workspace)) },
                    leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null) },
                )
            }
            Spacer(Modifier.height(4.dp))
        }
        if (workspaces.isEmpty()) {
            item {
                EmptyHint(stringResource(R.string.empty_workspaces))
            }
        } else {
            items(workspaces, key = { it.id }) { ws ->
                val dirty = WorkspaceStore.isWorkspaceDirty(ws)
                val apps = workspaceApps[ws.id].orEmpty()
                val installed = apps.filterNot { it.isSeed }
                EntryCard(
                    title = if (dirty) {
                        "${ws.displayName} · " + stringResource(R.string.workspace_dirty_badge)
                    } else ws.displayName,
                    subtitle = buildString {
                        append("工作區 · ${ws.dir.name}")
                        append('\n')
                        append("來源：${ws.sourceExeName.ifBlank { ws.sourceExePath.ifBlank { "（乾淨／未匯入）" } }}")
                        append('\n')
                        append(stringResource(R.string.workspace_apps_count, apps.size))
                        append('\n')
                        append(if (dirty) "點擊可重建 Prefix；長按刪除" else "點擊啟動來源程式；長按刪除")
                    },
                    icon = {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = null,
                            tint = if (dirty) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                    },
                    onClick = { onLaunchWorkspace(ws) },
                    onLongClick = { onDeleteWorkspace(ws) },
                )
                if (installed.isNotEmpty()) {
                    installed.forEach { app ->
                        EntryCard(
                            title = app.name,
                            subtitle = stringResource(R.string.workspace_app_installed, app.windowsPath),
                            icon = {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                )
                            },
                            onClick = { onLaunchWorkspaceApp(ws, app) },
                            modifier = Modifier.padding(start = 24.dp),
                        )
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            SectionTitle(stringResource(R.string.title_device_exes))
            Text(
                text = stringResource(R.string.hidden_exe_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!hasAllFiles) {
                    AssistChip(
                        onClick = onGrantAllFiles,
                        label = { Text(stringResource(R.string.btn_grant_all_files)) },
                    )
                }
                AssistChip(
                    onClick = onScanExes,
                    label = { Text(stringResource(R.string.btn_scan_exes)) },
                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                )
            }
            Spacer(Modifier.height(4.dp))
        }
        if (visibleExes.isEmpty()) {
            item {
                EmptyHint(stringResource(R.string.empty_exes))
            }
        } else {
            items(visibleExes, key = { it.path.ifBlank { it.displayName } }) { exe ->
                EntryCard(
                    title = exe.displayName,
                    subtitle = exe.path,
                    icon = {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                        )
                    },
                    onClick = { onLaunchExe(exe) },
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 12.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryCard(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                } else {
                    Modifier.clickable(onClick = onClick)
                },
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            icon()
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
