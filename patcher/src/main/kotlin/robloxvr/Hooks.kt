package robloxvr

import com.google.gson.JsonParser
import java.io.File

/**
 * Version-independent Java (smali) hooks for the Roblox VR bridge.
 *
 * Every hook is located by Android method names or by the shape of the code, never by the
 * obfuscated names or line numbers of one Roblox build, so the same list works across builds.
 */
class HookError(message: String) : Exception(message)

object Hooks {
    private const val B = "Lcom/phonexr/roblox/PhoneXRBridge;"
    private const val C = "Lcom/phonexr/roblox/ControllerManager;"
    private const val MGA = "Lcom/roblox/client/startup/MainGameActivity;"

    private val M = RegexOption.MULTILINE

    private fun flagsConst(kit: File, which: String): String {
        val root = JsonParser.parseString(File(kit, "flags.json").readText()).asJsonObject
        // JsonObject.toString() is compact and keeps insertion order
        val s = root.getAsJsonObject(which).toString().replace("\"", "\\\"")
        return "    const-string v0, \"$s\"\n"
    }

    private fun methodSpan(text: String, headerRe: String, nth: Int = 0): Pair<Int, Int> {
        val ms = Regex(headerRe, M).findAll(text).toList()
        if (ms.size <= nth) throw HookError("method not found: $headerRe")
        val start = ms[nth].range.first
        val end = text.indexOf("\n.end method", start)
        if (end < 0) throw HookError("unterminated method: $headerRe")
        return start to end + "\n.end method".length
    }

    /** Offset of the first real instruction of the method that begins at [start]. */
    private fun bodyStart(text: String, start: Int): Int {
        var off = text.indexOf('\n', start) + 1
        var skipUntil: String? = null
        while (off < text.length) {
            val nl = text.indexOf('\n', off).let { if (it < 0) text.length else it }
            val ln = text.substring(off, nl)
            val s = ln.trim()
            val next = nl + 1
            val skip = skipUntil
            if (skip != null) {
                if (s.startsWith(skip)) skipUntil = null
                off = next; continue
            }
            if (s.isEmpty() || s.startsWith(".locals") || s.startsWith(".registers") ||
                s.startsWith(".prologue") || s.startsWith(".line")
            ) { off = next; continue }
            if (s.startsWith(".param")) {
                // a .param may open a block closed by ".end param"
                if (next <= text.length && text.substring(minOf(next, text.length)).trimStart(' ').startsWith(".annotation")) {
                    skipUntil = ".end param"
                }
                off = next; continue
            }
            if (s.startsWith(".annotation")) { skipUntil = ".end annotation"; off = next; continue }
            break
        }
        return off
    }

    fun insertAtStart(text: String, headerRe: String, code: String, nth: Int = 0): String {
        val (s, _) = methodSpan(text, headerRe, nth)
        val at = bodyStart(text, s)
        return text.substring(0, at) + code + "\n" + text.substring(at)
    }

    private fun pick(body: String, anchorRe: String, last: Boolean): MatchResult? {
        val ms = Regex(anchorRe).findAll(body).toList()
        return if (last) ms.lastOrNull() else ms.firstOrNull()
    }

    fun insertAfter(text: String, headerRe: String, anchorRe: String, code: String, nth: Int = 0, last: Boolean = false): String {
        val (s, e) = methodSpan(text, headerRe, nth)
        val m = pick(text.substring(s, e), anchorRe, last) ?: throw HookError("anchor not found in $headerRe: $anchorRe")
        val at = s + m.range.last + 1
        return text.substring(0, at) + "\n" + code + text.substring(at)
    }

    fun insertBefore(text: String, headerRe: String, anchorRe: String, code: String, nth: Int = 0, last: Boolean = false): String {
        val (s, e) = methodSpan(text, headerRe, nth)
        val m = pick(text.substring(s, e), anchorRe, last) ?: throw HookError("anchor not found in $headerRe: $anchorRe")
        val at = s + m.range.first
        return text.substring(0, at) + code + "\n" + text.substring(at)
    }

    private fun addMethod(text: String, code: String) = text.trimEnd('\n') + "\n\n" + code

    private fun keyHook(label: String) =
        "    invoke-static {p0, p2}, $C->dispatchKey(Landroid/app/Activity;Landroid/view/KeyEvent;)Z\n" +
            "    move-result v0\n    if-eqz v0, :$label\n    const/4 v0, 0x1\n    return v0\n    :$label\n"

    private fun motionMethod(text: String): String {
        val sup = Regex("^\\.super (L[^;]+;)", M).find(text)?.groupValues?.get(1) ?: throw HookError(".super not found")
        return ".method public dispatchGenericMotionEvent(Landroid/view/MotionEvent;)Z\n    .locals 1\n\n" +
            "    invoke-static {p0, p1}, $C->dispatchMotion(Landroid/app/Activity;Landroid/view/MotionEvent;)Z\n" +
            "    move-result v0\n    if-eqz v0, :pxr_m\n    const/4 v0, 0x1\n    return v0\n    :pxr_m\n" +
            "    invoke-super {p0, p1}, $sup->dispatchGenericMotionEvent(Landroid/view/MotionEvent;)Z\n" +
            "    move-result v0\n    return v0\n.end method\n"
    }

    private fun hdr(name: String, sig: String) = "^\\.method [^\\n]*\\b" + Regex.escape(name) + Regex.escape(sig) + "$"

    private fun lifecycle(text: String, superCallRe: String, bridgeCall: String, name: String) =
        insertAfter(text, hdr(name, "()V"), superCallRe, "    invoke-static {}, $B->$bridgeCall()V\n")

    private fun findSmali(src: File, rel: String): File {
        src.listFiles { f -> f.isDirectory && f.name.startsWith("smali") }?.sortedBy { it.name }?.forEach {
            val p = File(it, rel)
            if (p.isFile) return p
        }
        throw HookError("class not found: $rel")
    }

    private fun findContent(src: File, pattern: String): File {
        val rx = Regex(pattern, M)
        src.listFiles { f -> f.isDirectory && f.name.startsWith("smali") }?.sortedBy { it.name }?.forEach { d ->
            d.walkTopDown().filter { it.isFile && it.extension == "smali" }.forEach { f ->
                if (rx.containsMatchIn(f.readText())) return f
            }
        }
        throw HookError("no class matches $pattern")
    }

    fun applyAll(src: File, kit: File) {
        val flagsMain = flagsConst(kit, "main")
        val flagsLobby = flagsConst(kit, "lobby")

        // ---- MainGameActivity (the GameActivity that owns the engine) ------------------------
        var p = findSmali(src, "com/roblox/client/startup/MainGameActivity.smali")
        var t = p.readText()
        val anchor = Regex(
            "invoke-static/range \\{v4 \\.\\. v9\\}, L[^;]+;->b\\(L[^;]+;Landroidx/lifecycle/s;ZLkotlin/jvm/functions/Function1;ILjava/lang/Object;\\)L[^;]+;\\n" +
                "\\n    invoke-virtual \\{p0\\}, " + Regex.escape(MGA) + "->\\w+\\(\\)V\\n"
        )
        val m = anchor.find(t) ?: throw HookError("MainGameActivity start anchor not found")
        t = t.substring(0, m.range.last + 1) + "\n    invoke-static {p0}, $B->start(Landroid/app/Activity;)V\n" + t.substring(m.range.last + 1)
        // engine flags go to the start of the method that launches the init coroutine (MainGameActivity$b)
        val mk = Regex("new-instance v4, Lcom/roblox/client/startup/MainGameActivity\\\$b;").find(t)
            ?: throw HookError("MainGameActivity init coroutine not found")
        val ms = Regex("^\\.method [^\\n]*$", M).findAll(t).filter { it.range.first < mk.range.first }.last()
        val at = bodyStart(t, ms.range.first)
        t = t.substring(0, at) + flagsMain +
            "    invoke-static {v0}, $MGA->nativePreloadFlagOverrides(Ljava/lang/String;)V\n\n" + t.substring(at)
        t = insertBefore(t, hdr("onDestroy", "()V"), "invoke-super \\{p0\\}, [^\\n]*->onDestroy\\(\\)V", "    invoke-static {}, $B->stop()V\n")
        t = insertAtStart(t, hdr("onKeyDown", "(ILandroid/view/KeyEvent;)Z"), keyHook("pxr_kd"))
        t = insertAtStart(t, hdr("onKeyUp", "(ILandroid/view/KeyEvent;)Z"), keyHook("pxr_ku"))
        t = lifecycle(t, "invoke-super \\{p0\\}, [^\\n]*->onPause\\(\\)V", "pause", "onPause")
        t = lifecycle(t, "invoke-super \\{p0\\}, [^\\n]*->onResume\\(\\)V", "resume", "onResume")
        t = addMethod(t, motionMethod(t))
        p.writeText(t)

        // name of the synthetic accessor of nativePreloadFlagOverrides (used by ActivityNativeMain)
        val accessor = Regex(
            "\\.method public static final synthetic (\\w+)\\(Ljava/lang/String;\\)V\\n    \\.locals 0\\n\\n    invoke-static \\{p0\\}, " +
                Regex.escape(MGA) + "->nativePreloadFlagOverrides"
        ).find(t)?.groupValues?.get(1)

        // ---- ActivityNativeMain (lobby activity) --------------------------------------------
        p = findSmali(src, "com/roblox/client/ActivityNativeMain.smali")
        t = p.readText()
        val supCreate = "invoke-super \\{p0, p1\\}, [^\\n]*->onCreate\\(Landroid/os/Bundle;\\)V"
        val pre = flagsLobby + (if (accessor != null) "    invoke-static {v0}, $MGA->$accessor(Ljava/lang/String;)V\n\n" else "")
        val onCreate = hdr("onCreate", "(Landroid/os/Bundle;)V")
        t = insertBefore(t, onCreate, supCreate, pre, last = true)
        t = insertAfter(t, onCreate, supCreate, "    invoke-static {p0}, $B->start(Landroid/app/Activity;)V\n", last = true)
        t = insertAtStart(t, hdr("onDestroy", "()V"), "    invoke-static {}, $B->stop()V\n")
        t = insertAtStart(t, hdr("onKeyDown", "(ILandroid/view/KeyEvent;)Z"), keyHook("pxr_kd"))
        t = insertAtStart(t, hdr("onKeyUp", "(ILandroid/view/KeyEvent;)Z"), keyHook("pxr_ku"))
        t = lifecycle(t, "invoke-super \\{p0\\}, [^\\n]*->onPause\\(\\)V", "pause", "onPause")
        t = lifecycle(t, "invoke-super \\{p0\\}, [^\\n]*->onResume\\(\\)V", "resume", "onResume")
        t = addMethod(t, motionMethod(t))
        p.writeText(t)

        // ---- game loaded / left callbacks ---------------------------------------------------
        p = findSmali(src, "com/roblox/engine/jni/NativeGLJavaInterface.smali")
        t = p.readText()
        t = insertAtStart(t, hdr("gameDidLeave", "()V"), "    invoke-static {}, $B->gameLeft()V\n")
        t = insertAtStart(t, hdr("gameLoadedCallback", "(J)V"), "    invoke-static {p0, p1}, $B->gameLoaded(J)V\n")
        p.writeText(t)

        // ---- splash: stereo loading screen --------------------------------------------------
        p = findSmali(src, "com/roblox/client/startup/ActivitySplash.smali")
        t = p.readText()
        val mm = Regex("invoke-virtual \\{p0, v0\\}, [^\\n]*->setContentView\\(I\\)V").find(t)
            ?: throw HookError("ActivitySplash setContentView not found")
        t = t.substring(0, mm.range.last + 1) + "\n    invoke-static {p0}, $B->showSplash(Landroid/app/Activity;)V\n" + t.substring(mm.range.last + 1)
        p.writeText(t)

        // ---- keyboard -----------------------------------------------------------------------
        p = findSmali(src, "com/roblox/client/RbxKeyboard.smali")
        t = p.readText()
        t = insertAtStart(t, "^\\.method public e\\(\\)V$", "    invoke-static {}, $B->onHideKeyboard()V\n")
        p.writeText(t)
        val showSig = "\\.method public static \\w+\\(Landroid/view/View;I\\)V\\n    \\.locals 2\\n\\n    if-eqz p0, :cond_0\\n"
        p = findContent(src, "^$showSig\\n    new-instance v0, Landroid/os/Handler;")
        t = p.readText()
        val sm = Regex("($showSig)").find(t)
        if (sm != null) {
            val end = sm.range.last + 1
            t = t.substring(0, end) + "\n    invoke-static {p0}, $B->onShowKeyboard(Landroid/view/View;)V\n" + t.substring(end)
        }
        p.writeText(t)
    }
}
