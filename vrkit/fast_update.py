#!/usr/bin/env python3
"""Dev helper: swap bridge dex + libphonexrvr in an already patched APK, re-sign, install. usage: fast_update.py in.apk out.apk"""
import glob, os, subprocess, sys, zipfile, shutil, tempfile
KIT = os.path.dirname(os.path.abspath(__file__)); PL = KIT + "/payload"
src, out = sys.argv[1:3]
os.environ["PATH"] = sorted(glob.glob(os.path.expanduser("~/Library/Android/sdk/build-tools/3*")))[-1] + ":/opt/homebrew/bin:" + os.environ["PATH"]
t = tempfile.mkdtemp(); u = t + "/u.apk"
repl = {"classes4.dex": PL + "/dex/classes.dex", "lib/arm64-v8a/libphonexrvr.so": PL + "/lib/libphonexrvr_3218.so"}
with zipfile.ZipFile(src) as zi, zipfile.ZipFile(u, "w") as zo:
    for it in zi.infolist():
        if it.filename.startswith("META-INF/") and it.filename.endswith((".SF", ".RSA", ".MF")): continue
        data = open(repl[it.filename], "rb").read() if it.filename in repl else zi.read(it.filename)
        zo.writestr(it, data, compress_type=it.compress_type)
subprocess.check_call(["zipalign", "-P", "16", "-f", "4", u, t + "/a.apk"])
subprocess.check_call(["apksigner", "sign", "--ks", os.path.expanduser("~/.robloxvr.keystore"), "--ks-pass", "pass:android", "--key-pass", "pass:android", "--out", out, t + "/a.apk"])
shutil.rmtree(t)
