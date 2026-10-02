"""Verify the shipping arm64 APK, including R8 output, without Android SDK tools.

Usage: python tools/verify-modern-apk.py path/to/app-arm64-release.apk
"""

import hashlib
from pathlib import Path
import re
import struct
import sys
import zipfile


ENTRY = "de.binarynoise.captiveportalautologin.xposed.CaptivePortalModule"
SCOPES = {
    "system", "com.android.systemui", "com.android.settings",
    "com.android.networkstack", "com.google.android.networkstack",
}
PROPERTIES = {
    "minApiVersion": "102", "targetApiVersion": "102", "staticScope": "true",
    "autoHotReload": "false", "exceptionMode": "protective",
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def dex_classes(data):
    require(data[:4] == b"dex\x0a", "Unsupported DEX format")

    def u32(offset):
        return struct.unpack_from("<I", data, offset)[0]

    strings = []
    for index in range(u32(56)):
        offset = u32(u32(60) + index * 4)
        # Skip the ULEB128 UTF-16 length. Class descriptors use ASCII.
        while data[offset] & 128:
            offset += 1
        offset += 1
        strings.append(data[offset:data.index(0, offset)].decode("utf-8", errors="replace"))
    require(not any("de.robv.android.xposed" in value or "de/robv/android/xposed" in value
                    for value in strings), "Legacy Xposed reference in DEX strings")
    types = [strings[u32(u32(68) + index * 4)] for index in range(u32(64))]
    for index in range(u32(96)):
        offset = u32(100) + index * 32
        superclass = u32(offset + 8)
        yield types[u32(offset)], None if superclass == 0xFFFFFFFF else types[superclass]


def verify(path):
    with zipfile.ZipFile(path) as apk:
        names = apk.namelist()
        require("assets/xposed_init" not in names, "Legacy registration is still packaged")
        base = "META-INF/xposed/"
        for filename in ("java_init.list", "scope.list", "module.prop"):
            require(names.count(base + filename) == 1, f"Missing or duplicate {filename}")
        require(apk.read(base + "java_init.list").decode().splitlines() == [ENTRY], "Unexpected entry point")
        scope_lines = apk.read(base + "scope.list").decode().splitlines()
        require(set(scope_lines) == SCOPES and len(scope_lines) == len(SCOPES), "Unexpected scope")
        props = dict(line.split("=", 1) for line in apk.read(base + "module.prop").decode().splitlines()
                     if line and not line.startswith("#"))
        require(props == PROPERTIES, f"Unexpected module properties: {props}")
        dex_names = [name for name in names if re.fullmatch(r"classes\d*\.dex", name)]
        require(dex_names, "No DEX files")
        classes = {}
        for name in dex_names:
            for descriptor, parent in dex_classes(apk.read(name)):
                require(not descriptor.startswith("Lio/github/libxposed/api/"),
                        f"compileOnly API class bundled: {descriptor}")
                require(descriptor not in classes, f"Duplicate class: {descriptor}")
                classes[descriptor] = parent
        require(classes.get("L" + ENTRY.replace(".", "/") + ";") == "Lio/github/libxposed/api/XposedModule;",
                "Exact modern entry class or XposedModule superclass missing after R8")
        abis = {name.split("/")[1] for name in names if name.startswith("lib/") and name.endswith(".so")}
        require(abis == {"arm64-v8a"}, f"Expected only arm64-v8a, got {abis}")
        print(f"PASS: {path.resolve()}")
        print(f"Modern metadata; exact entry; {len(dex_names)} DEX files; {len(classes)} classes; arm64-v8a")
        print("No legacy runtime references/registration; no bundled libxposed API classes")
        print("SHA-256: " + hashlib.file_digest(path.open("rb"), "sha256").hexdigest())


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    try:
        verify(Path(sys.argv[1]))
    except (ValueError, KeyError, zipfile.BadZipFile) as error:
        raise SystemExit(f"FAIL: {error}") from error
