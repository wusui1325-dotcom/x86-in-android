#!/usr/bin/env python3
"""Wine registry offline helpers for x86-in-android.
Wine 11 (Kron4ek) writes UTF-8 text with header 'WINE REGISTRY Version 2'
(see server/registry.c fprintf). Windows .reg UTF-16LE is NOT Wine's format.
Invalid/missing header → Wine prints 'not a valid registry file' and defaults
prefix_type to PREFIX_32BIT.
"""
import os, sys, re

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

def main():
    if len(sys.argv) < 3:
        print("usage: wine_reg_helper.py <check|purge-invalid|heal-arch|inject-rpcss|inject-exe-assoc|inject-cjk-fonts|patch-graphics|patch-dll-overrides|patch-present-knobs> <prefix> [native|builtin]")
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
    }[cmd](prefix)

if __name__ == "__main__":
    main()
