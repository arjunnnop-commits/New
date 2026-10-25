package com.yooitachiijod.glassfastboot

import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipInputStream

/** One file inside a zip, taken from the central directory (ZIP64 aware, so images > 4 GB are fine). */
class ZEntry(val name: String, val method: Int, val csize: Long, val usize: Long, val localOff: Long)

/**
 * Random-access zip reader. Lists every entry WITHOUT unpacking anything and can unpack a single
 * entry on demand. This replaces sequential ZipInputStream reading, which can stop early or fail
 * after huge entries such as super.img / system.img (data descriptors + ZIP64).
 */
object ZipIndex {
    private fun u16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int): Long = u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)
    private fun u64(b: ByteArray, o: Int): Long = u32(b, o) or (u32(b, o + 4) shl 32)

    private fun readAt(ch: FileChannel, pos: Long, len: Int): ByteArray {
        val bb = ByteBuffer.allocate(len)
        var p = pos
        while (bb.hasRemaining()) {
            val n = ch.read(bb, p)
            if (n <= 0) throw IOException("Unexpected end of archive")
            p += n
        }
        return bb.array()
    }

    fun list(ch: FileChannel): List<ZEntry> {
        val size = ch.size()
        val tailLen = minOf(size, 22L + 65535L).toInt()
        val tail = readAt(ch, size - tailLen, tailLen)
        var eocd = -1
        var i = tailLen - 22
        while (i >= 0) {
            if (u32(tail, i) == 0x06054b50L) { eocd = i; break }
            i--
        }
        if (eocd < 0) throw IOException("Not a valid ZIP (end record not found)")
        var total = u16(tail, eocd + 10).toLong()
        var cdSize = u32(tail, eocd + 12)
        var cdOff = u32(tail, eocd + 16)
        if (total == 0xFFFFL || cdSize == 0xFFFFFFFFL || cdOff == 0xFFFFFFFFL) {
            val eocdAbs = size - tailLen + eocd
            val loc = readAt(ch, eocdAbs - 20, 20)
            if (u32(loc, 0) != 0x07064b50L) throw IOException("ZIP64 locator missing")
            val z = readAt(ch, u64(loc, 8), 56)
            if (u32(z, 0) != 0x06064b50L) throw IOException("ZIP64 end record missing")
            total = u64(z, 32); cdSize = u64(z, 40); cdOff = u64(z, 48)
        }
        if (cdSize > Int.MAX_VALUE) throw IOException("ZIP central directory too large")
        val cd = readAt(ch, cdOff, cdSize.toInt())
        val out = ArrayList<ZEntry>()
        var p = 0
        var n = 0L
        while (n < total) {
            if (p + 46 > cd.size || u32(cd, p) != 0x02014b50L) throw IOException("Corrupt ZIP central directory")
            val method = u16(cd, p + 10)
            var cs = u32(cd, p + 20)
            var us = u32(cd, p + 24)
            val nl = u16(cd, p + 28)
            val el = u16(cd, p + 30)
            val cl = u16(cd, p + 32)
            var lo = u32(cd, p + 42)
            val name = String(cd, p + 46, nl, Charsets.UTF_8)
            var q = p + 46 + nl
            val qEnd = q + el
            while (q + 4 <= qEnd) {
                val id = u16(cd, q)
                val sz = u16(cd, q + 2)
                if (id == 1) {
                    var r = q + 4
                    if (us == 0xFFFFFFFFL) { us = u64(cd, r); r += 8 }
                    if (cs == 0xFFFFFFFFL) { cs = u64(cd, r); r += 8 }
                    if (lo == 0xFFFFFFFFL) { lo = u64(cd, r) }
                }
                q += 4 + sz
            }
            out.add(ZEntry(name, method, cs, us, lo))
            p += 46 + nl + el + cl
            n++
        }
        return out
    }

    /** Stream of the (decompressed) bytes of one entry. */
    fun open(ch: FileChannel, e: ZEntry): InputStream {
        val h = readAt(ch, e.localOff, 30)
        if (u32(h, 0) != 0x04034b50L) throw IOException("Corrupt ZIP entry: ${e.name}")
        val start = e.localOff + 30 + u16(h, 26) + u16(h, 28)
        val raw = object : InputStream() {
            var pos = start
            var left = e.csize
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0L) return -1
                if (len == 0) return 0
                val bb = ByteBuffer.wrap(b, off, minOf(len.toLong(), left).toInt())
                val n = ch.read(bb, pos)
                if (n <= 0) return -1
                pos += n
                left -= n
                return n
            }
        }
        return when (e.method) {
            0 -> raw
            8 -> {
                val inf = Inflater(true)
                object : InflaterInputStream(raw, inf, 1 shl 16) {
                    override fun close() {
                        try { super.close() } finally { inf.end() }
                    }
                }
            }
            else -> throw IOException("Unsupported compression method ${e.method} for ${e.name}")
        }
    }

    /** Unpacks one entry to [out] and checks that the full size arrived. */
    fun extractEntry(ch: FileChannel, e: ZEntry, out: File, onFrac: (Float) -> Unit) {
        var written = 0L
        open(ch, e).use { s ->
            FileOutputStream(out).use { o ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    written += n
                    if (e.usize > 0) onFrac((written.toFloat() / e.usize).coerceAtMost(1f))
                }
            }
        }
        if (written != e.usize) throw IOException("${e.name}: got $written of ${e.usize} bytes")
    }
}

/** Sequential extraction for tgz/tar (and as a fallback for odd zips). Images are reported one by one as they land. */
object Archive {
    class Result(val files: List<File>, val hasPayload: Boolean)

    fun extractImages(
        input: InputStream, name: String, outDir: File, log: (String) -> Unit,
        onFile: (File) -> Unit = {}
    ): Result {
        outDir.deleteRecursively(); outDir.mkdirs()
        val found = ArrayList<File>()
        var payload = false
        val lower = name.lowercase()
        fun save(n: String, src: InputStream, size: Long) {
            if (n == "super_empty.img") return
            if (size > 0 && outDir.usableSpace < size + (64L shl 20)) {
                log("  ✖ skipped $n — not enough free storage (needs ${size / 1048576} MB)")
                return
            }
            var f = File(outDir, n)
            var i = 1
            while (f.exists()) f = File(outDir, "${i++}_$n")
            log("  extract $n" + if (size > 0) " (${size / 1048576} MB)" else "")
            try {
                FileOutputStream(f).use { o -> src.copyTo(o, 1 shl 20) }
                found += f
                onFile(f)
            } catch (e: Exception) {
                f.delete()
                log("  ✖ $n: ${e.message}")
            }
        }

        fun zip(stream: InputStream, depth: Int) {
            val zis = ZipInputStream(stream)
            var e = zis.nextEntry
            while (e != null) {
                val n = e.name.substringAfterLast('/')
                when {
                    e.isDirectory -> {}
                    n.endsWith(".img") -> save(n, zis, e.size)
                    n == "payload.bin" -> payload = true
                    n.endsWith(".zip") && depth < 2 && n.startsWith("image") ->
                        zip(object : FilterInputStream(zis) { override fun close() {} }, depth + 1)
                }
                e = zis.nextEntry
            }
        }

        fun skipFully(s: InputStream, n: Long) {
            var left = n
            while (left > 0) {
                val k = s.skip(left)
                if (k > 0) left -= k else { if (s.read() < 0) return; left-- }
            }
        }

        fun tar(s: InputStream) {
            val h = ByteArray(512)
            var longName: String? = null
            while (true) {
                var r = 0
                while (r < 512) { val k = s.read(h, r, 512 - r); if (k < 0) return; r += k }
                if (h.all { it == 0.toByte() }) return
                fun cstr(o: Int, l: Int): String {
                    var e = o; while (e < o + l && h[e] != 0.toByte()) e++
                    return String(h, o, e - o)
                }
                var n = cstr(0, 100)
                val prefix = cstr(345, 155)
                if (prefix.isNotEmpty()) n = "$prefix/$n"
                val size = cstr(124, 12).trim().ifEmpty { "0" }.toLong(8)
                val type = h[156].toInt().toChar()
                val pad = (512 - size % 512) % 512
                if (type == 'L') {
                    val b = ByteArray(size.toInt()); var k = 0
                    while (k < b.size) { val m = s.read(b, k, b.size - k); if (m < 0) return; k += m }
                    longName = String(b).trimEnd('\u0000')
                    skipFully(s, pad)
                    continue
                }
                if (longName != null) { n = longName; longName = null }
                val base = n.substringAfterLast('/')
                if ((type == '0' || type == '\u0000') && base.endsWith(".img")) {
                    val lim = object : FilterInputStream(s) {
                        var left = size
                        override fun read(): Int = if (left <= 0) -1 else super.read().also { if (it >= 0) left-- }
                        override fun read(b: ByteArray, o: Int, l: Int): Int {
                            if (left <= 0) return -1
                            val k = super.read(b, o, minOf(l.toLong(), left).toInt())
                            if (k > 0) left -= k
                            return k
                        }
                        override fun close() {}
                    }
                    save(base, lim, size)
                    skipFully(s, lim.left)
                    skipFully(s, pad)
                } else {
                    if (base == "payload.bin") payload = true
                    skipFully(s, size + pad)
                }
            }
        }

        when {
            lower.endsWith(".tgz") || lower.endsWith(".tar.gz") -> tar(GZIPInputStream(input, 1 shl 16))
            lower.endsWith(".tar") -> tar(input)
            else -> zip(input, 0)
        }
        return Result(found, payload)
    }
}
