package com.wusper.x86

import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * An application visible inside a workspace's Wine prefix.
 * [isSeed] is the workspace's original launch exe (C:\App\app.exe / exe/seed.exe).
 */
data class WorkspaceApp(
    val name: String,
    val hostPath: String,
    val windowsPath: String,
    val source: String,
    val isSeed: Boolean = false,
)

/**
 * 0.1.118 P1-C: discover programs installed into the same Wine prefix
 * (Start Menu shortcuts, Uninstall registry, Program Files). Never invents entries.
 */
object WorkspaceApps {
    private val skipDirs = setOf(
        "windows", "windows nt", "internet explorer", "windows media player",
        "windows photo viewer", "windowsapps", "common files",
        "modifiablewindowsapps", "microsoft.net", "reference assemblies",
        "installshield installation information", "temp", "tmp", "microsoft",
    )
    private val skipExe = setOf(
        "notepad.exe", "winecfg.exe", "wordpad.exe", "iexplore.exe", "hh.exe",
        "msiexec.exe", "control.exe", "taskmgr.exe", "regedit.exe", "explorer.exe",
        "cmd.exe", "wineboot.exe", "uninstaller.exe", "winemine.exe", "clock.exe",
        "oleview.exe", "winhlp32.exe", "rundll32.exe", "services.exe", "rpcss.exe",
        "svchost.exe", "conhost.exe", "dwm.exe", "start.exe", "wineconsole.exe",
        "plugplay.exe", "winedevice.exe", "winemenubuilder.exe", "winefile.exe",
        "taskhost.exe", "dllhost.exe", "csrss.exe", "smss.exe", "winlogon.exe",
        "sechealthui.exe", "systemsettings.exe",
    )
    private val skipNamePrefixes = listOf("unins", "vcredist", "vc_redist", "dxsetup", "installengine")
    private val skipNameSubstr = listOf("uninstall", "crashhandler", "crashreport", "updater", "updatehelper")

    fun seedFile(ws: Workspace): File = File(ws.exeDir, "seed.exe")
    fun launchSlot(ws: Workspace): File = File(ws.exeDir, "app.exe")

    /** Keep a durable copy of the workspace launch exe so installing/switching apps cannot clobber it. */
    fun ensureSeedBackup(ws: Workspace) {
        ws.exeDir.mkdirs()
        val seed = seedFile(ws)
        val slot = launchSlot(ws)
        if ((!seed.exists() || seed.length() == 0L) && slot.exists() && slot.length() > 0L) {
            slot.copyTo(seed, overwrite = true)
        }
    }

    /**
     * Stage [app] (or the seed) into exe/app.exe so the existing C:\App\app.exe
     * argv/ladder is unchanged. Returns the launch slot.
     */
    fun prepareLaunchExe(ws: Workspace, app: WorkspaceApp?): File {
        ensureSeedBackup(ws)
        val slot = launchSlot(ws)
        val source: File = when {
            app == null || app.isSeed -> {
                val seed = seedFile(ws)
                if (seed.exists() && seed.length() > 0L) seed else slot
            }
            else -> {
                val f = File(app.hostPath)
                if (f.exists() && f.isFile && f.length() > 0L) f
                else throw IllegalStateException("找不到已安裝程式：${app.name}（${app.hostPath}）")
            }
        }
        if (source.canonicalPath != slot.canonicalPath) {
            source.copyTo(slot, overwrite = true)
        }
        return slot
    }

    fun stampLine(ws: Workspace): String {
        val apps = scan(ws)
        val list = apps.joinToString(",") { a ->
            val tag = if (a.isSeed) "seed" else a.source
            "${a.name.replace(",", " ")}:$tag"
        }.ifBlank { "-" }
        return "WS_APPS count=${apps.size} list=$list"
    }

    fun scan(ws: Workspace): List<WorkspaceApp> {
        ensureSeedBackup(ws)
        val prefix = WorkspaceStore.winePrefix(ws)
        val out = ArrayList<WorkspaceApp>()
        val seen = HashSet<String>()

        fun add(name: String, host: File, source: String, isSeed: Boolean) {
            if (!host.isFile || host.length() <= 0L) return
            if (!host.name.endsWith(".exe", ignoreCase = true)) return
            val key = try {
                host.canonicalPath.lowercase(Locale.US)
            } catch (_: Throwable) {
                host.absolutePath.lowercase(Locale.US)
            }
            if (key in seen) return
            val win = hostToWindows(prefix, host)
            val rel = win.replace('\\', '/').lowercase(Locale.US)
            if (!isSeed) {
                if (skipExeName(host.name)) return
                if (rel.contains("/windows/system32") || rel.contains("/windows/syswow64") ||
                    rel.startsWith("c:/windows/") || key.contains("/drive_c/windows/")
                ) return
                if (rel == "c:/app/app.exe") return
            }
            seen += key
            out += WorkspaceApp(
                name = name.ifBlank { host.nameWithoutExtension },
                hostPath = host.absolutePath,
                windowsPath = win.ifBlank { host.absolutePath },
                source = source,
                isSeed = isSeed,
            )
        }

        val seed = seedFile(ws)
        val slot = launchSlot(ws)
        val seedName = ws.displayName.ifBlank {
            ws.sourceExeName.removeSuffix(".exe").ifBlank { "app" }
        }
        when {
            seed.exists() && seed.length() > 0L -> add(seedName, seed, "seed", true)
            slot.exists() && slot.length() > 0L -> add(seedName, slot, "seed", true)
        }
        val prefixSlot = File(prefix, "drive_c/App/app.exe")
        if (prefixSlot.exists() && prefixSlot.length() > 0L) {
            add(seedName, prefixSlot, "seed", true)
        }

        if (prefix.isDirectory) {
            for ((name, host, source) in walkStartMenu(prefix)) add(name, host, source, false)
            for ((name, host, source) in collectUninstall(prefix)) add(name, host, source, false)
            for ((name, host, source) in walkProgramFiles(prefix)) add(name, host, source, false)
        }
        return out
    }

    private fun skipExeName(name: String): Boolean {
        val n = name.lowercase(Locale.US)
        if (n in skipExe) return true
        if (skipNamePrefixes.any { n.startsWith(it) }) return true
        if (skipNameSubstr.any { it in n }) return true
        return false
    }

    private fun windowsToHost(prefix: File, winPath: String): File? {
        var s = winPath.trim().trim('"')
        if (s.isEmpty()) return null
        val comma = Regex(""",-?\d+$""")
        if (comma.containsMatchIn(s) &&
            (s.contains(".exe", true) || s.contains(".dll", true) || s.contains(".ico", true))
        ) {
            s = s.replace(comma, "")
        }
        s = s.replace('/', '\\')
        s = Regex("%SystemRoot%", RegexOption.IGNORE_CASE).replace(s, """C:\windows""")
        s = Regex("%windir%", RegexOption.IGNORE_CASE).replace(s, """C:\windows""")
        s = Regex("""%ProgramFiles\(x86\)%""", RegexOption.IGNORE_CASE).replace(s, """C:\Program Files (x86)""")
        s = Regex("%ProgramFiles%", RegexOption.IGNORE_CASE).replace(s, """C:\Program Files""")
        val m = Regex("""^[A-Za-z]:\\(.*)""").find(s) ?: return null
        return File(prefix, "drive_c" + File.separator + m.groupValues[1].replace('\\', File.separatorChar))
    }

    private fun hostToWindows(prefix: File, host: File): String {
        val drive = File(prefix, "drive_c")
        val ap = try { host.canonicalFile } catch (_: Throwable) { host.absoluteFile }
        val ad = try { drive.canonicalFile } catch (_: Throwable) { drive.absoluteFile }
        val aps = ap.absolutePath
        val ads = ad.absolutePath
        if (aps == ads || aps.startsWith(ads + File.separator)) {
            val rel = aps.removePrefix(ads).trimStart('/', '\\')
            return "C:\\" + rel.replace('/', '\\')
        }
        return ""
    }

    private fun walkStartMenu(prefix: File): List<Triple<String, File, String>> {
        val drive = File(prefix, "drive_c")
        val roots = mutableListOf(
            File(drive, "ProgramData/Microsoft/Windows/Start Menu"),
        )
        File(drive, "users").listFiles()?.forEach { u ->
            if (u.isDirectory) {
                roots += File(u, "AppData/Roaming/Microsoft/Windows/Start Menu")
                roots += File(u, "Desktop")
            }
        }
        val found = ArrayList<Triple<String, File, String>>()
        for (root in roots) {
            if (!root.isDirectory) continue
            root.walkTopDown().forEach { f ->
                if (!f.isFile || !f.name.endsWith(".lnk", ignoreCase = true)) return@forEach
                val target = parseMsShortcut(f) ?: return@forEach
                val host = windowsToHost(prefix, target) ?: return@forEach
                if (!host.isFile || !host.name.endsWith(".exe", ignoreCase = true)) return@forEach
                if (skipExeName(host.name)) return@forEach
                found += Triple(f.nameWithoutExtension, host, "start_menu")
            }
        }
        return found
    }

    private fun collectUninstall(prefix: File): List<Triple<String, File, String>> {
        val found = ArrayList<Triple<String, File, String>>()
        for (hive in listOf("system.reg", "user.reg")) {
            val file = File(prefix, hive)
            if (!file.isFile) continue
            val text = runCatching { file.readText() }.getOrNull() ?: continue
            for ((sect, vals) in iterRegSections(text)) {
                if (!sect.lowercase(Locale.US).contains("\\microsoft\\windows\\currentversion\\uninstall\\")) {
                    continue
                }
                val name = unquoteReg(vals["DisplayName"] ?: vals["QuietDisplayName"] ?: "")
                val icon = unquoteReg(vals["DisplayIcon"] ?: "")
                val loc = unquoteReg(vals["InstallLocation"] ?: "")
                val iconHost = if (icon.isNotBlank()) windowsToHost(prefix, icon) else null
                if (iconHost != null && iconHost.isFile && iconHost.name.endsWith(".exe", true)) {
                    found += Triple(name.ifBlank { iconHost.nameWithoutExtension }, iconHost, "uninstall")
                    continue
                }
                if (loc.isNotBlank()) {
                    val locHost = windowsToHost(prefix, loc)
                    if (locHost != null && locHost.isDirectory) {
                        val pick = pickDirMainExe(locHost, name)
                        if (pick != null) {
                            found += Triple(name.ifBlank { pick.nameWithoutExtension }, pick, "uninstall")
                        }
                    }
                }
            }
        }
        return found
    }

    private fun walkProgramFiles(prefix: File): List<Triple<String, File, String>> {
        val drive = File(prefix, "drive_c")
        val found = ArrayList<Triple<String, File, String>>()
        for (pf in listOf("Program Files", "Program Files (x86)")) {
            val root = File(drive, pf)
            if (!root.isDirectory) continue
            val vendors = root.listFiles()?.filter { it.isDirectory } ?: continue
            for (vendor in vendors) {
                if (vendor.name.lowercase(Locale.US) in skipDirs) continue
                var pick = pickDirMainExe(vendor, vendor.name)
                if (pick == null) {
                    val subs = vendor.listFiles()?.filter { it.isDirectory } ?: emptyList()
                    for (s in subs) {
                        if (s.name.lowercase(Locale.US) in skipDirs) continue
                        pick = pickDirMainExe(s, s.name)
                        if (pick != null) break
                    }
                }
                if (pick != null && !skipExeName(pick.name)) {
                    found += Triple(vendor.name, pick, "program_files")
                }
            }
        }
        return found
    }

    private fun pickDirMainExe(directory: File, preferredName: String?): File? {
        val entries = directory.listFiles()?.filter { it.isFile && it.name.endsWith(".exe", true) } ?: return null
        val cands = entries.filterNot { skipExeName(it.name) }
        if (cands.isEmpty()) return null
        val want = (preferredName ?: "").lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "")
        val dirname = directory.name.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "")
        for (c in cands) {
            val stem = c.nameWithoutExtension.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "")
            if (stem.isNotEmpty() && (stem == want || stem == dirname || (dirname.startsWith(stem)) || (want.contains(stem)))) {
                return c
            }
        }
        for (c in cands) {
            if (c.nameWithoutExtension.equals(directory.name, ignoreCase = true)) return c
        }
        for (c in cands) {
            val n = c.name.lowercase(Locale.US)
            if ("helper" !in n && "service" !in n && "broker" !in n) return c
        }
        return cands[0]
    }

    private fun iterRegSections(text: String): List<Pair<String, Map<String, String>>> {
        val out = ArrayList<Pair<String, Map<String, String>>>()
        var current: String? = null
        var vals = LinkedHashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                if (current != null) out += current!! to vals
                val end = line.indexOf(']')
                if (end < 0) {
                    current = null
                    vals = LinkedHashMap()
                    continue
                }
                current = line.substring(1, end).replace("\\\\", "\\")
                vals = LinkedHashMap()
            } else if (current != null && line.startsWith("\"") && "=" in line) {
                val eq = line.indexOf('=')
                val k = line.substring(0, eq).trim().trim('"')
                val v = line.substring(eq + 1).trim()
                vals[k] = v
            }
        }
        if (current != null) out += current!! to vals
        return out
    }

    private fun unquoteReg(v: String): String {
        var s = v.trim()
        if (s.startsWith("str(")) {
            val q = s.indexOf('"')
            if (q >= 0) s = s.substring(q)
        }
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length - 1).replace("\\\\", "\\")
        }
        return s
    }

    /** Minimal MS-SHLLINK parser: LocalBasePath / LocalBasePathUnicode. */
    fun parseMsShortcut(file: File): String? {
        val data = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (data.size < 0x4C) return null
        if (data[0] != 'L'.code.toByte() || data[1] != 0.toByte() || data[2] != 0.toByte() || data[3] != 0.toByte()) {
            return null
        }
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val flags = buf.getInt(0x14)
        var off = 0x4C
        if (flags and 0x00000001 != 0) {
            if (off + 2 > data.size) return null
            val idLen = buf.getShort(off).toInt() and 0xFFFF
            off += 2 + idLen
        }
        var target: String? = null
        if (flags and 0x00000002 != 0) {
            if (off + 4 > data.size) return target
            val infoSize = buf.getInt(off)
            if (infoSize < 0x1C || off + infoSize > data.size) return target
            val blobOff = off
            val headerSize = buf.getInt(blobOff + 4)
            val infoFlags = buf.getInt(blobOff + 8)
            val localOff = buf.getInt(blobOff + 16)
            if (infoFlags and 0x1 != 0 && localOff > 0 && blobOff + localOff < blobOff + infoSize) {
                val start = blobOff + localOff
                val end = (start until (blobOff + infoSize)).firstOrNull { data[it] == 0.toByte() } ?: (blobOff + infoSize)
                if (end > start) {
                    target = String(data, start, end - start, Charsets.ISO_8859_1)
                }
            }
            if (headerSize >= 0x24 && blobOff + 0x20 <= blobOff + infoSize) {
                val uniOff = buf.getInt(blobOff + 0x1C)
                if (uniOff > 0 && blobOff + uniOff + 1 < blobOff + infoSize) {
                    var p = blobOff + uniOff
                    val chars = StringBuilder()
                    while (p + 1 < blobOff + infoSize) {
                        val ch = buf.getShort(p).toInt() and 0xFFFF
                        if (ch == 0) break
                        chars.append(ch.toChar())
                        p += 2
                    }
                    if (chars.isNotEmpty()) target = chars.toString()
                }
            }
            off += infoSize
        }
        return target
    }

    fun formatStampFromJson(file: File): String? {
        if (!file.isFile) return null
        val obj = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return null
        val count = obj.optInt("count", -1)
        if (count < 0) return null
        return "WS_APPS count=$count"
    }
}
