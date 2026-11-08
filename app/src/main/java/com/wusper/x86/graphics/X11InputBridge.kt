package com.wusper.x86.graphics

import android.view.KeyEvent
import android.view.MotionEvent
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 將 Android 觸控／鍵鼠事件寫入客體可讀的命令檔，由 x11-input-agent（xdotool）注入 X11。
 *
 * 協定（一行一令）：
 *   move X Y | down [btn] | up [btn] | click [btn] | key … | keydown … | keyup …
 *
 * 座標：Surface letterbox 反查到 1280×720 虛擬桌面絕對座標。
 */
class X11InputBridge(
    private val dataDir: File,
    private val desktopW: Int = DesktopLayout.WIDTH,
    private val desktopH: Int = DesktopLayout.HEIGHT,
) {
    private val cmdFile = File(dataDir, "x11input.cmd")
    private val writer = Executors.newSingleThreadExecutor()
    private val alive = AtomicBoolean(true)
    @Volatile var blitRect = android.graphics.Rect(0, 0, desktopW, desktopH)
    private var pointerDown = false
    private var lastX = 0
    private var lastY = 0

    fun prepareFifo() {
        // 歷史名；實際用命令檔佇列
        dataDir.mkdirs()
        if (!cmdFile.exists()) cmdFile.writeText("")
    }

    fun shutdown() {
        alive.set(false)
        send("keyup Shift_L")
        send("keyup Control_L")
        send("keyup Alt_L")
        send("keyup Super_L")
        writer.shutdownNow()
    }

    fun onTouch(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val mapped = DesktopLayout.viewToDesktop(event.x, event.y, blitRect, desktopW, desktopH)
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (mapped == null) return false
                lastX = mapped.first
                lastY = mapped.second
                pointerDown = true
                send("move $lastX $lastY")
                send("down 1")
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!pointerDown) return false
                if (mapped != null) {
                    // 降採樣：座標變化才送，減 xdotool 壓力
                    if (mapped.first != lastX || mapped.second != lastY) {
                        lastX = mapped.first
                        lastY = mapped.second
                        send("move $lastX $lastY")
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (!pointerDown) return false
                if (mapped != null) {
                    lastX = mapped.first
                    lastY = mapped.second
                    send("move $lastX $lastY")
                }
                send("up 1")
                pointerDown = false
                return true
            }
        }
        return false
    }

    fun onGenericMotion(event: MotionEvent): Boolean {
        if (event.source and android.view.InputDevice.SOURCE_CLASS_POINTER == 0) return false
        val mapped = DesktopLayout.viewToDesktop(event.x, event.y, blitRect, desktopW, desktopH)
            ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> {
                send("move ${mapped.first} ${mapped.second}")
                return true
            }
            MotionEvent.ACTION_SCROLL -> {
                val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (v > 0f) send("click 4")
                else if (v < 0f) send("click 5")
                return true
            }
        }
        return false
    }

    fun onKey(event: KeyEvent): Boolean {
        // 標點等：ACTION_DOWN 用 type 單字元
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val ch = event.unicodeChar
            if (ch in 33..126) {
                val c = ch.toChar()
                if (!c.isLetterOrDigit() && c != ' ') {
                    // 跳脫雙引號
                    val safe = if (c == '"') "\"" else c.toString()
                    send("type $safe")
                    return true
                }
            }
        }
        val name = keyName(event) ?: return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) send("keydown $name")
                return true
            }
            KeyEvent.ACTION_UP -> {
                send("keyup $name")
                return true
            }
        }
        return false
    }

    private fun send(line: String) {
        if (!alive.get()) return
        writer.execute {
            try {
                synchronized(cmdFile) {
                    FileOutputStream(cmdFile, true).use { fos ->
                        fos.write((line + "\n").toByteArray(Charsets.UTF_8))
                        fos.flush()
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun keyName(event: KeyEvent): String? {
        when (event.keyCode) {
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> return "Shift_L"
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> return "Control_L"
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> return "Alt_L"
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> return "Super_L"
            KeyEvent.KEYCODE_ENTER -> return "Return"
            KeyEvent.KEYCODE_SPACE -> return "space"
            KeyEvent.KEYCODE_DEL -> return "BackSpace"
            KeyEvent.KEYCODE_FORWARD_DEL -> return "Delete"
            KeyEvent.KEYCODE_TAB -> return "Tab"
            KeyEvent.KEYCODE_ESCAPE -> return "Escape"
            KeyEvent.KEYCODE_DPAD_UP -> return "Up"
            KeyEvent.KEYCODE_DPAD_DOWN -> return "Down"
            KeyEvent.KEYCODE_DPAD_LEFT -> return "Left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> return "Right"
            KeyEvent.KEYCODE_HOME -> return "Home"
            KeyEvent.KEYCODE_MOVE_END -> return "End"
            KeyEvent.KEYCODE_PAGE_UP -> return "Page_Up"
            KeyEvent.KEYCODE_PAGE_DOWN -> return "Page_Down"
        }
        if (event.keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
            return ('a' + (event.keyCode - KeyEvent.KEYCODE_A)).toString()
        }
        if (event.keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            return ('0' + (event.keyCode - KeyEvent.KEYCODE_0)).toString()
        }
        return null
    }
}
