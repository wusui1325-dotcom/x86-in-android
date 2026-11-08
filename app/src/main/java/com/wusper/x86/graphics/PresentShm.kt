package com.wusper.x86.graphics

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * present.shm 協定（v1）：
 * magic 'X86P' | ver=1 | w | h | stride | seq | format=0(RGBA) | reserved
 * 後接 stride*height 位元組 RGBA。
 * 對齊 Termux-X11 LorieBuffer FD／shm 思路的最短檔案版（可升級 ashmem）。
 */
object PresentShm {
    const val MAGIC = 0x50363858 // 'X86P' little-endian as int from bytes X86P → need careful
    // bytes: 'X','8','6','P' → 0x58 0x38 0x36 0x50 → LE int 0x50363858
    const val VERSION = 1
    const val HEADER_SIZE = 32
    const val FORMAT_RGBA8888 = 0

    data class Frame(
        val width: Int,
        val height: Int,
        val stride: Int,
        val seq: Int,
        val rgba: ByteBuffer,
    )

    fun path(dataDir: File): File = File(dataDir, "present.shm")

    fun writeStubHeader(file: File, width: Int, height: Int, seq: Int) {
        val stride = width * 4
        val total = HEADER_SIZE + stride * height
        RandomAccessFile(file, "rw").use { raf ->
            if (raf.length() < total) raf.setLength(total.toLong())
            val ch = raf.channel
            val map = ch.map(FileChannel.MapMode.READ_WRITE, 0, total.toLong())
            map.order(ByteOrder.LITTLE_ENDIAN)
            map.putInt(0, MAGIC)
            map.putInt(4, VERSION)
            map.putInt(8, width)
            map.putInt(12, height)
            map.putInt(16, stride)
            map.putInt(20, seq)
            map.putInt(24, FORMAT_RGBA8888)
            map.putInt(28, 0)
            map.force()
        }
    }

    fun readIfNewer(file: File, lastSeq: Int): Frame? {
        if (!file.exists() || file.length() < HEADER_SIZE) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val ch = raf.channel
                val header = ByteArray(HEADER_SIZE)
                raf.readFully(header)
                val hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val magic = hb.int
                val ver = hb.int
                if (magic != MAGIC || ver != VERSION) return null
                val w = hb.int
                val h = hb.int
                val stride = hb.int
                val seq = hb.int
                val fmt = hb.int
                if (fmt != FORMAT_RGBA8888) return null
                if (w <= 0 || h <= 0 || w > 4096 || h > 4096) return null
                if (seq == lastSeq) return null
                val need = HEADER_SIZE + stride * h
                if (raf.length() < need) return null
                val pixels = ByteArray(stride * h)
                raf.seek(HEADER_SIZE.toLong())
                var off = 0
                while (off < pixels.size) {
                    val n = raf.read(pixels, off, pixels.size - off)
                    if (n <= 0) break
                    off += n
                }
                val buf = ByteBuffer.allocateDirect(pixels.size)
                buf.put(pixels)
                buf.flip()
                Frame(w, h, stride, seq, buf)
            }
        } catch (_: Throwable) {
            null
        }
    }
}
