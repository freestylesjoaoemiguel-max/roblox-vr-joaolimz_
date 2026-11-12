package robloxvr

import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object Zips {
    fun extract(zip: File, dest: File) {
        val root = dest.canonicalFile
        ZipFile(zip).use { z ->
            for (e in z.entries()) {
                val out = File(root, e.name).canonicalFile
                if (!out.path.startsWith(root.path + File.separator)) continue // zip-slip guard
                if (e.isDirectory) { out.mkdirs(); continue }
                out.parentFile.mkdirs()
                z.getInputStream(e).use { i -> out.outputStream().use { i.copyTo(it) } }
            }
        }
    }

    fun names(zip: File): List<String>? = try {
        ZipFile(zip).use { z -> z.entries().asSequence().map { it.name }.toList() }
    } catch (e: Exception) { null }

    /** Copies [src] to [dst] and appends [extra] (entry name -> file) as STORED (uncompressed) entries. */
    fun appendStored(src: File, dst: File, extra: Map<String, File>) {
        ZipFile(src).use { zi ->
            ZipOutputStream(dst.outputStream().buffered()).use { zo ->
                for (e in zi.entries()) {
                    val ne = ZipEntry(e.name)
                    ne.method = e.method
                    if (e.time != -1L) ne.time = e.time
                    if (e.method == ZipEntry.STORED) {
                        ne.size = e.size; ne.compressedSize = e.size; ne.crc = e.crc
                    }
                    zo.putNextEntry(ne)
                    zi.getInputStream(e).use { it.copyTo(zo) }
                    zo.closeEntry()
                }
                for ((name, f) in extra) {
                    val bytes = f.readBytes()
                    val ne = ZipEntry(name)
                    ne.method = ZipEntry.STORED
                    ne.size = bytes.size.toLong(); ne.compressedSize = ne.size
                    ne.crc = CRC32().also { it.update(bytes) }.value
                    zo.putNextEntry(ne)
                    zo.write(bytes)
                    zo.closeEntry()
                }
            }
        }
    }
}
