package com.wusper.x86.x11

import android.graphics.Rect
import android.view.KeyEvent
import android.view.MotionEvent
import com.winlator.xserver.Pointer
import com.winlator.xserver.XServer
import com.wusper.x86.graphics.DesktopLayout

/**
 * Session Surface 觸控／鍵鼠 → 進程內 X（[XServer.injectPointerMove]／[XServer.injectPointerButtonPress]／
 * [com.winlator.xserver.Keyboard.onKeyEvent]）。
 *
 * 取代 GpuStack 路徑的 xdotool 檔案佇列（[com.wusper.x86.graphics.X11InputBridge] 僅 SoftLegacy）。
 */
class InProcessXInput(
    private val xServer: XServer,
    private val desktopW: Int = DesktopLayout.WIDTH,
    private val desktopH: Int = DesktopLayout.HEIGHT,
) {
    /** Surface letterbox（視圖座標）；Present 滿版時可設為整個 Surface。 */
    @Volatile
    var blitRect: Rect = Rect(0, 0, desktopW, desktopH)

    private var pointerDown = false
    private var lastX = 0
    private var lastY = 0

    fun updateBlitFromSurface(surfaceW: Int, surfaceH: Int) {
        if (surfaceW <= 0 || surfaceH <= 0) return
        blitRect = DesktopLayout.fitRect(desktopW, desktopH, surfaceW, surfaceH)
    }

    fun onTouch(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val mapped = DesktopLayout.viewToDesktop(event.x, event.y, blitRect, desktopW, desktopH)
        return when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (mapped == null) return false
                lastX = mapped.first
                lastY = mapped.second
                pointerDown = true
                xServer.injectPointerMove(lastX, lastY)
                xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!pointerDown) return false
                if (mapped != null && (mapped.first != lastX || mapped.second != lastY)) {
                    lastX = mapped.first
                    lastY = mapped.second
                    xServer.injectPointerMove(lastX, lastY)
                }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (!pointerDown) return false
                if (mapped != null) {
                    lastX = mapped.first
                    lastY = mapped.second
                    xServer.injectPointerMove(lastX, lastY)
                }
                xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
                pointerDown = false
                true
            }
            else -> false
        }
    }

    fun onGenericMotion(event: MotionEvent): Boolean {
        if (event.source and android.view.InputDevice.SOURCE_CLASS_POINTER == 0) return false
        val mapped = DesktopLayout.viewToDesktop(event.x, event.y, blitRect, desktopW, desktopH)
            ?: return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> {
                xServer.injectPointerMove(mapped.first, mapped.second)
                true
            }
            MotionEvent.ACTION_SCROLL -> {
                xServer.injectPointerMove(mapped.first, mapped.second)
                val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                when {
                    v > 0f -> {
                        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_UP)
                        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_UP)
                    }
                    v < 0f -> {
                        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_DOWN)
                        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_DOWN)
                    }
                }
                true
            }
            else -> false
        }
    }

    fun onKey(event: KeyEvent): Boolean {
        // 走 Winlator Keyboard 既有 Android→XKeycode 表＋injectKey*
        return try {
            xServer.keyboard.onKeyEvent(event)
        } catch (_: Throwable) {
            false
        }
    }

    fun shutdown() {
        if (pointerDown) {
            try {
                xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
            } catch (_: Throwable) {
            }
            pointerDown = false
        }
    }
}
