package robloxvr

import java.io.File
import kotlin.system.exitProcess

private fun findKit(explicit: String?): File {
    val cands = buildList {
        explicit?.let { add(File(it)) }
        System.getenv("ROBLOXVR_KIT")?.let { add(File(it)) }
        val jarDir = runCatching { File(Patcher::class.java.protectionDomain.codeSource.location.toURI()).parentFile }.getOrNull()
        val cwd = File(System.getProperty("user.dir"))
        listOfNotNull(cwd, jarDir, jarDir?.parentFile, jarDir?.parentFile?.parentFile, jarDir?.parentFile?.parentFile?.parentFile).forEach {
            add(File(it, "vrkit")); add(it)
        }
    }
    return cands.firstOrNull { File(it, "profiles").isDirectory && File(it, "flags.json").isFile }
        ?: run { println("ERRO: pasta vrkit não encontrada (use --kit DIR ou ROBLOXVR_KIT)"); exitProcess(1) }
}

const val CREDITS = "créditos : joaolimz_ - samrat"

fun main(args: Array<String>) {
    System.setOut(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8"))
    var kit: String? = null
    var hooksOnly: String? = null
    val pos = mutableListOf<String>()
    var i = 0
    while (i < args.size) {
        if (args[i] == "--kit" && i + 1 < args.size) { kit = args[++i] }
        else if (args[i] == "--hooks-only" && i + 1 < args.size) { hooksOnly = args[++i] } // dev: apply only the smali hooks to a decoded tree
        else pos += args[i]
        i++
    }
    println(CREDITS)
    hooksOnly?.let {
        try { Hooks.applyAll(File(it), findKit(kit)) } catch (e: HookError) { println("ERRO: ${e.message}"); exitProcess(1) }
        return
    }
    if (pos.size != 2) {
        println("uso: RobloxVRPatcher [--kit DIR_VRKIT] ENTRADA SAIDA.apk")
        exitProcess(2)
    }
    try {
        Patcher(findKit(kit)).patch(File(pos[0]).absoluteFile, File(pos[1]).absoluteFile)
    } catch (e: PatchError) {
        println("ERRO: ${e.message}")
        exitProcess(1)
    }
}
