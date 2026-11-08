#!/usr/bin/env bash
# 自動 bump versionCode；versionName patch +1（可 X86_BUMP_NAME=minor|major|none）
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VF="$ROOT/version.json"
python3 - <<'PY' "$VF" "${X86_BUMP_NAME:-patch}"
import json, sys, pathlib
path = pathlib.Path(sys.argv[1])
mode = sys.argv[2]
data = json.loads(path.read_text())
old_code = int(data["versionCode"])
old_name = data["versionName"]
parts = [int(x) for x in old_name.split(".")]
while len(parts) < 3:
    parts.append(0)
if mode == "major":
    parts = [parts[0] + 1, 0, 0]
elif mode == "minor":
    parts = [parts[0], parts[1] + 1, 0]
elif mode == "none":
    pass
else:  # patch
    parts[2] += 1
data["versionCode"] = old_code + 1
data["versionName"] = ".".join(str(p) for p in parts)
notes = data.get("notes", "")
data["notes"] = f"{data['versionName']}: auto-bump from {old_name} (code {old_code}->{data['versionCode']})"
path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
print(f"bumped {old_name} ({old_code}) -> {data['versionName']} ({data['versionCode']})")
PY
