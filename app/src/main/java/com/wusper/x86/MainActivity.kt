package com.wusper.x86

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.wusper.x86.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val workspaceAdapter = EntryAdapter()
    private val exeAdapter = EntryAdapter()

    private val pickExe = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "unknown.exe"
        val exe = DeviceExe(
            displayName = if (name.endsWith(".exe", true)) name else "$name.exe",
            path = uri.toString(),
            uriString = uri.toString(),
        )
        WorkspaceStore.addPickedExe(this, exe)
        // 首次選取即建工作區並假啟動
        launchExe(exe)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.listWorkspaces.layoutManager = LinearLayoutManager(this)
        binding.listExes.layoutManager = LinearLayoutManager(this)
        binding.listWorkspaces.adapter = workspaceAdapter
        binding.listExes.adapter = exeAdapter

        binding.btnPickExe.setOnClickListener {
            // 常見 PE／任意檔；過濾交給顯示名
            pickExe.launch(arrayOf("*/*", "application/octet-stream", "application/x-msdownload"))
        }
        binding.btnRefresh.setOnClickListener { refreshLists() }

        refreshLists()
    }

    override fun onResume() {
        super.onResume()
        refreshLists()
    }

    private fun refreshLists() {
        val workspaces = WorkspaceStore.listWorkspaces(this)
        workspaceAdapter.submit(
            workspaces.map { ws ->
                ListEntry(
                    title = ws.displayName,
                    subtitle = "工作區 · ${ws.dir.name}\n來源：${ws.sourceExeName.ifBlank { ws.sourceExePath }}",
                    onClick = { launchWorkspace(ws) },
                )
            }
        )
        binding.emptyWorkspaces.visibility =
            if (workspaces.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE

        val allPicked = WorkspaceStore.loadPickedExes(this)
        // Boss：若已有同名／同路徑工作區，隱藏對應 exe，只顯示工作區入口
        val visible = WorkspaceStore.filterVisibleExes(this, allPicked)
        exeAdapter.submit(
            visible.map { exe ->
                ListEntry(
                    title = exe.displayName,
                    subtitle = exe.path,
                    onClick = { launchExe(exe) },
                )
            }
        )
        binding.emptyExes.visibility =
            if (visible.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun launchExe(exe: DeviceExe) {
        val ws = WorkspaceStore.ensureWorkspace(this, exe)
        WorkspaceStore.touchLaunch(this, ws)
        Toast.makeText(
            this,
            getString(R.string.workspace_created, ws.displayName) + "\n" +
                getString(R.string.launch_placeholder, ws.displayName),
            Toast.LENGTH_LONG
        ).show()
        refreshLists()
    }

    private fun launchWorkspace(ws: Workspace) {
        WorkspaceStore.touchLaunch(this, ws)
        Toast.makeText(
            this,
            getString(R.string.launch_placeholder, ws.displayName),
            Toast.LENGTH_SHORT
        ).show()
        refreshLists()
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
