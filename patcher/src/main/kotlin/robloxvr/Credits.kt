package robloxvr

import java.security.MessageDigest

/** Credit line. Stored encoded + hashed: the patcher refuses to run if it was altered. */
object Credits {
    private val E = intArrayOf(57,19,171,198,18,20,240,228,225,185,154,135,196,218,221,172,166,184,181,165,185,205,217,219,113,104,125,101,127,81)
    private const val H = "1b8293f0fe3ffe07ab3fcbc439c38c86e1e42269bbe97bce7d95b994b23deb5a"

    fun text(): String = String(ByteArray(E.size) { i -> (E[i] xor ((0x5A + i * 7) and 0xFF)).toByte() }, Charsets.UTF_8)

    fun intact(): Boolean = MessageDigest.getInstance("SHA-256").digest(text().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) } == H
}
