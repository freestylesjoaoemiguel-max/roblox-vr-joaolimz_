"""Version-independent Java hooks for the Roblox VR bridge.

Every hook is located by Android method names or by the shape of the code, never by the
obfuscated names or line numbers of one Roblox build, so the same list works across builds.
"""
import glob
import json
import os
import re

B = "Lcom/phonexr/roblox/PhoneXRBridge;"
C = "Lcom/phonexr/roblox/ControllerManager;"
MGA = "Lcom/roblox/client/startup/MainGameActivity;"


class HookError(Exception):
    pass


def _flags_const(kit, which):
    flags = json.load(open(os.path.join(kit, "flags.json")))[which]
    s = json.dumps(flags, separators=(",", ":")).replace('"', '\\"')
    return f'    const-string v0, "{s}"\n'


def _method_span(text, header_re, nth=0):
    ms = list(re.finditer(header_re, text, re.M))
    if len(ms) <= nth:
        raise HookError(f"method not found: {header_re}")
    start = ms[nth].start()
    end = text.index("\n.end method", start) + len("\n.end method")
    return start, end


def _body_start(text, start):
    """Offset of the first real instruction of the method that begins at `start`."""
    pos = text.index("\n", start) + 1
    lines = text[pos:].split("\n")
    off = pos
    skip_until = None
    for ln in lines:
        s = ln.strip()
        if skip_until:
            if s.startswith(skip_until):
                skip_until = None
            off += len(ln) + 1
            continue
        if s == "" or s.startswith(".locals") or s.startswith(".registers") or s.startswith(".prologue") or s.startswith(".line"):
            off += len(ln) + 1
            continue
        if s.startswith(".param"):
            if "\n" in ln or s.endswith(".end param"):
                pass
            # a .param may open a block closed by ".end param"
            nxt = text[off + len(ln) + 1:].lstrip(" ")
            if nxt.startswith(".annotation"):
                skip_until = ".end param"
            off += len(ln) + 1
            continue
        if s.startswith(".annotation"):
            skip_until = ".end annotation"
            off += len(ln) + 1
            continue
        break
    return off


def insert_at_start(text, header_re, code, nth=0):
    s, e = _method_span(text, header_re, nth)
    at = _body_start(text, s)
    return text[:at] + code + "\n" + text[at:]


def _pick(body, anchor_re, last):
    ms = list(re.finditer(anchor_re, body))
    if not ms:
        return None
    return ms[-1] if last else ms[0]


def insert_after(text, header_re, anchor_re, code, nth=0, last=False):
    s, e = _method_span(text, header_re, nth)
    body = text[s:e]
    m = _pick(body, anchor_re, last)
    if not m:
        raise HookError(f"anchor not found in {header_re}: {anchor_re}")
    at = s + m.end()
    return text[:at] + "\n" + code + text[at:]


def insert_before(text, header_re, anchor_re, code, nth=0, last=False):
    s, e = _method_span(text, header_re, nth)
    body = text[s:e]
    m = _pick(body, anchor_re, last)
    if not m:
        raise HookError(f"anchor not found in {header_re}: {anchor_re}")
    at = s + m.start()
    return text[:at] + code + "\n" + text[at:]


def add_method(text, code):
    return text.rstrip("\n") + "\n\n" + code


def _key_hook(label):
    return (f"    invoke-static {{p0, p2}}, {C}->dispatchKey(Landroid/app/Activity;Landroid/view/KeyEvent;)Z\n"
            f"    move-result v0\n    if-eqz v0, :{label}\n    const/4 v0, 0x1\n    return v0\n    :{label}\n")


def _motion_method(text):
    sup = re.search(r"^\.super (L[^;]+;)", text, re.M).group(1)
    return (".method public dispatchGenericMotionEvent(Landroid/view/MotionEvent;)Z\n    .locals 1\n\n"
            f"    invoke-static {{p0, p1}}, {C}->dispatchMotion(Landroid/app/Activity;Landroid/view/MotionEvent;)Z\n"
            "    move-result v0\n    if-eqz v0, :pxr_m\n    const/4 v0, 0x1\n    return v0\n    :pxr_m\n"
            f"    invoke-super {{p0, p1}}, {sup}->dispatchGenericMotionEvent(Landroid/view/MotionEvent;)Z\n"
            "    move-result v0\n    return v0\n.end method\n")


def _hdr(name, sig):
    return r"^\.method [^\n]*\b" + re.escape(name) + re.escape(sig) + r"$"


def _lifecycle(text, super_call_re, bridge_call, name):
    return insert_after(text, _hdr(name, "()V"), super_call_re, f"    invoke-static {{}}, {B}->{bridge_call}()V\n")


def _read(path):
    return open(path, encoding="utf-8").read()


def _write(path, text):
    open(path, "w", encoding="utf-8").write(text)


def _find_smali(src, rel):
    for d in sorted(glob.glob(os.path.join(src, "smali*"))):
        p = os.path.join(d, rel)
        if os.path.isfile(p):
            return p
    raise HookError(f"class not found: {rel}")


def _find_content(src, pattern):
    rx = re.compile(pattern, re.M)
    for f in glob.glob(os.path.join(src, "smali*", "**", "*.smali"), recursive=True):
        if rx.search(open(f, encoding="utf-8", errors="ignore").read()):
            return f
    raise HookError(f"no class matches {pattern}")


def apply_all(src, kit):
    flags_main = _flags_const(kit, "main")
    flags_lobby = _flags_const(kit, "lobby")

    # ---- MainGameActivity (the GameActivity that owns the engine) -------------------------
    p = _find_smali(src, "com/roblox/client/startup/MainGameActivity.smali")
    t = _read(p)
    anchor = (r"invoke-static/range \{v4 \.\. v9\}, L[^;]+;->b\(L[^;]+;Landroidx/lifecycle/s;ZLkotlin/jvm/functions/Function1;ILjava/lang/Object;\)L[^;]+;\n"
              r"\n    invoke-virtual \{p0\}, " + re.escape(MGA) + r"->\w+\(\)V\n")
    m = re.search(anchor, t)
    if not m:
        raise HookError("MainGameActivity start anchor not found")
    t = t[:m.end()] + f"\n    invoke-static {{p0}}, {B}->start(Landroid/app/Activity;)V\n" + t[m.end():]
    # engine flags go to the start of the method that launches the init coroutine (MainGameActivity$b)
    mk = re.search(r"new-instance v4, Lcom/roblox/client/startup/MainGameActivity\$b;", t)
    if not mk:
        raise HookError("MainGameActivity init coroutine not found")
    ms = [x for x in re.finditer(r"^\.method [^\n]*$", t, re.M) if x.start() < mk.start()][-1]
    at = _body_start(t, ms.start())
    t = (t[:at] + flags_main + f"    invoke-static {{v0}}, {MGA}->nativePreloadFlagOverrides(Ljava/lang/String;)V\n\n" + t[at:])
    t = insert_before(t, _hdr("onDestroy", "()V"), r"invoke-super \{p0\}, [^\n]*->onDestroy\(\)V", f"    invoke-static {{}}, {B}->stop()V\n")
    t = insert_at_start(t, _hdr("onKeyDown", "(ILandroid/view/KeyEvent;)Z"), _key_hook("pxr_kd"))
    t = insert_at_start(t, _hdr("onKeyUp", "(ILandroid/view/KeyEvent;)Z"), _key_hook("pxr_ku"))
    t = _lifecycle(t, r"invoke-super \{p0\}, [^\n]*->onPause\(\)V", "pause", "onPause")
    t = _lifecycle(t, r"invoke-super \{p0\}, [^\n]*->onResume\(\)V", "resume", "onResume")
    t = add_method(t, _motion_method(t))
    _write(p, t)

    # name of the synthetic accessor of nativePreloadFlagOverrides (used by ActivityNativeMain)
    am = re.search(r"\.method public static final synthetic (\w+)\(Ljava/lang/String;\)V\n    \.locals 0\n\n    invoke-static \{p0\}, "
                   + re.escape(MGA) + r"->nativePreloadFlagOverrides", t)
    accessor = am.group(1) if am else None

    # ---- ActivityNativeMain (lobby activity) ---------------------------------------------
    p = _find_smali(src, "com/roblox/client/ActivityNativeMain.smali")
    t = _read(p)
    sup_create = r"invoke-super \{p0, p1\}, [^\n]*->onCreate\(Landroid/os/Bundle;\)V"
    pre = flags_lobby + (f"    invoke-static {{v0}}, {MGA}->{accessor}(Ljava/lang/String;)V\n\n" if accessor else "")
    t = insert_before(t, _hdr("onCreate", "(Landroid/os/Bundle;)V"), sup_create, pre, last=True)
    t = insert_after(t, _hdr("onCreate", "(Landroid/os/Bundle;)V"), sup_create, f"    invoke-static {{p0}}, {B}->start(Landroid/app/Activity;)V\n", last=True)
    t = insert_at_start(t, _hdr("onDestroy", "()V"), f"    invoke-static {{}}, {B}->stop()V\n")
    t = insert_at_start(t, _hdr("onKeyDown", "(ILandroid/view/KeyEvent;)Z"), _key_hook("pxr_kd"))
    t = insert_at_start(t, _hdr("onKeyUp", "(ILandroid/view/KeyEvent;)Z"), _key_hook("pxr_ku"))
    t = _lifecycle(t, r"invoke-super \{p0\}, [^\n]*->onPause\(\)V", "pause", "onPause")
    t = _lifecycle(t, r"invoke-super \{p0\}, [^\n]*->onResume\(\)V", "resume", "onResume")
    t = add_method(t, _motion_method(t))
    _write(p, t)

    # ---- game loaded / left callbacks ----------------------------------------------------
    p = _find_smali(src, "com/roblox/engine/jni/NativeGLJavaInterface.smali")
    t = _read(p)
    t = insert_at_start(t, _hdr("gameDidLeave", "()V"), f"    invoke-static {{}}, {B}->gameLeft()V\n")
    t = insert_at_start(t, _hdr("gameLoadedCallback", "(J)V"), f"    invoke-static {{p0, p1}}, {B}->gameLoaded(J)V\n")
    _write(p, t)

    # ---- splash: stereo loading screen -----------------------------------------------------
    p = _find_smali(src, "com/roblox/client/startup/ActivitySplash.smali")
    t = _read(p)
    mm = re.search(r"invoke-virtual \{p0, v0\}, [^\n]*->setContentView\(I\)V", t)
    if not mm:
        raise HookError("ActivitySplash setContentView not found")
    t = t[:mm.end()] + f"\n    invoke-static {{p0}}, {B}->showSplash(Landroid/app/Activity;)V\n" + t[mm.end():]
    _write(p, t)

    # ---- keyboard ----------------------------------------------------------------------------
    p = _find_smali(src, "com/roblox/client/RbxKeyboard.smali")
    t = _read(p)
    t = insert_at_start(t, r"^\.method public e\(\)V$", f"    invoke-static {{}}, {B}->onHideKeyboard()V\n")
    _write(p, t)
    p = _find_content(src, r"^\.method public static \w+\(Landroid/view/View;I\)V\n    \.locals 2\n\n    if-eqz p0, :cond_0\n\n    new-instance v0, Landroid/os/Handler;")
    t = _read(p)
    t = re.sub(r"(\.method public static \w+\(Landroid/view/View;I\)V\n    \.locals 2\n\n    if-eqz p0, :cond_0\n)",
               lambda m: m.group(1) + f"\n    invoke-static {{p0}}, {B}->onShowKeyboard(Landroid/view/View;)V\n", t, count=1)
    _write(p, t)
