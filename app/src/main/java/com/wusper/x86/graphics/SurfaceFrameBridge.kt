package com.wusper.x86.graphics

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.SurfaceHolder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 輪詢 Xvfb fbdir XWD → blit 到 SurfaceHolder。
 */
class SurfaceFrameBridge(
    private val fbDir: File,
    private val holder: SurfaceHolder,
    private val targetFps: Int = 15,
) : Thread("x86-surface-bridge") {
    private val running = AtomicBoolean(true)
    @Volatile var lastInfo: String = "等待幀…"

    fun shutdown() {
        running.set(false)
        interrupt()
    }

    override fun run() {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 36f
        }
        val intervalMs = (1000L / targetFps.coerceIn(5, 60))
        fbDir.mkdirs()
        while (running.get()) {
            try {
                val fb = XwdFrameReader.findFbFile(fbDir)
                val frame = fb?.let { XwdFrameReader.read(it) }
                val canvas: Canvas? = try {
                    holder.lockCanvas()
                } catch (_: Throwable) {
                    null
                }
                if (canvas != null) {
                    try {
                        canvas.drawColor(Color.rgb(10, 14, 20))
                        if (frame != null) {
                            val dst = fitRect(frame.width, frame.height, canvas.width, canvas.height)
                            canvas.drawBitmap(frame.bitmap, null, dst, null)
                            lastInfo = "${frame.width}x${frame.height} ← ${fb?.name}"
                            frame.bitmap.recycle()
                        } else {
                            canvas.drawText("等待 Xvfb 幀（$fbDir）", 40f, 80f, paint)
                            lastInfo = "無幀"
                        }
                    } finally {
                        try {
                            holder.unlockCanvasAndPost(canvas)
                        } catch (_: Throwable) {
                        }
                    }
                }
                sleep(intervalMs)
            } catch (_: InterruptedException) {
                break
            } catch (t: Throwable) {
                lastInfo = "bridge: ${t.message}"
                try {
                    sleep(500)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun fitRect(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Rect {
        if (srcW <= 0 || srcH <= 0) return Rect(0, 0, dstW, dstH)
        val scale = minOf(dstW.toFloat() / srcW, dstH.toFloat() / srcH)
        val w = (srcW * scale).toInt().coerceAtLeast(1)
        val h = (srcH * scale).toInt().coerceAtLeast(1)
        val left = (dstW - w) / 2
        val top = (dstH - h) / 2
        return Rect(left, top, left + w, top + h)
    }
}
