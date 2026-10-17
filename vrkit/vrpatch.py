#!/usr/bin/env python3
"""Roblox VR patcher: turns a stock Roblox package (apk / apkm / xapk / apks / zip)
into one installable APK (package com.samrat.roblox.vr) with the Cardboard VR bridge.

usage: vrpatch.py INPUT OUTPUT.apk
"""
import glob
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile

KIT = os.path.dirname(os.path.abspath(__file__))
NEW_PKG = "com.samrat.roblox.vr"
OLD_PKG = "com.roblox.client"
KEYSTORE = os.path.expanduser("~/.robloxvr.keystore")
STEPS = 9


def step(n, text):
    print(f"[{n}/{STEPS}] {text}", flush=True)


def die(msg):
    print("ОШИБКА: " + msg, flush=True)
    sys.exit(1)


def run(cmd, **kw):
    r = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, **kw)
    if r.returncode != 0:
        die(f"{' '.join(cmd[:3])} …\n{r.stdout[-1500:]}")
    return r.stdout


def find_tools():
    sdk = os.environ.get("ANDROID_HOME") or os.path.expanduser("~/Library/Android/sdk")
    bt = sorted(glob.glob(os.path.join(sdk, "build-tools", "*")), key=lambda p: [int(x) if x.isdigit() else 0 for x in re.split(r"[.-]", os.path.basename(p))])
    extra = [bt[-1]] if bt else []
    os.environ["PATH"] = ":".join(extra + ["/opt/homebrew/bin", "/usr/local/bin", os.environ.get("PATH", "")])
    for t in ("apktool", "zipalign", "apksigner", "keytool", "java"):
        if not shutil.which(t):
            die(f"не найден «{t}». Установите: brew install apktool openjdk, и Android build-tools (zipalign, apksigner).")


def find_jar():
    for p in (os.path.join(KIT, "tools", "APKEditor.jar"), os.path.expanduser("~/.robloxvr/APKEditor.jar")):
        if os.path.isfile(p):
            return p
    die("нет APKEditor.jar (нужен для склейки split-APK)")


def is_universal_apk(path):
    try:
        with zipfile.ZipFile(path) as z:
            names = z.namelist()
    except zipfile.BadZipFile:
        return False
    return "AndroidManifest.xml" in names and any(n.startswith("lib/arm64-v8a/libroblox.so") for n in names)


def to_universal(inp, work):
    """Returns path of one APK that contains the whole app."""
    if inp.lower().endswith(".apk") and is_universal_apk(inp):
        return inp
    x = os.path.join(work, "bundle")
    if os.path.isdir(inp):
        shutil.copytree(inp, x)
    else:
        os.makedirs(x)
        try:
            with zipfile.ZipFile(inp) as z:
                z.extractall(x)
        except zipfile.BadZipFile:
            die("файл не архив/APK")
    for f in glob.glob(os.path.join(x, "**", "*.idsig"), recursive=True):
        os.remove(f)
    if not glob.glob(os.path.join(x, "**", "*.apk"), recursive=True):
        die("внутри нет .apk файлов")
    out = os.path.join(work, "merged.apk")
    run(["java", "-jar", find_jar(), "m", "-i", x, "-o", out])
    return out


def read_version(src):
    y = open(os.path.join(src, "apktool.yml")).read()
    m = re.search(r"versionCode:\s*'?(\d+)", y)
    return int(m.group(1)) if m else 0


def apply_stubs(src, pdir, profile):
    """Replaces whole methods (e.g. the forced-upgrade check) with the small stubs shipped in the profile."""
    for st in profile.get("stubs", []):
        path = os.path.join(src, st["file"])
        text = open(path, encoding="utf-8").read()
        m = re.search(re.escape(st["signature"]) + r".*?\n\.end method\n", text, re.S)
        if not m:
            die(f"не найден метод {st['signature']} в {st['file']}")
        body = open(os.path.join(pdir, st["body"]), encoding="utf-8").read()
        open(path, "w", encoding="utf-8").write(text[:m.start()] + body + text[m.end():])


def patch_manifest(src):
    p = os.path.join(src, "AndroidManifest.xml")
    s = open(p, encoding="utf-8").read()
    s = s.replace(f'package="{OLD_PKG}"', f'package="{NEW_PKG}"', 1)
    # authorities and custom permissions are per-package: rename so it can live beside the original
    s = re.sub(r'(android:authorities=")([^"]*)(")', lambda m: m.group(1) + m.group(2).replace(OLD_PKG, NEW_PKG) + m.group(3), s)
    s = re.sub(r'(android:permission=")' + re.escape(OLD_PKG) + r'\.', r"\1" + NEW_PKG + ".", s)
    s = re.sub(r'(<(?:uses-)?permission android:name=")' + re.escape(OLD_PKG) + r'\.', r"\1" + NEW_PKG + ".", s)
    s = re.sub(r'(<application [^>]*?android:label=")[^"]*(")', r"\1Roblox VR\2", s, count=1)
    # a split-less build: drop split requirements
    s = re.sub(r' android:requiredSplitTypes="[^"]*"', "", s)
    s = re.sub(r' android:splitTypes="[^"]*"', "", s)
    s = re.sub(r'\s*<meta-data android:name="com\.android\.vending\.splits(?:\.required)?"[^>]*/>', "", s)
    # start in landscape (headset): the launch screens are otherwise portrait
    def _landscape(m):
        tag = re.sub(r' android:screenOrientation="[^"]*"', "", m.group(0))
        return tag[:-1].rstrip("/") + ' android:screenOrientation="sensorLandscape"' + ("/>" if m.group(0).endswith("/>") else ">")
    s = re.sub(r'<activity [^>]*android:name="com\.roblox\.client\.(?:startup\.ActivitySplash|ActivityNativeMain|startup\.MainGameActivity)"[^>]*>', _landscape, s)
    if "android.hardware.vr.headtracking" not in s:
        s = s.replace("<application", '<uses-feature android:name="android.hardware.vr.headtracking" android:required="false"/><application', 1)
    open(p, "w", encoding="utf-8").write(s)


def patch_yml(src):
    p = os.path.join(src, "apktool.yml")
    s = open(p).read()
    s = re.sub(r"targetSdkVersion: \d+", "targetSdkVersion: 29", s)
    if "\n- task\n" not in s:
        s = s.replace("doNotCompress:\n", "doNotCompress:\n- task\n", 1)
    open(p, "w").write(s)


def patch_native(lib, profile):
    data = bytearray(open(lib, "rb").read())
    for old, new in ((b"libOpenMAXAL.so\0", b"libphonexrvr.so\0"), (b"eglSwapBuffers\0", b"pxrSwapBuffers\0")):
        if new not in data:
            pos = data.find(old)
            if pos < 0:
                die(f"в libroblox.so нет {old[:-1].decode()}")
            data[pos:pos + len(old)] = new
    for pt in profile["patches"]:
        off, before, after = int(pt["off"], 16), bytes.fromhex(pt["before"]), bytes.fromhex(pt["after"])
        cur = bytes(data[off:off + len(before)])
        if cur == after:
            continue
        if cur != before:
            die(f"libroblox.so не совпадает с профилем в 0x{off:x}: ждали {before.hex()}, а там {cur.hex()}")
        data[off:off + len(after)] = after
    open(lib, "wb").write(data)


def main():
    if len(sys.argv) != 3:
        die("usage: vrpatch.py INPUT OUTPUT.apk")
    inp, out = os.path.abspath(sys.argv[1]), os.path.abspath(sys.argv[2])
    if not os.path.exists(inp):
        die(f"нет файла {inp}")
    find_tools()
    work = tempfile.mkdtemp(prefix="robloxvr-")
    try:
        step(1, "Готовлю единый APK…")
        apk = to_universal(inp, work)

        step(2, "Распаковываю (apktool)…")
        src = os.path.join(work, "src")
        run(["apktool", "d", "-f", apk, "-o", src])
        ver = read_version(src)
        pdir = os.path.join(KIT, "profiles", str(ver))
        if not os.path.isdir(pdir):
            sup = sorted(os.listdir(os.path.join(KIT, "profiles")))
            die(f"версия Roblox {ver} пока не поддерживается. Поддерживаются: {', '.join(sup)}")
        profile = json.load(open(os.path.join(pdir, "profile.json")))
        print(f"    Roblox build {ver}", flush=True)

        step(3, "Вшиваю хуки в Java-код…")
        run(["patch", "-p1", "--no-backup-if-mismatch", "-d", src, "-i", os.path.join(pdir, "smali.patch")])
        apply_stubs(src, pdir, profile)

        step(4, "Манифест: отдельный пакет com.samrat.roblox.vr…")
        patch_manifest(src)
        patch_yml(src)

        step(5, "Оставляю только arm64, кладу библиотеки VR…")
        libdir = os.path.join(src, "lib")
        for d in glob.glob(os.path.join(libdir, "*")):
            if os.path.basename(d) != "arm64-v8a":
                shutil.rmtree(d)
        a64 = os.path.join(libdir, "arm64-v8a")
        if not os.path.isfile(os.path.join(a64, "libroblox.so")):
            die("в пакете нет arm64 libroblox.so")
        pl = os.path.join(KIT, "payload")
        for so in glob.glob(os.path.join(pl, "lib", "*.so")):
            b = os.path.basename(so)
            if b.startswith("libphonexrvr_"):
                if b != profile["lib"]:
                    continue
                b = "libphonexrvr.so"
            shutil.copy(so, os.path.join(a64, b))
        os.makedirs(os.path.join(src, "assets"), exist_ok=True)
        for f in glob.glob(os.path.join(pl, "assets", "*")):
            shutil.copy(f, os.path.join(src, "assets"))

        step(6, "Патчу libroblox.so (VR + Cardboard-рендер)…")
        patch_native(os.path.join(a64, "libroblox.so"), profile)

        step(7, "Собираю APK…")
        unsigned = os.path.join(work, "u.apk")
        run(["apktool", "b", src, "-o", unsigned])
        with zipfile.ZipFile(unsigned) as z:
            n = len([x for x in z.namelist() if re.fullmatch(r"classes\d*\.dex", x)])
        with zipfile.ZipFile(unsigned, "a") as z:
            for i, dex in enumerate(sorted(glob.glob(os.path.join(pl, "dex", "classes*.dex")))):
                z.write(dex, f"classes{n + 1 + i}.dex", compress_type=zipfile.ZIP_STORED)

        step(8, "Выравниваю и подписываю…")
        aligned = os.path.join(work, "a.apk")
        run(["zipalign", "-P", "16", "-f", "4", unsigned, aligned])
        if not os.path.isfile(KEYSTORE):
            run(["keytool", "-genkey", "-keystore", KEYSTORE, "-alias", "vr", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
                 "-storepass", "android", "-keypass", "android", "-dname", "CN=RobloxVR"])
        run(["apksigner", "sign", "--ks", KEYSTORE, "--ks-pass", "pass:android", "--key-pass", "pass:android", "--out", out, aligned])

        step(9, "Готово")
        print(f"OUT: {out}", flush=True)
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    main()
