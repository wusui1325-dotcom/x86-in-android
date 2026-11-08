package com.wusper.x86

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wusper.x86.session.SessionActivity
import com.wusper.x86.ui.theme.X86Theme

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            X86Theme {
                var tick by remember { mutableIntStateOf(0) }
                val workspaces = remember(tick) { WorkspaceStore.listWorkspaces(this) }
                val picked = remember(tick) { WorkspaceStore.loadPickedExes(this) }
                val visibleExes = remember(tick, workspaces, picked) {
                    WorkspaceStore.filterVisibleExes(this, picked)
                }

                val pickExe = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri: Uri? ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
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

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(stringResource(R.string.app_name)) },
                            actions = {
                                IconButton(onClick = { tick++ }) {
                                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.btn_refresh))
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
                        onLaunchWorkspace = { ws ->
                            launchWorkspace(ws, forceSmoke = false)
                            tick++
                        },
                        onLaunchExe = { exe ->
                            launchExe(exe)
                            tick++
                        },
                        onHostSmoke = {
                            // 用內建 host ELF 建／重用工作區並驗收管線
                            val smoke = DeviceExe(
                                displayName = "hello_host_arm64",
                                path = "asset://testdata/hello_host_arm64",
                                uriString = null,
                            )
                            val ws = WorkspaceStore.ensureWorkspace(this, smoke)
                            WorkspaceStore.touchLaunch(this, ws)
                            startActivity(
                                Intent(this, SessionActivity::class.java)
                                    .putExtra(SessionActivity.EXTRA_WORKSPACE_ID, ws.id)
                                    .putExtra(SessionActivity.EXTRA_FORCE_SMOKE, true),
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

    private fun launchWorkspace(ws: Workspace, forceSmoke: Boolean) {
        WorkspaceStore.touchLaunch(this, ws)
        startActivity(
            Intent(this, SessionActivity::class.java)
                .putExtra(SessionActivity.EXTRA_WORKSPACE_ID, ws.id)
                .putExtra(SessionActivity.EXTRA_FORCE_SMOKE, forceSmoke),
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
}

@Composable
private fun HomeScreen(
    padding: PaddingValues,
    workspaces: List<Workspace>,
    visibleExes: List<DeviceExe>,
    onLaunchWorkspace: (Workspace) -> Unit,
    onLaunchExe: (DeviceExe) -> Unit,
    onHostSmoke: () -> Unit,
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = onHostSmoke,
                    label = { Text(stringResource(R.string.btn_host_smoke)) },
                    leadingIcon = { Icon(Icons.Default.Science, contentDescription = null) },
                )
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
                    subtitle = "工作區 · ${ws.dir.name}\n來源：${ws.sourceExeName.ifBlank { ws.sourceExePath }}",
                    icon = { Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    onClick = { onLaunchWorkspace(ws) },
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
                    icon = { Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.secondary) },
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

@Composable
private fun EntryCard(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
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
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
