package robloxvr

import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import kotlin.system.exitProcess

class PatchError(message: String) : Exception(message)

class Patcher(private val kit: File, private val log: (String) -> Unit = { println(it) }) {
    companion object {
        const val NEW_PKG = "com.samrat.roblox.vr"
        const val OLD_PKG = "com.roblox.client"
        const val STEPS = 9
        val KEYSTORE = File(System.getProperty("user.home"), ".robloxvr.keystore")
    }

    private var path: String = System.getenv("PATH") ?: ""

    private fun step(n: Int, text: String) = log("[$n/$STEPS] $text")

    private fun run(vararg cmd: String): String {
        val pb = ProcessBuilder(*cmd).redirectErrorStream(true)
        pb.environment()["PATH"] = path
        val p = try { pb.start() } catch (e: Exception) { throw PatchError("falha ao executar ${cmd[0]}: ${e.message}") }
        val out = p.inputStream.bufferedReader().readText()
        if (p.waitFor() != 0) throw PatchError("${cmd.take(3).joinToString(" ")} …\n${out.takeLast(1500)}")
        return out
    }

    private fun which(tool: String) = path.split(File.pathSeparator).any { File(it, tool).canExecute() }

    private fun findTools() {
        val home = System.getProperty("user.home")
        val sdk = listOf(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"), "$home/Library/Android/sdk", "$home/Android/Sdk")
            .filterNotNull().firstOrNull { File(it, "build-tools").isDirectory }
        val bt = sdk?.let { File(it, "build-tools").listFiles { f -> f.isDirectory }?.maxWithOrNull(versionOrder) }
        path = listOfNotNull(bt?.path, "/opt/homebrew/bin", "/usr/local/bin", path).joinToString(File.pathSeparator)
        for (t in listOf("apktool", "zipalign", "apksigner", "keytool", "java")) {
            if (!which(t)) throw PatchError("«$t» não encontrado. Instale: brew install apktool openjdk e o Android build-tools (zipalign, apksigner).")
        }
    }

    private val versionOrder = Comparator<File> { a, b ->
        val x = a.name.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.name.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return@Comparator c
        }
        0
    }

    private fun findJar(): File =
        listOf(File(kit, "tools/APKEditor.jar"), File(System.getProperty("user.home"), ".robloxvr/APKEditor.jar")).firstOrNull { it.isFile }
            ?: throw PatchError("APKEditor.jar não encontrado (necessário para juntar split-APK). Rode scripts/fetch_deps.sh")

    private fun isUniversalApk(f: File): Boolean {
        val n = Zips.names(f) ?: return false
        return "AndroidManifest.xml" in n && n.any { it.startsWith("lib/arm64-v8a/libroblox.so") }
    }

    private fun toUniversal(inp: File, work: File): File {
        if (inp.name.lowercase().endsWith(".apk") && isUniversalApk(inp)) return inp
        val x = File(work, "bundle")
        if (inp.isDirectory) inp.copyRecursively(x)
        else {
            x.mkdirs()
            if (Zips.names(inp) == null) throw PatchError("o arquivo não é um APK/arquivo zip válido")
            Zips.extract(inp, x)
        }
        x.walkTopDown().filter { it.isFile && it.extension == "idsig" }.forEach { it.delete() }
        if (x.walkTopDown().none { it.isFile && it.extension == "apk" }) throw PatchError("não há arquivos .apk dentro do pacote")
        val out = File(work, "merged.apk")
        run("java", "-jar", findJar().path, "m", "-i", x.path, "-o", out.path)
        return out
    }

    private fun readVersion(src: File): Int =
        Regex("versionCode:\\s*'?(\\d+)").find(File(src, "apktool.yml").readText())?.groupValues?.get(1)?.toInt() ?: 0

    /** Replaces whole methods (e.g. the forced-upgrade check) with the small stubs shipped in the profile. */
    private fun applyStubs(src: File, pdir: File, profile: com.google.gson.JsonObject) {
        val stubs = profile.getAsJsonArray("stubs") ?: return
        for (el in stubs) {
            val st = el.asJsonObject
            val f = File(src, st["file"].asString)
            val text = f.readText()
            val sig = st["signature"].asString
            val m = Regex(Regex.escape(sig) + ".*?\\n\\.end method\\n", RegexOption.DOT_MATCHES_ALL).find(text)
                ?: throw PatchError("método $sig não encontrado em ${f.name}")
            val body = File(pdir, st["body"].asString).readText()
            f.writeText(text.substring(0, m.range.first) + body + text.substring(m.range.last + 1))
        }
    }

    private fun replaceFirstLiteral(s: String, old: String, new: String): String {
        val i = s.indexOf(old)
        return if (i < 0) s else s.substring(0, i) + new + s.substring(i + old.length)
    }

    internal fun patchManifestText(input: String): String {
        var s = replaceFirstLiteral(input, "package=\"$OLD_PKG\"", "package=\"$NEW_PKG\"")
        // authorities and custom permissions are per-package: rename so it can live beside the original
        s = s.replace(Regex("(android:authorities=\")([^\"]*)(\")")) { it.groupValues[1] + it.groupValues[2].replace(OLD_PKG, NEW_PKG) + it.groupValues[3] }
        s = s.replace(Regex("(android:permission=\")" + Regex.escape(OLD_PKG) + "\\.")) { it.groupValues[1] + NEW_PKG + "." }
        s = s.replace(Regex("(<(?:uses-)?permission android:name=\")" + Regex.escape(OLD_PKG) + "\\.")) { it.groupValues[1] + NEW_PKG + "." }
        Regex("(<application [^>]*?android:label=\")[^\"]*(\")").find(s)?.let {
            s = s.substring(0, it.range.first) + it.groupValues[1] + "Roblox VR" + it.groupValues[2] + s.substring(it.range.last + 1)
        }
        // a split-less build: drop split requirements
        s = s.replace(Regex(" android:requiredSplitTypes=\"[^\"]*\""), "")
        s = s.replace(Regex(" android:splitTypes=\"[^\"]*\""), "")
        s = s.replace(Regex("\\s*<meta-data android:name=\"com\\.android\\.vending\\.splits(?:\\.required)?\"[^>]*/>"), "")
        // start in landscape (headset): the launch screens are otherwise portrait
        s = s.replace(Regex("<activity [^>]*android:name=\"com\\.roblox\\.client\\.(?:startup\\.ActivitySplash|ActivityNativeMain|startup\\.MainGameActivity)\"[^>]*>")) {
            val tag = it.value.replace(Regex(" android:screenOrientation=\"[^\"]*\""), "")
            tag.dropLast(1).trimEnd('/') + " android:screenOrientation=\"sensorLandscape\"" + (if (it.value.endsWith("/>")) "/>" else ">")
        }
        // ARCore (optional 6DoF head position) needs these markers
        if ("android:name=\"com.google.ar.core\"" !in s) {
            s = replaceFirstLiteral(s, "</application>",
                "<meta-data android:name=\"com.google.ar.core\" android:value=\"optional\"/><meta-data android:name=\"com.google.ar.core.min_apk_version\" android:value=\"242040000\"/>" +
                    "<activity android:name=\"com.google.ar.core.InstallActivity\" android:configChanges=\"keyboardHidden|orientation|screenSize\" android:excludeFromRecents=\"true\" android:exported=\"false\" android:launchMode=\"singleTop\" android:theme=\"@android:style/Theme.Material.Light.Dialog.Alert\"/></application>")
            s = if ("<queries>" in s) replaceFirstLiteral(s, "<queries>", "<queries><package android:name=\"com.google.ar.core\"/>")
            else replaceFirstLiteral(s, "<application", "<queries><package android:name=\"com.google.ar.core\"/></queries><application")
        }
        if ("android.hardware.vr.headtracking" !in s) {
            s = replaceFirstLiteral(s, "<application", "<uses-feature android:name=\"android.hardware.vr.headtracking\" android:required=\"false\"/><application")
        }
        return s
    }

    private fun patchManifest(src: File) {
        val p = File(src, "AndroidManifest.xml")
        p.writeText(patchManifestText(p.readText()))
    }

    private fun patchYml(src: File) {
        val p = File(src, "apktool.yml")
        var s = p.readText().replace(Regex("targetSdkVersion: \\d+"), "targetSdkVersion: 29")
        if ("\n- task\n" !in s) s = replaceFirstLiteral(s, "doNotCompress:\n", "doNotCompress:\n- task\n")
        p.writeText(s)
    }

    private fun indexOf(data: ByteArray, pat: ByteArray): Int {
        outer@ for (i in 0..data.size - pat.size) {
            for (j in pat.indices) if (data[i + j] != pat[j]) continue@outer
            return i
        }
        return -1
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    internal fun patchNative(lib: File, profile: com.google.gson.JsonObject) {
        val data = lib.readBytes()
        for ((old, new) in listOf("libOpenMAXAL.so\u0000" to "libphonexrvr.so\u0000", "eglSwapBuffers\u0000" to "pxrSwapBuffers\u0000")) {
            val nb = new.toByteArray(Charsets.ISO_8859_1)
            if (indexOf(data, nb) < 0) {
                val ob = old.toByteArray(Charsets.ISO_8859_1)
                val pos = indexOf(data, ob)
                if (pos < 0) throw PatchError("libroblox.so não contém ${old.dropLast(1)}")
                nb.copyInto(data, pos)
            }
        }
        for (el in profile.getAsJsonArray("patches")) {
            val pt = el.asJsonObject
            val off = pt["off"].asString.removePrefix("0x").toInt(16)
            val before = hex(pt["before"].asString)
            val after = hex(pt["after"].asString)
            val cur = data.copyOfRange(off, off + before.size)
            if (cur.contentEquals(after)) continue
            if (!cur.contentEquals(before)) {
                throw PatchError("libroblox.so não bate com o perfil em 0x${off.toString(16)}: esperado ${pt["before"].asString}, encontrado ${cur.joinToString("") { "%02x".format(it) }}")
            }
            after.copyInto(data, off)
        }
        lib.writeBytes(data)
    }

    fun patch(inp: File, out: File) {
        if (!Credits.intact()) throw PatchError("créditos alterados")
        if (!inp.exists()) throw PatchError("arquivo não encontrado: $inp")
        findTools()
        val work = Files.createTempDirectory("robloxvr-").toFile()
        try {
            step(1, "Preparando um único APK…")
            val apk = toUniversal(inp, work)

            step(2, "Descompilando (apktool)…")
            val src = File(work, "src")
            run("apktool", "d", "-f", apk.path, "-o", src.path)
            val ver = readVersion(src)
            val profilesDir = File(kit, "profiles")
            val pdir = File(profilesDir, ver.toString())
            if (!File(pdir, "profile.json").isFile) {
                val sup = profilesDir.listFiles { f -> File(f, "profile.json").isFile }?.map { it.name }?.sorted().orEmpty()
                throw PatchError("a versão do Roblox $ver ainda não é suportada. Suportadas: ${sup.joinToString(", ")}")
            }
            val profile = JsonParser.parseString(File(pdir, "profile.json").readText()).asJsonObject
            log("    Roblox build $ver")

            step(3, "Injetando hooks no código Java…")
            try { Hooks.applyAll(src, kit) } catch (e: HookError) {
                throw PatchError("não foi possível injetar os hooks nesta versão do Roblox: ${e.message}")
            }
            applyStubs(src, pdir, profile)

            step(4, "Manifest: pacote separado $NEW_PKG…")
            patchManifest(src)
            patchYml(src)

            step(5, "Mantendo só arm64 e adicionando as bibliotecas VR…")
            val libdir = File(src, "lib")
            libdir.listFiles()?.filter { it.name != "arm64-v8a" }?.forEach { it.deleteRecursively() }
            val a64 = File(libdir, "arm64-v8a")
            if (!File(a64, "libroblox.so").isFile) throw PatchError("o pacote não tem libroblox.so arm64")
            val pl = File(kit, "payload")
            File(pl, "lib").listFiles { f -> f.extension == "so" }?.forEach { so ->
                var b = so.name
                if (b.startsWith("libphonexrvr_")) {
                    if (b != profile["lib"].asString) return@forEach
                    b = "libphonexrvr.so"
                }
                so.copyTo(File(a64, b), overwrite = true)
            }
            File(src, "assets").mkdirs()
            File(pl, "assets").listFiles()?.forEach { it.copyTo(File(src, "assets/${it.name}"), overwrite = true) }

            step(6, "Patcheando libroblox.so (VR + renderização Cardboard)…")
            patchNative(File(a64, "libroblox.so"), profile)

            step(7, "Compilando o APK…")
            val unsigned = File(work, "u.apk")
            run("apktool", "b", src.path, "-o", unsigned.path)
            val n = Zips.names(unsigned).orEmpty().count { Regex("classes\\d*\\.dex").matches(it) }
            val dexes = File(pl, "dex").listFiles { f -> f.name.matches(Regex("classes.*\\.dex")) }?.sortedBy { it.name }.orEmpty()
            val withDex = File(work, "d.apk")
            Zips.appendStored(unsigned, withDex, dexes.withIndex().associate { (i, f) -> "classes${n + 1 + i}.dex" to f })

            step(8, "Alinhando e assinando…")
            val aligned = File(work, "a.apk")
            run("zipalign", "-P", "16", "-f", "4", withDex.path, aligned.path)
            if (!KEYSTORE.isFile) {
                run("keytool", "-genkey", "-keystore", KEYSTORE.path, "-alias", "vr", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
                    "-storepass", "android", "-keypass", "android", "-dname", "CN=RobloxVR")
            }
            run("apksigner", "sign", "--ks", KEYSTORE.path, "--ks-pass", "pass:android", "--key-pass", "pass:android", "--out", out.path, aligned.path)

            step(9, "Pronto")
            log("OUT: ${out.path}")
        } finally {
            work.deleteRecursively()
        }
    }
}
