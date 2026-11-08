package com.wusper.x86

import android.content.Intent
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
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
import com.wusper.x86.session.SessionActivity
import com.wusper.x86.ui.theme.X86Theme

class MainActivity : ComponentActivity() {
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
                var workspacePendingDelete by remember { mutableStateOf<Workspace?>(null) }

                val workspaces = remember(tick) { WorkspaceStore.listWorkspaces(this) }
                val picked = remember(tick) { WorkspaceStore.loadPickedExes(this) }
                val visibleExes = remember(tick, workspaces, picked) {
                    WorkspaceStore.filterVisibleExes(this, picked)
                }

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
                    WorkspaceStore.addPickedExe(this, exe)
                    launchExe(exe)
                    tick++
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
                                        ),
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
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.btn_pick_exe))
                        }
                    },
                ) { padding ->
                    HomeScreen(
                        padding = padding,
                        workspaces = workspaces,
                        visibleExes = visibleExes,
                        showDevChips = developerMode,
                        onLaunchWorkspace = { ws ->
                            launchWorkspace(ws)
                            tick++
                        },
                        onDeleteWorkspace = { ws -> workspacePendingDelete = ws },
                        onLaunchExe = { exe ->
                            launchExe(exe)
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

    private fun launchWorkspace(ws: Workspace) {
        WorkspaceStore.touchLaunch(this, ws)
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

    companion object {
        private const val PREF_DEV = "developer_options"
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeScreen(
    padding: PaddingValues,
    workspaces: List<Workspace>,
    visibleExes: List<DeviceExe>,
    showDevChips: Boolean,
    onLaunchWorkspace: (Workspace) -> Unit,
    onDeleteWorkspace: (Workspace) -> Unit,
    onLaunchExe: (DeviceExe) -> Unit,
    onHostSmoke: () -> Unit,
    onGuestAndroidSmoke: () -> Unit,
    onGuestLinuxSmoke: () -> Unit,
    onMicrobenchNative: () -> Unit,
    onMicrobenchBox64: () -> Unit,
    onPeSmoke: () -> Unit,
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
        }
        if (workspaces.isEmpty()) {
            item {
                EmptyHint(stringResource(R.string.empty_workspaces))
            }
        } else {
            items(workspaces, key = { it.id }) { ws ->
                EntryCard(
                    title = ws.displayName,
                    subtitle = "工作區 · ${ws.dir.name}\n來源：${ws.sourceExeName.ifBlank { ws.sourceExePath }}\n長按可刪除",
                    icon = {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    onClick = { onLaunchWorkspace(ws) },
                    onLongClick = { onDeleteWorkspace(ws) },
                )
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
) {
    Card(
        modifier = Modifier
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
