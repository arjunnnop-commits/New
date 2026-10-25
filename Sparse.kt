package com.yooitachiijod.glassfastboot

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Android sparse image reader + re-splitter (raw or sparse input -> pieces <= max download size). */
object Sparse {
    private const val MAGIC = 0xED26FF3A.toInt()
    const val RAW = 0xCAC1
    const val FILL = 0xCAC2
    const val DONT = 0xCAC3
    const val CRC = 0xCAC4

    class Ch(val type: Int, val blocks: Long, val srcOff: Long, val fill: Int)
    class Layout(val blk: Int, val total: Long, val chunks: List<Ch>)

    fun isSparse(ch: FileChannel): Boolean {
        val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        if (ch.read(bb, 0) < 4) return false
        bb.flip()
        return bb.int == MAGIC
    }

    fun rawLayout(size: Long): Layout {
        val blk = 4096
        val blocks = (size + blk - 1) / blk
        return Layout(blk, blocks, listOf(Ch(RAW, blocks, 0, 0)))
    }

    fun parse(ch: FileChannel): Layout {
        val h = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
        ch.read(h, 0); h.flip()
        h.getInt()
        h.getShort(); h.getShort()
        val fileHdr = h.getShort().toInt() and 0xFFFF
        val chunkHdr = h.getShort().toInt() and 0xFFFF
        val blk = h.getInt()
        val total = h.getInt().toLong() and 0xFFFFFFFFL
        val nChunks = h.getInt()
        var pos = fileHdr.toLong()
        val list = ArrayList<Ch>(nChunks)
        val c = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        repeat(nChunks) {
            c.clear(); ch.read(c, pos); c.flip()
            val type = c.getShort().toInt() and 0xFFFF
            c.getShort()
            val sz = c.getInt().toLong() and 0xFFFFFFFFL
            val tsz = c.getInt().toLong() and 0xFFFFFFFFL
            val dataOff = pos + chunkHdr
            when (type) {
                RAW -> list += Ch(RAW, sz, dataOff, 0)
                FILL -> {
                    val f = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                    ch.read(f, dataOff); f.flip()
                    list += Ch(FILL, sz, 0, f.int)
                }
                DONT -> list += Ch(DONT, sz, 0, 0)
            }
            pos += tsz
        }
        return Layout(blk, total, list)
    }

    /** One output piece: a run of chunks that starts at block [start] of the full image. */
    class Piece(val start: Long, val chunks: List<Ch>)

    /** Works out how to cut the image into pieces <= maxSize WITHOUT writing anything yet. */
    fun plan(layout: Layout, maxSize: Long): List<Piece> {
        val blk = layout.blk.toLong()
        val pieces = ArrayList<Piece>()
        var cur = ArrayList<Ch>()
        var curBytes = 52L          // header 28 + lead dont-care 12 + tail dont-care 12
        var curBlocks = 0L
        var blkPos = 0L

        fun flush() {
            if (cur.isEmpty()) return
            pieces += Piece(blkPos - curBlocks, cur)
            cur = ArrayList(); curBytes = 52L; curBlocks = 0L
        }

        for (c in layout.chunks) {
            var rem = c.blocks
            var src = c.srcOff
            while (rem > 0) {
                when (c.type) {
                    RAW -> {
                        val avail = (maxSize - curBytes - 12) / blk
                        if (avail <= 0) {
                            if (cur.isEmpty()) throw FastbootException("max-download-size is too small")
                            flush(); continue
                        }
                        val take = minOf(avail, rem)
                        cur += Ch(RAW, take, src, 0)
                        curBytes += 12 + take * blk
                        curBlocks += take; blkPos += take
                        src += take * blk; rem -= take
                    }
                    FILL, DONT -> {
                        val need = if (c.type == FILL) 16 else 12
                        if (maxSize - curBytes < need) { flush(); continue }
                        cur += Ch(c.type, rem, 0, c.fill)
                        curBytes += need
                        curBlocks += rem; blkPos += rem; rem = 0
                    }
                    else -> rem = 0
                }
            }
        }
        flush()
        return pieces
    }

    /** Writes one planned piece to [out] right before it is flashed, so only one piece is on disk at a time. */
    fun write(ch: FileChannel, l: Layout, piece: Piece, out: File) = writePiece(ch, l, piece.start, piece.chunks, out)

    private fun writePiece(ch: FileChannel, l: Layout, start: Long, chunks: List<Ch>, out: File) {
        val tail = l.total - (start + chunks.sumOf { it.blocks })
        val count = chunks.size + (if (start > 0) 1 else 0) + (if (tail > 0) 1 else 0)
        BufferedOutputStream(FileOutputStream(out), 1 shl 20).use { os ->
            val h = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
            h.putInt(MAGIC); h.putShort(1); h.putShort(0); h.putShort(28); h.putShort(12)
            h.putInt(l.blk); h.putInt(l.total.toInt()); h.putInt(count); h.putInt(0)
            os.write(h.array())
            fun hdr(type: Int, blocks: Long, total: Long) {
                val b = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                b.putShort(type.toShort()); b.putShort(0); b.putInt(blocks.toInt()); b.putInt(total.toInt())
                os.write(b.array())
            }
            if (start > 0) hdr(DONT, start, 12)
            for (c in chunks) when (c.type) {
                RAW -> {
                    hdr(RAW, c.blocks, 12 + c.blocks * l.blk)
                    copyRaw(ch, c.srcOff, c.blocks * l.blk, os)
                }
                FILL -> {
                    hdr(FILL, c.blocks, 16)
                    os.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(c.fill).array())
                }
                DONT -> hdr(DONT, c.blocks, 12)
            }
            if (tail > 0) hdr(DONT, tail, 12)
        }
    }

    private fun copyRaw(ch: FileChannel, off: Long, len: Long, os: BufferedOutputStream) {
        val bb = ByteBuffer.allocate(1 shl 20)
        var pos = off
        var left = len
        while (left > 0) {
            bb.clear()
            bb.limit(minOf(bb.capacity().toLong(), left).toInt())
            val n = ch.read(bb, pos)
            if (n <= 0) { val z = minOf(left, 1L shl 20); os.write(ByteArray(z.toInt())); left -= z; continue }
            os.write(bb.array(), 0, n)
            pos += n; left -= n
        }
    }
}
