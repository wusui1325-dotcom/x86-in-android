package com.wusper.x86.graphics

import android.graphics.Rect
import android.view.Surface
import com.wusper.x86.engine.NativeEngine
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GpuStack Present 橋：幀 → AHardwareBuffer → ANativeWindow（Session Surface）。
 *
 * 來源優先序：
 * 1) present.shm（客體／工具寫入）
 * 2) Xvfb XWD（僅作 X 協定 stub 的過渡 feeder，非 SoftLegacy Canvas 路徑）
 * 3) stub 色條（可測通路）
 */
class PresentSurfaceBridge(
    private val dataDir: File,
    private val surface: Surface,
    private val desktopW: Int = DesktopLayout.WIDTH,
    private val desktopH: Int = DesktopLayout.HEIGHT,
    private val targetFps: Int = 30,
    private val allowXwdFeeder: Boolean = true,
    private val allowStub: Boolean = true,
    private val onBlitRect: ((Rect) -> Unit)? = null,
) : Thread("x86-present-bridge") {

    private val running = AtomicBoolean(true)
    @Volatile var lastInfo: String = "Present 初始化…"
    @Volatile var lastBlitRect: Rect = Rect(0, 0, desktopW, desktopH)
    private var lastShmSeq = -1
    private var frameIndex = 0
    private var xwdScratch: ByteBuffer? = null

    fun shutdown() {
        running.set(false)
        interrupt()
        try {
            NativeEngine.nativePresentDetach()
        } catch (_: Throwable) {
        }
    }

    override fun run() {
        val createRc = NativeEngine.nativePresentCreate(desktopW, desktopH)
        val attachRc = NativeEngine.nativePresentAttachSurface(surface)
        if (createRc != 0 || attachRc != 0) {
            lastInfo = "Present 建立失敗 create=$createRc attach=$attachRc"
            return
        }
        lastInfo = "Present AHB→Surface 就緒"
        onBlitRect?.invoke(lastBlitRect)

        val shmFile = PresentShm.path(dataDir)
        val fbDir = File(dataDir, "xfb")
        val intervalMs = (1000L / targetFps.coerceIn(5, 60))

        while (running.get()) {
            try {
                var presented = false

                val shm = PresentShm.readIfNewer(shmFile, lastShmSeq)
                if (shm != null) {
                    lastShmSeq = shm.seq
                    val rc = NativeEngine.nativePresentRgba(
                        shm.rgba, shm.width, shm.height, shm.stride,
                    )
                    lastInfo = if (rc == 0) {
                        "shm seq=${shm.seq} ${shm.width}x${shm.height}"
                    } else {
                        "shm present rc=$rc"
                    }
                    presented = rc == 0
                }

                if (!presented && allowXwdFeeder) {
                    val fb = XwdFrameReader.findFbFile(fbDir)
                    val frame = fb?.let { XwdFrameReader.read(it) }
                    if (frame != null) {
                        val rgba = bitmapToRgbaDirect(frame.bitmap)
                        frame.bitmap.recycle()
                        if (rgba != null) {
                            val rc = NativeEngine.nativePresentRgba(
                                rgba, frame.width, frame.height, frame.width * 4,
                            )
                            lastInfo = if (rc == 0) {
                                "xwd→AHB ${frame.width}x${frame.height}"
                            } else {
                                "xwd present rc=$rc"
                            }
                            presented = rc == 0
                        }
                    }
                }

                if (!presented && allowStub) {
                    val rc = NativeEngine.nativePresentStub(frameIndex)
                    lastInfo = if (rc == 0) "stub#$frameIndex（等客體幀）" else "stub rc=$rc"
                    presented = rc == 0
                }

                frameIndex++
                sleep(intervalMs)
            } catch (_: InterruptedException) {
                break
            } catch (t: Throwable) {
                lastInfo = "present: ${t.message}"
                try {
                    sleep(intervalMs)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        NativeEngine.nativePresentDetach()
    }

    private fun bitmapToRgbaDirect(bmp: android.graphics.Bitmap): ByteBuffer? {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return null
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val need = w * h * 4
        var buf = xwdScratch
        if (buf == null || buf.capacity() < need) {
            buf = ByteBuffer.allocateDirect(need)
            xwdScratch = buf
        }
        buf.clear()
        for (p in pixels) {
            // Android ARGB int → RGBA bytes
            buf.put(((p shr 16) and 0xff).toByte())
            buf.put(((p shr 8) and 0xff).toByte())
            buf.put((p and 0xff).toByte())
            buf.put(((p shr 24) and 0xff).toByte())
        }
        buf.flip()
        return buf
    }
}
