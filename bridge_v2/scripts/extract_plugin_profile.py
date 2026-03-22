#!/usr/bin/env python3
import json
import sys
import zipfile
from pathlib import Path

if len(sys.argv) != 2:
    print("usage: extract_plugin_profile.py <plugin.apk>")
    sys.exit(1)

apk = Path(sys.argv[1])
with zipfile.ZipFile(apk) as zf:
    entry = zf.read("META-INF/xposed/java_init.list").decode("utf-8", "ignore").strip().splitlines()[0].strip()
    props = {}
    for line in zf.read("META-INF/xposed/module.prop").decode("utf-8", "ignore").splitlines():
        if "=" in line:
            k, v = line.split("=", 1)
            props[k.strip()] = v.strip()
    scope = zf.read("META-INF/xposed/scope.list").decode("utf-8", "ignore").splitlines()

profile = {
    "targetPluginFileName": apk.name,
    "entryClass": entry,
    "minApiVersion": int(props.get("minApiVersion", "0") or 0),
    "targetApiVersion": int(props.get("targetApiVersion", "0") or 0),
    "targetScope": [x.strip() for x in scope if x.strip()],
}
print(json.dumps(profile, ensure_ascii=False, indent=2))
