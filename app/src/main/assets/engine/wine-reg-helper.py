#!/usr/bin/env python3
"""Wine registry offline helpers for x86-in-android.
Wine 11 (Kron4ek) writes UTF-8 text with header 'WINE REGISTRY Version 2'
(see server/registry.c fprintf). Windows .reg UTF-16LE is NOT Wine's format.
Invalid/missing header → Wine prints 'not a valid registry file' and defaults
prefix_type to PREFIX_32BIT.
"""
import os
import json, sys, re

HEADER = "WINE REGISTRY Version 2"
REG_NAMES = ("system.reg", "user.reg", "userdef.reg")

def detect(path):
    if not os.path.isfile(path):
        return {"exists": False, "encoding": "missing", "valid": False, "arch": "missing", "bytes": 0}
    raw = open(path, "rb").read()
    n = len(raw)
    enc = "unknown"
    text = None
    if raw.startswith(b"\xff\xfe"):
        enc = "utf16le"
        try:
            text = raw.decode("utf-16-le")
        except Exception:
            text = None
    elif raw.startswith(b"\xfe\xff"):
        enc = "utf16be"
        try:
            text = raw.decode("utf-16-be")
        except Exception:
            text = None
    else:
        # Wine native: UTF-8 / ASCII
        enc = "utf8"
        try:
            text = raw.decode("utf-8")
        except Exception:
            text = None
    valid = False
    arch = "missing"
    if text is not None:
        # First non-empty line must be exact header (BOM already stripped by decode for utf16)
        lines = text.splitlines()
        first = ""
        for ln in lines:
            if ln.strip():
                first = ln.strip()
                break
        # UTF-16 decode may leave BOM char
        if first.startswith("\ufeff"):
            first = first[1:].strip()
        valid = (first == HEADER)
        for ln in lines:
            s = ln.strip()
            if s.startswith("#arch="):
                arch = s.split("=", 1)[1].strip()
                break
    return {"exists": True, "encoding": enc, "valid": valid, "arch": arch, "bytes": n, "text": text}

def write_utf8(path, text):
    if not text.endswith("\n"):
        text += "\n"
    data = text.encode("utf-8")
    tmp = path + ".tmp"
    with open(tmp, "wb") as f:
        f.write(data)
        f.flush(); os.fsync(f.fileno())
    os.replace(tmp, path)

def ensure_header_and_arch(text, arch="win64"):
    """Return text with Version 2 header + #arch=win64 global option."""
    if text.startswith("\ufeff"):
        text = text[1:]
    # Drop wrong headers / old arch lines
    lines = text.splitlines(True)
    out = []
    saw_header = False
    for ln in lines:
        s = ln.strip()
        if s.startswith("WINE REGISTRY"):
            if not saw_header:
                out.append(HEADER + "\n")
                saw_header = True
            continue
        if s.startswith("#arch="):
            continue  # re-add later
        out.append(ln if ln.endswith("\n") else ln + "\n")
    if not saw_header:
        out.insert(0, HEADER + "\n")
        saw_header = True
    # Insert #arch after header / leading comments
    body = "".join(out)
    # Rebuild with arch after header block
    lines = body.splitlines(True)
    insert_at = 1
    while insert_at < len(lines) and (lines[insert_at].startswith(";;") or lines[insert_at].startswith(";") or lines[insert_at].strip() == ""):
        insert_at += 1
    lines.insert(insert_at, "\n#arch=%s\n" % arch)
    return "".join(lines)

def cmd_check(prefix):
    results = {}
    all_valid = True
    any_exists = False
    for name in REG_NAMES:
        path = os.path.join(prefix, name)
        info = detect(path)
        info.pop("text", None)
        results[name] = info
        if info["exists"]:
            any_exists = True
            if not info["valid"]:
                all_valid = False
        else:
            # missing is ok before wineboot; not "invalid"
            pass
    # valid set means: every existing file is valid, and system.reg exists+valid for "ready"
    sys_ok = results["system.reg"]["exists"] and results["system.reg"]["valid"]
    print("WINE_REG encoding=%s valid=%s sys_arch=%s bytes=%s ready=%s detail=%s" % (
        results["system.reg"].get("encoding"),
        results["system.reg"].get("valid"),
        results["system.reg"].get("arch"),
        results["system.reg"].get("bytes"),
        sys_ok and all_valid,
        {k: {kk: vv for kk, vv in v.items()} for k, v in results.items()},
    ))
    # exit 0 if no invalid files; exit 2 if any invalid exists; exit 3 if system missing
    invalid = [n for n, i in results.items() if i["exists"] and not i["valid"]]
    if invalid:
        sys.exit(2)
    if not results["system.reg"]["exists"]:
        sys.exit(3)
    if results["system.reg"]["arch"] != "win64":
        sys.exit(4)
    sys.exit(0)

def cmd_purge_invalid(prefix):
    purged = []
    for name in REG_NAMES:
        path = os.path.join(prefix, name)
        info = detect(path)
        if info["exists"] and not info["valid"]:
            os.remove(path)
            purged.append("%s:%s:%sB" % (name, info["encoding"], info["bytes"]))
        # Also purge tiny stubs that claim valid but are incomplete? handled by valid check
    # Purge UTF-16 that Wine 11 can't read even if we decoded header OK — Wine uses fgets UTF-8
    for name in REG_NAMES:
        path = os.path.join(prefix, name)
        if not os.path.isfile(path):
            continue
        raw = open(path, "rb").read(4)
        if raw.startswith(b"\xff\xfe") or raw.startswith(b"\xfe\xff"):
            # Wine 11 fopen/fgets cannot consume UTF-16LE; rewrite to UTF-8 or delete
            info = detect(path)
            if info.get("text") and info["valid"]:
                write_utf8(path, ensure_header_and_arch(info["text"]))
                purged.append("%s:utf16->utf8" % name)
            else:
                os.remove(path)
                purged.append("%s:utf16-deleted" % name)
    print("purged=%s" % (purged if purged else "none"))
    sys.exit(0)

def cmd_heal_arch(prefix):
    """Patch #arch=win64 only on VALID existing regs. NEVER create stubs.
    If invalid → purge and exit 2 (caller must wineboot).
    If missing system.reg → exit 3 (caller must wineboot)."""
    os.makedirs(os.path.join(prefix, "drive_c", "windows", "syswow64"), exist_ok=True)
    # First purge invalid
    invalid = []
    for name in REG_NAMES:
        path = os.path.join(prefix, name)
        info = detect(path)
        if info["exists"] and not info["valid"]:
            invalid.append(name)
            os.remove(path)
    if invalid:
        print("heal invalid_purged=%s need_wineboot=1" % invalid)
        sys.exit(2)
    sys_path = os.path.join(prefix, "system.reg")
    if not os.path.isfile(sys_path):
        print("heal system.reg=missing need_wineboot=1")
        sys.exit(3)
    changed = []
    vers = {}
    for name in REG_NAMES:
        path = os.path.join(prefix, name)
        if not os.path.isfile(path):
            vers[name] = "missing"
            continue
        info = detect(path)
        # Convert utf16 wine-incompatible to utf8
        text = info["text"]
        if text is None:
            os.remove(path)
            invalid.append(name)
            continue
        new = ensure_header_and_arch(text, "win64")
        # Always rewrite as UTF-8 (Wine native) if was utf16 or arch wrong or header wrong form
        need = (info["encoding"] != "utf8") or (info["arch"] != "win64") or (new != text)
        # Also rewrite if header was wrong variant already fixed in ensure_header
        if need:
            write_utf8(path, new)
            changed.append(name)
        info2 = detect(path)
        vers[name] = info2["arch"]
    if invalid:
        print("heal invalid_purged=%s need_wineboot=1" % invalid)
        sys.exit(2)
    ok = vers.get("system.reg") == "win64"
    print("heal changed=%s ver=%s encoding=utf8 ok=%s" % (changed, vers, ok))
    sys.exit(0 if ok else 2)

def strip_svc_blocks(text, name):
    pat = re.compile(
        r"(?ms)^\[System\\\\(?:CurrentControlSet|ControlSet001)\\\\Services\\\\"
        + re.escape(name)
        + r"\][^\[]*"
    )
    return pat.sub("", text)

def has_svc(text, name):
    return ("Services\\\\" + name + "]") in text

def svc_block(name, typ, start, image, display, group=None, desc=None, obj="LocalSystem"):
    import time
    t = format(int(time.time() * 1e7), "x")
    lines = []
    for hive in ("CurrentControlSet", "ControlSet001"):
        lines.append("[System\\\\%s\\\\Services\\\\%s]" % (hive, name))
        lines.append("#time=%s" % t)
        lines.append('"Type"=dword:%08x' % typ)
        lines.append('"Start"=dword:%08x' % start)
        lines.append('"ErrorControl"=dword:00000001')
        lines.append('"ImagePath"=str(2):"%s"' % image)
        lines.append('"DisplayName"="%s"' % display)
        if obj:
            lines.append('"ObjectName"="%s"' % obj)
        if group:
            lines.append('"Group"="%s"' % group)
        if desc:
            lines.append('"Description"="%s"' % desc)
        lines.append("")
    return "\n".join(lines)

def cmd_inject_rpcss(prefix):
    path = os.path.join(prefix, "system.reg")
    info = detect(path)
    if not info["exists"]:
        print("inject skip system.reg=missing need_wineboot=1")
        sys.exit(3)
    if not info["valid"] or info["text"] is None:
        print("inject skip system.reg=invalid encoding=%s need_wineboot=1" % info["encoding"])
        if info["exists"]:
            os.remove(path)
        sys.exit(2)
    text = ensure_header_and_arch(info["text"], "win64")
    text = strip_svc_blocks(text, "RpcSs")
    text = strip_svc_blocks(text, "MountMgr")
    if not text.endswith("\n"):
        text += "\n"
    text += "\n;; --- x86-in-android offline RpcSs/MountMgr inject ---\n"
    text += svc_block(
        "RpcSs", 0x20, 0x3,  # DEMAND_START — avoid AUTO 1053 race; we start once
        "%SystemRoot%\\\\system32\\\\rpcss.exe",
        "Remote Procedure Call (RPC)",
        desc="RPC service",
    ) + "\n"
    text += svc_block(
        "MountMgr", 0x1, 0x3,  # DEMAND — avoid SCM autostart 1053
        "%SystemRoot%\\\\system32\\\\drivers\\\\mountmgr.sys",
        "Mount Manager",
        group="System Bus Extender",
        desc="Device mounting service",
        obj=None,
    ) + "\n"
    write_utf8(path, text)
    final = detect(path)
    ok = final["valid"] and has_svc(final["text"] or "", "RpcSs") and has_svc(final["text"] or "", "MountMgr")
    print("inject encoding=%s valid=%s rpcss=%s mountmgr=%s arch=%s bytes=%s ok=%s" % (
        final["encoding"], final["valid"],
        has_svc(final["text"] or "", "RpcSs"),
        has_svc(final["text"] or "", "MountMgr"),
        final["arch"], final["bytes"], ok))
    sys.exit(0 if ok else 2)


def cmd_inject_exe_assoc(prefix):
    """0.1.94 H4: ensure HKCR .exe -> exefile -> shell/open/command so ShellExecute
    and start.exe fallbacks can CreateProcess the PE (wineboot soft-continue may
    leave Classes incomplete)."""
    path = os.path.join(prefix, "system.reg")
    info = detect(path)
    if not info["exists"] or not info["valid"] or info["text"] is None:
        print("exeassoc skip system.reg ready=0")
        sys.exit(3)
    text = info["text"]
    # Idempotent: strip prior inject marker blocks then append
    marker = ";; --- x86-in-android offline .exe assoc inject ---"
    if marker in text:
        # remove previous inject tail (from marker to EOF or next ;; ---)
        idx = text.find(marker)
        # find end of our previous block: next ";; ---" after marker+len, else EOF
        rest = text[idx + len(marker):]
        nxt = rest.find("\n;; ---")
        if nxt >= 0:
            text = text[:idx] + rest[nxt+1:]
        else:
            text = text[:idx]
    if not text.endswith("\n"):
        text += "\n"
    # Wine registry format: [Software\\Classes\\...] under HKLM is system.reg
    block = """
;; --- x86-in-android offline .exe assoc inject ---
[Software\\\\Classes\\\\.exe]
@="exefile"

[Software\\\\Classes\\\\exefile]
@="Application"

[Software\\\\Classes\\\\exefile\\\\DefaultIcon]
@="%%1"

[Software\\\\Classes\\\\exefile\\\\shell]

[Software\\\\Classes\\\\exefile\\\\shell\\\\open]

[Software\\\\Classes\\\\exefile\\\\shell\\\\open\\\\command]
@="\\"%1\\" %*"

[Software\\\\Classes\\\\Wow6432Node\\\\.exe]
@="exefile"

[Software\\\\Classes\\\\Wow6432Node\\\\exefile]
@="Application"

[Software\\\\Classes\\\\Wow6432Node\\\\exefile\\\\shell\\\\open\\\\command]
@="\\"%1\\" %*"
"""
    text += block
    write_utf8(path, text)
    final = detect(path)
    ok = final["valid"] and ("Classes\\\\.exe]" in (final["text"] or "") or 'Classes\\.exe]' in (final["text"] or ""))
    # Wine stores as Software\\Classes\\.exe] in the file
    has = False
    t = final["text"] or ""
    if "Classes\\.exe]" in t or "Classes\\\\.exe]" in t or 'Classes\\.exe]' in t:
        has = True
    if ".exe]" in t and "exefile" in t:
        has = True
    print("exeassoc encoding=%s valid=%s has_exe=%s arch=%s bytes=%s ok=%s" % (
        final["encoding"], final["valid"], has, final["arch"], final["bytes"], has and final["valid"]))
    sys.exit(0 if (has and final["valid"]) else 2)

def cmd_patch_graphics(prefix):
    """Set Graphics=x11 in user.reg only if file is valid Wine reg; never create from scratch before wineboot."""
    path = os.path.join(prefix, "user.reg")
    info = detect(path)
    if not info["exists"] or not info["valid"] or info["text"] is None:
        print("graphics skip user.reg ready=0")
        sys.exit(3)
    text = info["text"]
    if '"Graphics"="x11"' in text:
        print("graphics already=x11")
        sys.exit(0)
    # remove old Graphics lines
    lines = []
    for ln in text.splitlines(True):
        if ln.strip().startswith('"Graphics"='):
            continue
        lines.append(ln if ln.endswith("\n") else ln + "\n")
    text = "".join(lines)
    if "[Software\\\\Wine\\\\Drivers]" in text:
        # append after Drivers header
        out = []
        done = False
        for ln in text.splitlines(True):
            out.append(ln if ln.endswith("\n") else ln + "\n")
            if (not done) and ln.strip() == "[Software\\\\Wine\\\\Drivers]":
                out.append('"Graphics"="x11"\n')
                done = True
        if not done:
            out.append("\n[Software\\\\Wine\\\\Drivers]\n\"Graphics\"=\"x11\"\n")
        text = "".join(out)
    else:
        text += "\n[Software\\\\Wine\\\\Drivers]\n\"Graphics\"=\"x11\"\n"
    text = ensure_header_and_arch(text, "win64")
    write_utf8(path, text)
    print("graphics set=x11 encoding=utf8")
    sys.exit(0)

def cmd_patch_dll_overrides(prefix):
    """Ensure D3D DLL overrides in user.reg so wineboot children keep DXVK/wined3d.
    Usage: wine_reg_helper.py patch-dll-overrides <prefix> [native|builtin]
    Default mode=native (= native,builtin for DXVK). builtin = wined3d bisect.
    Never create user.reg from scratch before wineboot (same contract as patch-graphics).
    """
    mode = "native"
    if len(sys.argv) >= 4 and sys.argv[3] in ("native", "builtin"):
        mode = sys.argv[3]
    elif os.environ.get("X86_DXWRAPPER", "").strip().lower() == "wined3d":
        mode = "builtin"
    value = "native,builtin" if mode == "native" else "builtin"
    path = os.path.join(prefix, "user.reg")
    info = detect(path)
    if not info["exists"] or not info["valid"] or info["text"] is None:
        print("dlloverrides skip user.reg ready=0")
        sys.exit(3)
    text = info["text"]
    dlls = ("d3d8", "d3d9", "d3d10core", "d3d11", "dxgi")
    # Wine user.reg section paths store doubled backslashes like Drivers.
    section = "[Software\\\\Wine\\\\DllOverrides]"
    lines = []
    for ln in text.splitlines(True):
        s = ln.strip()
        drop = False
        for d in dlls:
            if s.startswith('"%s"=' % d):
                drop = True
                break
        if not drop:
            lines.append(ln if ln.endswith("\n") else ln + "\n")
    text = "".join(lines)
    block_lines = [section + "\n"] + ['"%s"="%s"\n' % (d, value) for d in dlls]
    if section in text:
        out = []
        done = False
        for ln in text.splitlines(True):
            out.append(ln if ln.endswith("\n") else ln + "\n")
            if (not done) and ln.strip() == section:
                for b in block_lines[1:]:
                    out.append(b)
                done = True
        if not done:
            out.append("\n" + "".join(block_lines))
        text = "".join(out)
    else:
        if not text.endswith("\n"):
            text += "\n"
        text += "\n" + "".join(block_lines)
    text = ensure_header_and_arch(text, "win64")
    write_utf8(path, text)
    print("dlloverrides mode=%s value=%s dlls=%s" % (mode, value, ",".join(dlls)))
    sys.exit(0)



def _upsert_section(text, section, kv_pairs):
    """Ensure [section] exists and set/replace listed "Key"="Value" or dword lines.
    kv_pairs: list of (key, full_assignment_line_without_newline) e.g. ('"VideoMemorySize"', '"VideoMemorySize"="2048"')
    Keys matched by startswith(key+'=').
    """
    keys = [k for k, _ in kv_pairs]
    lines = []
    for ln in text.splitlines(True):
        s = ln.strip()
        drop = False
        for k in keys:
            if s.startswith(k + "="):
                drop = True
                break
        if not drop:
            lines.append(ln if ln.endswith("\n") else ln + "\n")
    text = "".join(lines)
    assignments = [a if a.endswith("\n") else a + "\n" for _, a in kv_pairs]
    if section in text:
        out = []
        done = False
        for ln in text.splitlines(True):
            out.append(ln if ln.endswith("\n") else ln + "\n")
            if (not done) and ln.strip() == section:
                for a in assignments:
                    out.append(a)
                done = True
        if not done:
            out.append("\n" + section + "\n" + "".join(assignments))
        text = "".join(out)
    else:
        if not text.endswith("\n"):
            text += "\n"
        text += "\n" + section + "\n" + "".join(assignments)
    return text



def cmd_inject_cjk_fonts(prefix):
    """0.1.117 P1-Z: Wine FontSubstitutes + Fonts\\Replacements for Noto Sans CJK.
    Traditional aliases → Noto Sans CJK TC; Simplified aliases → Noto Sans CJK SC.
    Never invent a hive before wineboot.
    """
    tc = "Noto Sans CJK TC"
    sc = "Noto Sans CJK SC"
    hk = "Noto Sans CJK HK"
    # (windows_name, family)
    aliases_tc = [
        "Microsoft JhengHei", "Microsoft JhengHei UI", "微軟正黑體", "微軟正黑體 UI",
        "MingLiU", "PMingLiU", "MingLiU-ExtB", "PMingLiU-ExtB", "MingLiU_HKSCS",
        "新細明體", "細明體", "DFKai-SB", "標楷體",
    ]
    aliases_sc = [
        "SimSun", "NSimSun", "SimSun-ExtB", "宋体", "新宋体",
        "Microsoft YaHei", "Microsoft YaHei UI", "微软雅黑",
        "SimHei", "黑体", "KaiTi", "楷体", "FangSong", "仿宋", "DengXian", "等线",
    ]
    # HKSCS better on HK face
    alias_pairs = [(n, tc) for n in aliases_tc] + [(n, sc) for n in aliases_sc]
    alias_pairs = [(n, hk) if n == "MingLiU_HKSCS" else (n, fam) for n, fam in alias_pairs]

    def _pairs(family_value=True):
        out = []
        for name, fam in alias_pairs:
            key = '"%s"' % name
            line = '"%s"="%s"' % (name, fam)
            out.append((key, line))
        return out

    user = os.path.join(prefix, "user.reg")
    system = os.path.join(prefix, "system.reg")
    info_u = detect(user)
    info_s = detect(system)
    if not info_u["exists"] and not info_s["exists"]:
        print("cjkfonts skip regs=missing need_wineboot=1")
        sys.exit(3)
    wrote = []
    if info_u["exists"] and info_u["valid"] and info_u["text"] is not None:
        text = ensure_header_and_arch(info_u["text"], "win64")
        text = _upsert_section(text, "[Software\\\\Wine\\\\Fonts\\\\Replacements]", _pairs())
        write_utf8(user, text)
        wrote.append("user.replacements")
    if info_s["exists"] and info_s["valid"] and info_s["text"] is not None:
        text = ensure_header_and_arch(info_s["text"], "win64")
        text = _upsert_section(text, "[Software\\\\Microsoft\\\\Windows NT\\\\CurrentVersion\\\\FontSubstitutes]", _pairs())
        font_lines = [
            ('"Noto Sans CJK TC (TrueType)"', '"Noto Sans CJK TC (TrueType)"="NotoSansCJK-Regular.ttc"'),
            ('"Noto Sans CJK SC (TrueType)"', '"Noto Sans CJK SC (TrueType)"="NotoSansCJK-Regular.ttc"'),
            ('"Noto Sans CJK HK (TrueType)"', '"Noto Sans CJK HK (TrueType)"="NotoSansCJK-Regular.ttc"'),
            ('"Noto Sans CJK JP (TrueType)"', '"Noto Sans CJK JP (TrueType)"="NotoSansCJK-Regular.ttc"'),
            ('"Noto Sans CJK KR (TrueType)"', '"Noto Sans CJK KR (TrueType)"="NotoSansCJK-Regular.ttc"'),
            ('"Noto Sans Mono CJK TC (TrueType)"', '"Noto Sans Mono CJK TC (TrueType)"="NotoSansCJK-Regular.ttc"'),
            ('"Noto Sans Mono CJK SC (TrueType)"', '"Noto Sans Mono CJK SC (TrueType)"="NotoSansCJK-Regular.ttc"'),
        ]
        text = _upsert_section(text, "[Software\\\\Microsoft\\\\Windows NT\\\\CurrentVersion\\\\Fonts]", font_lines)
        write_utf8(system, text)
        wrote.append("system.substitutes")
        wrote.append("system.fonts")
    if not wrote:
        print("cjkfonts skip regs=invalid")
        sys.exit(2)
    print("cjkfonts wrote=%s family=Noto Sans CJK TC aliases=%d ok=1" % (",".join(wrote), len(alias_pairs)))
    sys.exit(0)


def cmd_patch_present_knobs(prefix):
    """0.1.72/74: Wine Direct3D VideoMemorySize/csmt + X11 Driver grab/FS/depth knobs.
    Usage: wine_reg_helper.py patch-present-knobs <prefix>
    0.1.74 adds UseXRandR/UseXVidMode/DXGrab=N, ScreenDepth=32, Decorated=N
    (leftover exclusive-FS / mode-switch on in-process X). Never invent user.reg
    before wineboot.
    """
    path = os.path.join(prefix, "user.reg")
    info = detect(path)
    if not info["exists"] or not info["valid"] or info["text"] is None:
        print("presentknobs skip user.reg ready=0")
        sys.exit(3)
    text = info["text"]
    d3d = "[Software\\\\Wine\\\\Direct3D]"
    text = _upsert_section(text, d3d, [
        ('"VideoMemorySize"', '"VideoMemorySize"="2048"'),
        ('"csmt"', '"csmt"=dword:00000001'),
        ('"EnableCSMT"', '"EnableCSMT"="Y"'),
    ])
    x11 = "[Software\\\\Wine\\\\X11 Driver]"
    text = _upsert_section(text, x11, [
        ('"GrabFullscreen"', '"GrabFullscreen"="N"'),
        ('"Managed"', '"Managed"="Y"'),
        ('"UseTakeFocus"', '"UseTakeFocus"="N"'),
        # 0.1.74: leftover exclusive-FS / mode-switch / depth on in-process X
        ('"UseXRandR"', '"UseXRandR"="N"'),
        ('"UseXVidMode"', '"UseXVidMode"="N"'),
        ('"DXGrab"', '"DXGrab"="N"'),
        ('"ScreenDepth"', '"ScreenDepth"="32"'),
        ('"Decorated"', '"Decorated"="N"'),
    ])
    text = ensure_header_and_arch(text, "win64")
    write_utf8(path, text)
    print("presentknobs VideoMemorySize=2048 csmt=1 GrabFullscreen=N Managed=Y UseTakeFocus=N UseXRandR=N UseXVidMode=N DXGrab=N ScreenDepth=32 Decorated=N")
    sys.exit(0)


def cmd_inject_light_theme(prefix):
    """0.1.118 P1-A: force Wine built-in Light (Aero-Light) visual style.
    Never invent a hive before wineboot. Does not bundle Microsoft theme files.
    """
    user = os.path.join(prefix, "user.reg")
    info = detect(user)
    if not info["exists"] or not info["valid"] or info["text"] is None:
        print("theme skip user.reg ready=0")
        sys.exit(3)
    msstyles = "C:\\\\windows\\\\Resources\\\\Themes\\\\light\\\\light.msstyles"
    theme = "C:\\\\windows\\\\Resources\\\\Themes\\\\light\\\\light.theme"
    text = info["text"]
    text = _upsert_section(text, "[Software\\\\Microsoft\\\\Windows\\\\CurrentVersion\\\\ThemeManager]", [
        ('"ThemeActive"', '"ThemeActive"="1"'),
        ('"ColorName"', '"ColorName"="NormalColor"'),
        ('"SizeName"', '"SizeName"="NormalSize"'),
        ('"DllName"', '"DllName"="%s"' % msstyles),
    ])
    text = _upsert_section(text, "[Software\\\\Microsoft\\\\Windows\\\\CurrentVersion\\\\Themes]", [
        ('"CurrentTheme"', '"CurrentTheme"="%s"' % theme),
        ('"ThemeName"', '"ThemeName"="Light"'),
    ])
    text = _upsert_section(text, "[Software\\\\Microsoft\\\\Windows\\\\CurrentVersion\\\\Themes\\\\Personalize]", [
        ('"AppsUseLightTheme"', '"AppsUseLightTheme"=dword:00000001'),
        ('"SystemUsesLightTheme"', '"SystemUsesLightTheme"=dword:00000001'),
        ('"EnableTransparency"', '"EnableTransparency"=dword:00000000'),
        ('"ColorPrevalence"', '"ColorPrevalence"=dword:00000000'),
    ])
    text = ensure_header_and_arch(text, "win64")
    write_utf8(user, text)
    print("theme style=light active=1 dll=%s ok=1" % msstyles.replace("\\\\", "\\"))
    sys.exit(0)


def cmd_inject_font_smooth(prefix):
    """0.1.118 P1-B: ClearType/fontsmoothing + ban bitmap UI fonts.
    Keeps 0.1.117 CJK FontSubstitutes (upsert only). Never invent hives.
    """
    user = os.path.join(prefix, "user.reg")
    system = os.path.join(prefix, "system.reg")
    info_u = detect(user)
    info_s = detect(system)
    if not info_u["exists"] and not info_s["exists"]:
        print("fontsmooth skip regs=missing need_wineboot=1")
        sys.exit(3)
    wrote = []
    bitmap_pairs = [
        ("MS Shell Dlg", "Tahoma"),
        ("MS Shell Dlg 2", "Tahoma"),
        ("MS Sans Serif", "Tahoma"),
        ("MS Serif", "Times New Roman"),
        ("Courier", "Courier New"),
        ("System", "Tahoma"),
        ("Fixedsys", "Courier New"),
        ("Terminal", "Courier New"),
        ("Small Fonts", "Tahoma"),
        ("Roman", "Times New Roman"),
        ("Script", "Tahoma"),
        ("Modern", "Tahoma"),
    ]
    sub_pairs = [('"%s"' % a, '"%s"="%s"' % (a, b)) for a, b in bitmap_pairs]
    fontlink = [
        ('"Tahoma"', '"Tahoma"="NotoSansCJK-Regular.ttc,Noto Sans CJK TC"'),
        ('"Tahoma Bold"', '"Tahoma Bold"="NotoSansCJK-Regular.ttc,Noto Sans CJK TC"'),
        ('"Microsoft Sans Serif"', '"Microsoft Sans Serif"="NotoSansCJK-Regular.ttc,Noto Sans CJK TC"'),
        ('"Courier New"', '"Courier New"="NotoSansCJK-Regular.ttc,Noto Sans CJK TC"'),
        ('"Times New Roman"', '"Times New Roman"="NotoSansCJK-Regular.ttc,Noto Sans CJK TC"'),
        ('"Segoe UI"', '"Segoe UI"="NotoSansCJK-Regular.ttc,Noto Sans CJK TC"'),
        ('"Noto Sans CJK TC"', '"Noto Sans CJK TC"="NotoSansCJK-Regular.ttc,Noto Sans CJK TC"'),
    ]

    if info_u["exists"] and info_u["valid"] and info_u["text"] is not None:
        text = info_u["text"]
        # FontSmoothing=2 (enabled), Type=2 (ClearType), Orientation=1 (RGB), Gamma=1400
        text = _upsert_section(text, "[Control Panel\\\\Desktop]", [
            ('"FontSmoothing"', '"FontSmoothing"="2"'),
            ('"FontSmoothingType"', '"FontSmoothingType"=dword:00000002'),
            ('"FontSmoothingOrientation"', '"FontSmoothingOrientation"=dword:00000001'),
            ('"FontSmoothingGamma"', '"FontSmoothingGamma"=dword:00000578'),
        ])
        text = _upsert_section(text, "[Software\\\\Wine\\\\X11 Driver]", [
            ('"ClientSideWithRender"', '"ClientSideWithRender"="Y"'),
            ('"ClientSideAntiAliasWithRender"', '"ClientSideAntiAliasWithRender"="Y"'),
            ('"ClientSideAntiAliasWithCore"', '"ClientSideAntiAliasWithCore"="Y"'),
        ])
        text = _upsert_section(text, "[Software\\\\Wine\\\\Fonts\\\\Replacements]", sub_pairs)
        text = ensure_header_and_arch(text, "win64")
        write_utf8(user, text)
        wrote.append("user.desktop")
        wrote.append("user.x11aa")
        wrote.append("user.replacements")
    if info_s["exists"] and info_s["valid"] and info_s["text"] is not None:
        text = info_s["text"]
        text = _upsert_section(text, "[Software\\\\Microsoft\\\\Windows NT\\\\CurrentVersion\\\\FontSubstitutes]", sub_pairs)
        text = _upsert_section(text, "[Software\\\\Microsoft\\\\Windows NT\\\\CurrentVersion\\\\FontLink\\\\SystemLink]", fontlink)
        text = ensure_header_and_arch(text, "win64")
        write_utf8(system, text)
        wrote.append("system.substitutes")
        wrote.append("system.fontlink")
    if not wrote:
        print("fontsmooth skip regs=invalid")
        sys.exit(2)
    print("fontsmooth wrote=%s type=cleartype bitmap_off=1 ok=1" % ",".join(wrote))
    sys.exit(0)


# --- 0.1.118 P1-C: workspace installed-app scan (Start Menu / Uninstall / Program Files) ---

_WS_SKIP_DIRS = {
    "windows", "windows nt", "internet explorer", "windows media player",
    "windows photo viewer", "windowsapps", "common files", "modifiablewindowsapps",
    "microsoft.net", "reference assemblies", "installshield installation information",
    "temp", "tmp", "microsoft",
}
_WS_SKIP_EXE = {
    "notepad.exe", "winecfg.exe", "wordpad.exe", "iexplore.exe", "hh.exe",
    "msiexec.exe", "control.exe", "taskmgr.exe", "regedit.exe", "explorer.exe",
    "cmd.exe", "wineboot.exe", "uninstaller.exe", "winemine.exe", "clock.exe",
    "oleview.exe", "winhlp32.exe", "rundll32.exe", "services.exe", "rpcss.exe",
    "svchost.exe", "conhost.exe", "dwm.exe", "start.exe", "wineconsole.exe",
    "plugplay.exe", "winedevice.exe", "winemenubuilder.exe", "winefile.exe",
    "taskhost.exe", "dllhost.exe", "csrss.exe", "smss.exe", "winlogon.exe",
    "sechealthui.exe", "systemsettings.exe",
}
_WS_SKIP_NAME_PREFIXES = ("unins", "vcredist", "vc_redist", "dxsetup", "installengine")
_WS_SKIP_NAME_SUBSTR = (
    "uninstall", "crashhandler", "crashreport", "updater", "updatehelper",
    "setup_tmp",
)


def _ws_norm(p):
    return (p or "").replace("\\", "/").strip().lower()


def _ws_is_skipped_exe_name(name):
    n = (name or "").lower()
    if n in _WS_SKIP_EXE:
        return True
    if any(n.startswith(pfx) for pfx in _WS_SKIP_NAME_PREFIXES):
        return True
    if any(s in n for s in _WS_SKIP_NAME_SUBSTR):
        return True
    return False


def _ws_windows_to_host(prefix, win_path):
    if not win_path:
        return None
    s = win_path.strip().strip('"')
    # DisplayIcon often has ",0" or ",-1"
    if "," in s and s.lower().endswith((".exe,0", ".exe,-1", ".dll,0", ".ico,0", ".exe,1")):
        s = s.rsplit(",", 1)[0]
    elif re.search(r",-?\d+$", s) and (".exe" in s.lower() or ".dll" in s.lower() or ".ico" in s.lower()):
        s = re.sub(r",-?\d+$", "", s)
    s = s.replace("/", "\\")
    # expand %SystemRoot% / %ProgramFiles%
    def _env(text, pat, val):
        return re.sub(pat, lambda _m: val, text, flags=re.I)
    s = _env(s, r"%SystemRoot%", "C:\\windows")
    s = _env(s, r"%windir%", "C:\\windows")
    s = _env(s, r"%ProgramFiles\(x86\)%", "C:\\Program Files (x86)")
    s = _env(s, r"%ProgramFiles%", "C:\\Program Files")
    m = re.match(r"^[A-Za-z]:\\(.*)$", s)
    if not m:
        if s.startswith("\\") or s.startswith("/"):
            return None
        return None
    rel = m.group(1).replace("\\", os.sep)
    host = os.path.join(prefix, "drive_c", rel)
    return host


def _ws_host_to_windows(prefix, host):
    drive = os.path.join(prefix, "drive_c")
    ap = os.path.abspath(host)
    ad = os.path.abspath(drive)
    if ap == ad or ap.startswith(ad + os.sep):
        rel = ap[len(ad):].lstrip("\\/")
        return "C:\\" + rel.replace("/", "\\")
    return None


def parse_ms_shortcut(path):
    """Parse a Windows Shell Link (.lnk) and return the local target path (Windows path) or None."""
    try:
        with open(path, "rb") as f:
            data = f.read()
    except OSError:
        return None
    if len(data) < 0x4C or data[0:4] != b"L\x00\x00\x00":
        return None
    flags = int.from_bytes(data[0x14:0x18], "little")
    off = 0x4C
    # HasLinkTargetIDList
    if flags & 0x00000001:
        if off + 2 > len(data):
            return None
        id_len = int.from_bytes(data[off:off + 2], "little")
        off += 2 + id_len
    target = None
    # HasLinkInfo
    if flags & 0x00000002:
        if off + 4 > len(data):
            return target
        info_size = int.from_bytes(data[off:off + 4], "little")
        if info_size < 0x1C or off + info_size > len(data):
            return target
        blob = data[off:off + info_size]
        header_size = int.from_bytes(blob[4:8], "little")
        info_flags = int.from_bytes(blob[8:12], "little")
        local_off = int.from_bytes(blob[16:20], "little")
        if info_flags & 0x1 and 0 < local_off < len(blob):
            end = blob.find(b"\x00", local_off)
            if end > local_off:
                try:
                    target = blob[local_off:end].decode("latin-1")
                except Exception:
                    target = None
        if header_size >= 0x24 and len(blob) >= 0x24:
            uni_off = int.from_bytes(blob[0x1C:0x20], "little")
            if 0 < uni_off < len(blob) - 1:
                end = uni_off
                chars = []
                while end + 1 < len(blob):
                    ch = int.from_bytes(blob[end:end + 2], "little")
                    if ch == 0:
                        break
                    chars.append(ch)
                    end += 2
                if chars:
                    try:
                        target = "".join(chr(c) for c in chars)
                    except Exception:
                        pass
        off += info_size
    # StringData: Name, RelativePath, WorkingDir, Arguments, IconLocation
    is_unicode = bool(flags & 0x00000080)

    def _read_str(pos, unicode_flag):
        if pos + 2 > len(data):
            return None, pos
        nchars = int.from_bytes(data[pos:pos + 2], "little")
        pos += 2
        if unicode_flag:
            nbytes = nchars * 2
            if pos + nbytes > len(data):
                return None, pos
            s = data[pos:pos + nbytes].decode("utf-16le", errors="ignore")
            return s, pos + nbytes
        if pos + nchars > len(data):
            return None, pos
        s = data[pos:pos + nchars].decode("latin-1", errors="ignore")
        return s, pos + nchars

    # We already have LocalBasePath; RelativePath may help if LinkInfo was missing.
    if target is None:
        # skip Name if present, then try RelativePath (not a full path usually)
        for bit in (0x4, 0x8, 0x10, 0x20, 0x40):
            if flags & bit:
                s, off = _read_str(off, is_unicode)
                if bit == 0x8 and s and (s.lower().endswith(".exe") or ":" in s):
                    if target is None:
                        target = s
    return target


def _iter_reg_sections(text):
    """Yield (section_name_unescaped, dict of key->raw value string)."""
    if not text:
        return
    current = None
    vals = {}
    for raw in text.splitlines():
        line = raw.strip()
        if line.startswith("["):
            if current is not None:
                yield current, vals
            # [Software\\Microsoft\\... ] 12345
            end = line.find("]")
            if end < 0:
                current = None
                vals = {}
                continue
            current = line[1:end].replace("\\\\", "\\")
            vals = {}
        elif current is not None and line.startswith('"') and "=" in line:
            k, _, v = line.partition("=")
            k = k.strip().strip('"')
            vals[k] = v.strip()
    if current is not None:
        yield current, vals


def _reg_unquote(v):
    if v is None:
        return ""
    v = v.strip()
    if v.startswith('str('):
        # str(2):"...."
        q = v.find('"')
        if q >= 0:
            v = v[q:]
    if len(v) >= 2 and v[0] == '"' and v[-1] == '"':
        v = v[1:-1]
        v = v.replace("\\\\", "\\")
    return v


def _collect_uninstall_exes(prefix):
    found = []
    for hive in ("system.reg", "user.reg"):
        path = os.path.join(prefix, hive)
        info = detect(path)
        if not info["exists"] or not info["valid"] or info["text"] is None:
            continue
        for sect, vals in _iter_reg_sections(info["text"]):
            low = sect.lower()
            if "\\microsoft\\windows\\currentversion\\uninstall\\" not in low:
                continue
            name = _reg_unquote(vals.get("DisplayName") or vals.get("QuietDisplayName") or "")
            icon = _reg_unquote(vals.get("DisplayIcon") or "")
            loc = _reg_unquote(vals.get("InstallLocation") or "")
            host = _ws_windows_to_host(prefix, icon) if icon else None
            if host and os.path.isfile(host) and host.lower().endswith(".exe"):
                found.append((name or os.path.splitext(os.path.basename(host))[0], host, "uninstall"))
                continue
            if loc:
                loc_host = _ws_windows_to_host(prefix, loc)
                if loc_host and os.path.isdir(loc_host):
                    pick = _pick_dir_main_exe(loc_host, name)
                    if pick:
                        found.append((name or os.path.splitext(os.path.basename(pick))[0], pick, "uninstall"))
    return found


def _pick_dir_main_exe(directory, preferred_name=None):
    try:
        entries = [f for f in os.listdir(directory) if f.lower().endswith(".exe")]
    except OSError:
        return None
    cands = []
    for f in entries:
        if _ws_is_skipped_exe_name(f):
            continue
        cands.append(os.path.join(directory, f))
    if not cands:
        return None
    if preferred_name:
        want = re.sub(r"[^a-z0-9]+", "", preferred_name.lower())
        dirname = re.sub(r"[^a-z0-9]+", "", os.path.basename(directory).lower())
        for c in cands:
            stem = re.sub(r"[^a-z0-9]+", "", os.path.splitext(os.path.basename(c))[0].lower())
            if stem and (stem == want or stem == dirname or (dirname and dirname.startswith(stem)) or (stem and stem in want)):
                return c
    # Prefer exe matching folder name
    dirname = os.path.basename(directory).lower()
    for c in cands:
        if os.path.splitext(os.path.basename(c))[0].lower() == dirname:
            return c
    # Prefer non-helper names
    for c in cands:
        n = os.path.basename(c).lower()
        if "helper" not in n and "service" not in n and "broker" not in n:
            return c
    return cands[0]


def _walk_start_menu_lnks(prefix):
    drive = os.path.join(prefix, "drive_c")
    roots = [
        os.path.join(drive, "ProgramData", "Microsoft", "Windows", "Start Menu"),
        os.path.join(drive, "users"),
    ]
    lnks = []
    # users/*/AppData/Roaming/Microsoft/Windows/Start Menu and Desktop
    if os.path.isdir(os.path.join(drive, "users")):
        try:
            for u in os.listdir(os.path.join(drive, "users")):
                base = os.path.join(drive, "users", u)
                roots.append(os.path.join(base, "AppData", "Roaming", "Microsoft", "Windows", "Start Menu"))
                roots.append(os.path.join(base, "Desktop"))
        except OSError:
            pass
    for root in roots:
        if not os.path.isdir(root):
            continue
        for dirpath, dirnames, filenames in os.walk(root):
            # skip windows builtin folders later via exe filter
            for fn in filenames:
                if fn.lower().endswith(".lnk"):
                    lnks.append(os.path.join(dirpath, fn))
    found = []
    for lnk in lnks:
        target = parse_ms_shortcut(lnk)
        if not target:
            continue
        host = _ws_windows_to_host(prefix, target)
        if host is None:
            # relative or already host-like
            if os.path.isabs(target) and os.path.isfile(target):
                host = target
            else:
                continue
        if not os.path.isfile(host) or not host.lower().endswith(".exe"):
            continue
        if _ws_is_skipped_exe_name(os.path.basename(host)):
            continue
        # skip windows\system32 etc.
        rel = _ws_norm(_ws_host_to_windows(prefix, host) or "")
        if "/windows/system32" in rel or "/windows/syswow64" in rel or rel.startswith("c:/windows/"):
            continue
        name = os.path.splitext(os.path.basename(lnk))[0]
        found.append((name, host, "start_menu"))
    return found


def _walk_program_files(prefix):
    drive = os.path.join(prefix, "drive_c")
    found = []
    for pf in ("Program Files", "Program Files (x86)"):
        root = os.path.join(drive, pf)
        if not os.path.isdir(root):
            continue
        try:
            vendors = [d for d in os.listdir(root) if os.path.isdir(os.path.join(root, d))]
        except OSError:
            continue
        for vendor in vendors:
            if vendor.lower() in _WS_SKIP_DIRS:
                continue
            vdir = os.path.join(root, vendor)
            # First try vendor dir itself, then one level of version subdirs
            pick = _pick_dir_main_exe(vdir, vendor)
            if not pick:
                try:
                    subs = [s for s in os.listdir(vdir) if os.path.isdir(os.path.join(vdir, s))]
                except OSError:
                    subs = []
                for s in subs:
                    if s.lower() in _WS_SKIP_DIRS:
                        continue
                    pick = _pick_dir_main_exe(os.path.join(vdir, s), s)
                    if pick:
                        break
            if pick and not _ws_is_skipped_exe_name(os.path.basename(pick)):
                found.append((vendor, pick, "program_files"))
    return found


def scan_prefix_apps(prefix, seed_host=None, seed_name=None):
    """Return list of dicts {name, host, windows, source, seed} de-duplicated by host path."""
    apps = []
    seen = set()

    def add(name, host, source, is_seed=False):
        if not host or not os.path.isfile(host):
            return
        if not host.lower().endswith(".exe"):
            return
        key = os.path.abspath(host).lower()
        if key in seen:
            return
        if (not is_seed) and _ws_is_skipped_exe_name(os.path.basename(host)):
            return
        # skip system32 unless seed
        win = _ws_host_to_windows(prefix, host)
        rel = _ws_norm(win or "")
        if (not is_seed) and (
            "/windows/system32" in rel or "/windows/syswow64" in rel or
            rel.startswith("c:/windows/") or "/drive_c/windows/" in key.replace("\\", "/")
        ):
            return
        # skip the launch-slot copy itself unless marked seed
        if (not is_seed) and rel in ("c:/app/app.exe", "c:\\app\\app.exe"):
            return
        seen.add(key)
        apps.append({
            "name": name or os.path.splitext(os.path.basename(host))[0],
            "host": os.path.abspath(host),
            "windows": win or host,
            "source": source,
            "seed": bool(is_seed),
        })

    have_seed = False
    if seed_host and os.path.isfile(seed_host):
        add(seed_name or os.path.splitext(os.path.basename(seed_host))[0], seed_host, "seed", True)
        have_seed = True
    # Guest seed slot — 0.1.119: do not list C:\App\app.exe as a second seed
    slot = os.path.join(prefix, "drive_c", "App", "app.exe")
    if (not have_seed) and os.path.isfile(slot):
        add(seed_name or "app", slot, "seed", True)

    for name, host, source in _walk_start_menu_lnks(prefix):
        add(name, host, source)
    for name, host, source in _collect_uninstall_exes(prefix):
        add(name, host, source)
    for name, host, source in _walk_program_files(prefix):
        add(name, host, source)
    return apps


def cmd_scan_ws_apps(prefix):
    """0.1.118 P1-C: scan prefix Start Menu / Uninstall / Program Files; stamp WS_APPS."""
    seed = os.environ.get("X86_WS_SEED_EXE", "").strip() or None
    seed_name = os.environ.get("X86_WS_SEED_NAME", "").strip() or None
    apps = scan_prefix_apps(prefix, seed_host=seed, seed_name=seed_name)
    out_dir = os.environ.get("X86_WS_APPS_OUT") or ""
    if not out_dir:
        # /mnt/data when running in guest; else prefix parent
        if os.path.isdir("/mnt/data"):
            out_dir = "/mnt/data"
        else:
            out_dir = prefix
    try:
        os.makedirs(out_dir, exist_ok=True)
        path = os.path.join(out_dir, "ws-apps.json")
        payload = {"count": len(apps), "apps": apps}
        with open(path, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False, indent=2)
    except OSError as e:
        path = "-"
        print("wsapps write_fail=%s" % e)
    def _tag(a):
        if a.get("seed"):
            return "seed"
        src = a.get("source") or ""
        if src == "uninstall":
            return "installed"
        return src
    names = ",".join("%s:%s" % (a["name"].replace(",", " "), _tag(a)) for a in apps) or "-"
    print("WS_APPS count=%d list=%s out=%s" % (len(apps), names, path))
    sys.exit(0)


def main():
    if len(sys.argv) < 3:
        print("usage: wine_reg_helper.py <check|purge-invalid|heal-arch|inject-rpcss|inject-exe-assoc|inject-cjk-fonts|inject-light-theme|inject-font-smooth|scan-ws-apps|patch-graphics|patch-dll-overrides|patch-present-knobs> <prefix> [native|builtin]")
        sys.exit(1)
    cmd, prefix = sys.argv[1], sys.argv[2]
    {
        "check": cmd_check,
        "purge-invalid": cmd_purge_invalid,
        "heal-arch": cmd_heal_arch,
        "inject-rpcss": cmd_inject_rpcss,
        "inject-exe-assoc": cmd_inject_exe_assoc,
        "patch-graphics": cmd_patch_graphics,
        "patch-dll-overrides": cmd_patch_dll_overrides,
        "patch-present-knobs": cmd_patch_present_knobs,
        "inject-cjk-fonts": cmd_inject_cjk_fonts,
        "inject-light-theme": cmd_inject_light_theme,
        "inject-font-smooth": cmd_inject_font_smooth,
        "scan-ws-apps": cmd_scan_ws_apps,
    }[cmd](prefix)

if __name__ == "__main__":
    main()
