package com.wusper.x86.graphics

import android.graphics.Bitmap
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 讀取 Xvfb `-fbdir` 產出的 XWD 檔（ZPixmap），轉成 ARGB_8888 Bitmap。
 * 最短顯示路徑：客體 X11 → XWD 檔 → Surface blit。
 */
object XwdFrameReader {
    data class Frame(
        val width: Int,
        val height: Int,
        val bitmap: Bitmap,
    )

    fun findFbFile(dir: File): File? {
        if (!dir.isDirectory) return null
        // Xvfb 典型檔名：Xvfb_screen0
        val preferred = File(dir, "Xvfb_screen0")
        if (preferred.exists() && preferred.length() > 256) return preferred
        return dir.listFiles()
            ?.filter { it.isFile && it.length() > 256 }
            ?.maxByOrNull { it.lastModified() }
    }

    fun read(file: File): Frame? {
        if (!file.exists() || file.length() < 100) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val headerBytes = ByteArray(256)
                raf.readFully(headerBytes)
                val bb = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN)
                val headerSize = bb.int
                val fileVersion = bb.int
                if (fileVersion != 7) return null
                val pixmapFormat = bb.int // 2 = ZPixmap
                val pixmapDepth = bb.int
                val pixmapWidth = bb.int
                val pixmapHeight = bb.int
                val xOffset = bb.int
                val byteOrder = bb.int // 1=MSBFirst
                val bitmapUnit = bb.int
                val bitmapBitOrder = bb.int
                val bitmapPad = bb.int
                val bitsPerPixel = bb.int
                val bytesPerLine = bb.int
                val visualClass = bb.int
                // skip rest of fixed header fields we don't need for copy
                if (pixmapWidth <= 0 || pixmapHeight <= 0 || pixmapWidth > 4096 || pixmapHeight > 4096) {
                    return null
                }
                if (headerSize <= 0 || headerSize > file.length()) return null

                raf.seek(headerSize.toLong())
                val pixelBytes = ByteArray((file.length() - headerSize).toInt().coerceAtLeast(0))
                var off = 0
                while (off < pixelBytes.size) {
                    val n = raf.read(pixelBytes, off, pixelBytes.size - off)
                    if (n <= 0) break
                    off += n
                }

                val bmp = Bitmap.createBitmap(pixmapWidth, pixmapHeight, Bitmap.Config.ARGB_8888)
                val pixels = IntArray(pixmapWidth * pixmapHeight)
                when {
                    bitsPerPixel == 32 || bitsPerPixel == 24 -> {
                        // Xvfb often BGRA or RGBX depending on order; assume BGRA little / MSBFirst quirks
                        var i = 0
                        var p = 0
                        val stride = if (bytesPerLine > 0) bytesPerLine else pixmapWidth * 4
                        for (y in 0 until pixmapHeight) {
                            var row = y * stride
                            for (x in 0 until pixmapWidth) {
                                if (row + 3 >= pixelBytes.size) break
                                val b = pixelBytes[row].toInt() and 0xff
                                val g = pixelBytes[row + 1].toInt() and 0xff
                                val r = pixelBytes[row + 2].toInt() and 0xff
                                // XWD on Xvfb depth 24/32 typically B,G,R,pad
                                pixels[p++] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
                                row += 4
                            }
                        }
                    }
                    bitsPerPixel == 16 -> {
                        var p = 0
                        val stride = if (bytesPerLine > 0) bytesPerLine else pixmapWidth * 2
                        for (y in 0 until pixmapHeight) {
                            var row = y * stride
                            for (x in 0 until pixmapWidth) {
                                if (row + 1 >= pixelBytes.size) break
                                val lo = pixelBytes[row].toInt() and 0xff
                                val hi = pixelBytes[row + 1].toInt() and 0xff
                                val v = lo or (hi shl 8)
                                val r = ((v shr 11) and 0x1f) * 255 / 31
                                val g = ((v shr 5) and 0x3f) * 255 / 63
                                val b = (v and 0x1f) * 255 / 31
                                pixels[p++] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
                                row += 2
                            }
                        }
                    }
                    else -> return null
                }
                bmp.setPixels(pixels, 0, pixmapWidth, 0, 0, pixmapWidth, pixmapHeight)
                Frame(pixmapWidth, pixmapHeight, bmp)
            }
        } catch (_: Throwable) {
            null
        }
    }
}
