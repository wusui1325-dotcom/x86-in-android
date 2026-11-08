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
    private val desktopW: Int = DesktopLayout.WIDTH,
    private val desktopH: Int = DesktopLayout.HEIGHT,
    private val onBlitRect: ((Rect) -> Unit)? = null,
) : Thread("x86-surface-bridge") {
    private val running = AtomicBoolean(true)
    @Volatile var lastInfo: String = "等待幀…"
    /** 最近一次繪製的 letterbox 區域（視圖座標），供觸控反查。 */
    @Volatile var lastBlitRect: Rect = Rect(0, 0, desktopW, desktopH)

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
                            val dst = DesktopLayout.fitRect(
                                frame.width, frame.height, canvas.width, canvas.height,
                            )
                            lastBlitRect = Rect(dst)
                            onBlitRect?.invoke(lastBlitRect)
                            canvas.drawBitmap(frame.bitmap, null, dst, null)
                            lastInfo = "${frame.width}x${frame.height} ← ${fb?.name}"
                            frame.bitmap.recycle()
                        } else {
                            // 尚無幀時仍假設桌面等比 letterbox，方便點擊測試
                            lastBlitRect = DesktopLayout.fitRect(
                                desktopW, desktopH, canvas.width, canvas.height,
                            )
                            onBlitRect?.invoke(lastBlitRect)
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
}
