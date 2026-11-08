package com.wusper.x86.x11

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.winlator.inputcontrols.ExternalController
import com.winlator.math.Mathf
import com.winlator.winhandler.MouseEventFlags
import com.winlator.winhandler.WinHandler
import com.winlator.xserver.Pointer
import com.winlator.xserver.XKeycode
import com.winlator.xserver.XServer
import com.wusper.x86.graphics.DesktopLayout
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Session Surface → 進程內 X（WinHandler 級，對齊 Winlator TouchpadView）。
 *
 * - 相對滑鼠：單指拖曳 → injectPointerMoveDelta／WinHandler.MOVE
 * - 左鍵：輕觸（tap）
 * - 右鍵：雙指輕觸 **或** 長按
 * - 滾輪：雙指垂直滑動；外接滑鼠 AXIS_VSCROLL／HSCROLL 多格強化
 * - 可選手把：gamepad／joystick → 方向鍵／WASD／常用鍵（非完整 dinput UDP）
 */
class InProcessXInput(
    private val xServer: XServer,
    private val desktopW: Int = DesktopLayout.WIDTH,
    private val desktopH: Int = DesktopLayout.HEIGHT,
    var style: Style = Style.TOUCHPAD,
) {
    enum class Style { TOUCHPAD, TOUCHSCREEN }

    @Volatile
    var blitRect: Rect = Rect(0, 0, desktopW, desktopH)

    var sensitivity: Float = 1.25f

    private val handler = Handler(Looper.getMainLooper())
    private val fingers = arrayOfNulls<Finger>(MAX_FINGERS)
    private var numFingers = 0
    private var scrollAccumY = 0f
    private var scrolling = false
    private var fingerLeft: Finger? = null
    private var fingerRight: Finger? = null
    private var longPressFinger: Finger? = null
    private var longPressFired = false
    private val padHeld = HashSet<Int>()

    private val longPressRunnable = Runnable {
        val f = longPressFinger ?: return@Runnable
        if (numFingers == 1 && !longPressFired && f.travelDistance() < MAX_TAP_TRAVEL) {
            longPressFired = true
            pressRight(f)
        }
    }

    fun updateBlitFromSurface(surfaceW: Int, surfaceH: Int) {
        if (surfaceW <= 0 || surfaceH <= 0) return
        blitRect = DesktopLayout.fitRect(desktopW, desktopH, surfaceW, surfaceH)
    }

    fun onTouch(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            return onExternalMouseTouch(event)
        }
        val actionIndex = event.actionIndex
        val pointerId = event.getPointerId(actionIndex)
        if (pointerId < 0 || pointerId >= MAX_FINGERS) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                scrollAccumY = 0f
                scrolling = false
                val f = Finger(event.getX(actionIndex), event.getY(actionIndex))
                fingers[pointerId] = f
                numFingers++
                if (style == Style.TOUCHSCREEN && numFingers == 1) {
                    val mapped = mapDesktop(f.viewX, f.viewY)
                    if (mapped != null) {
                        xServer.injectPointerMove(mapped.first, mapped.second)
                        pressLeft(f)
                    }
                } else if (numFingers == 1 && style == Style.TOUCHPAD) {
                    longPressFinger = f
                    longPressFired = false
                    handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                } else {
                    cancelLongPressKeepRight()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until MAX_FINGERS) {
                    val finger = fingers[i] ?: continue
                    val idx = event.findPointerIndex(i)
                    if (idx >= 0) {
                        finger.update(event.getX(idx), event.getY(idx))
                        handleFingerMove(finger)
                    } else {
                        handleFingerUp(finger)
                        fingers[i] = null
                        numFingers = (numFingers - 1).coerceAtLeast(0)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val finger = fingers[pointerId]
                if (finger != null) {
                    finger.update(event.getX(actionIndex), event.getY(actionIndex))
                    handleFingerUp(finger)
                    fingers[pointerId] = null
                    numFingers = (numFingers - 1).coerceAtLeast(0)
                }
                if (numFingers == 0) {
                    cancelLongPressKeepRight()
                    scrolling = false
                    scrollAccumY = 0f
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelLongPressKeepRight()
                releaseLeft()
                releaseRight()
                for (i in 0 until MAX_FINGERS) fingers[i] = null
                numFingers = 0
                scrolling = false
            }
        }
        return true
    }

    fun onGenericMotion(event: MotionEvent): Boolean {
        if (ExternalController.isGameController(event.device)) {
            return onGamepadMotion(event)
        }
        if (event.source and InputDevice.SOURCE_CLASS_POINTER == 0) return false
        val mapped = DesktopLayout.viewToDesktop(event.x, event.y, blitRect, desktopW, desktopH)
        return when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> {
                if (mapped != null) xServer.injectPointerMove(mapped.first, mapped.second)
                true
            }
            MotionEvent.ACTION_SCROLL -> {
                if (mapped != null) xServer.injectPointerMove(mapped.first, mapped.second)
                emitWheel(
                    event.getAxisValue(MotionEvent.AXIS_VSCROLL),
                    event.getAxisValue(MotionEvent.AXIS_HSCROLL),
                )
                true
            }
            MotionEvent.ACTION_BUTTON_PRESS -> {
                if (mapped != null) xServer.injectPointerMove(mapped.first, mapped.second)
                pressMouseButton(event.actionButton)
                true
            }
            MotionEvent.ACTION_BUTTON_RELEASE -> {
                releaseMouseButton(event.actionButton)
                true
            }
            else -> false
        }
    }

    fun onKey(event: KeyEvent): Boolean {
        if (ExternalController.isGameController(event.device) || isGamepadKey(event.keyCode)) {
            return onGamepadKey(event)
        }
        return try {
            xServer.keyboard.onKeyEvent(event)
        } catch (_: Throwable) {
            false
        }
    }

    fun shutdown() {
        handler.removeCallbacks(longPressRunnable)
        longPressFinger = null
        releaseLeft()
        releaseRight()
        for (i in 0 until MAX_FINGERS) fingers[i] = null
        numFingers = 0
        for (token in padHeld.toList()) {
            val key = tokenToKey(token) ?: continue
            try { xServer.injectKeyRelease(key) } catch (_: Throwable) {}
        }
        padHeld.clear()
    }

    // ── gestures ───────────────────────────────────────────────────

    private fun handleFingerMove(finger: Finger) {
        if (longPressFinger === finger && finger.travelDistance() >= MAX_TAP_TRAVEL) {
            cancelLongPressKeepRight()
        }
        var skipMove = false
        val finger2 = if (numFingers == 2) findSecond(finger) else null
        if (finger2 != null) {
            val scale = 1000f / minOf(desktopW, desktopH).toFloat()
            val dist = hypot(
                (finger.x - finger2.x).toDouble(),
                (finger.y - finger2.y).toDouble(),
            ).toFloat() * scale
            if (dist < MAX_TWO_FINGER_SCROLL_DIST) {
                scrollAccumY += ((finger.y + finger2.y) * 0.5f) -
                    ((finger.lastY + finger2.lastY) * 0.5f)
                when {
                    scrollAccumY < -100f -> {
                        clickScroll(Pointer.Button.BUTTON_SCROLL_DOWN)
                        scrollAccumY = 0f
                    }
                    scrollAccumY > 100f -> {
                        clickScroll(Pointer.Button.BUTTON_SCROLL_UP)
                        scrollAccumY = 0f
                    }
                }
                scrolling = true
            } else if (
                dist >= MAX_TWO_FINGER_SCROLL_DIST &&
                !xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT) &&
                finger2.travelDistance() < MAX_TAP_TRAVEL
            ) {
                pressLeft(finger)
                skipMove = true
            }
        }

        if (!scrolling && numFingers <= 2 && !skipMove) {
            when (style) {
                Style.TOUCHSCREEN -> {
                    if (numFingers == 1) {
                        val mapped = mapDesktop(finger.viewX, finger.viewY) ?: return
                        xServer.injectPointerMove(mapped.first, mapped.second)
                    }
                }
                Style.TOUCHPAD -> {
                    val dx = finger.deltaX(sensitivity)
                    val dy = finger.deltaY(sensitivity)
                    if (dx != 0 || dy != 0) moveRelative(dx, dy)
                }
            }
        }
    }

    private fun handleFingerUp(finger: Finger) {
        when (numFingers) {
            1 -> {
                if (style == Style.TOUCHPAD && !longPressFired && finger.isTap()) {
                    pressLeft(finger)
                }
            }
            2 -> {
                val other = findSecond(finger)
                if (other != null && finger.isTap()) {
                    pressRight(finger)
                }
            }
        }
        releaseLeft(finger)
        releaseRight(finger)
        if (longPressFinger === finger) cancelLongPressKeepRight()
    }

    private fun moveRelative(dx: Int, dy: Int) {
        if (xServer.isRelativeMouseMovement) {
            val wh: WinHandler? = xServer.winHandler
            wh?.mouseEvent(MouseEventFlags.MOVE, dx, dy, 0)
                ?: xServer.injectPointerMoveDelta(dx, dy)
        } else {
            xServer.injectPointerMoveDelta(dx, dy)
        }
    }

    private fun pressLeft(finger: Finger) {
        if (xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT)) return
        if (xServer.isRelativeMouseMovement) {
            xServer.winHandler?.mouseEvent(MouseEventFlags.LEFTDOWN, 0, 0, 0)
                ?: xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT)
        } else {
            xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT)
        }
        fingerLeft = finger
    }

    private fun pressRight(finger: Finger) {
        if (xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_RIGHT)) return
        if (xServer.isRelativeMouseMovement) {
            xServer.winHandler?.mouseEvent(MouseEventFlags.RIGHTDOWN, 0, 0, 0)
                ?: xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT)
        } else {
            xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT)
        }
        fingerRight = finger
    }

    private fun releaseLeft(finger: Finger? = fingerLeft) {
        if (finger == null || finger !== fingerLeft) return
        if (!xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT)) {
            fingerLeft = null
            return
        }
        handler.postDelayed({
            if (xServer.isRelativeMouseMovement) {
                xServer.winHandler?.mouseEvent(MouseEventFlags.LEFTUP, 0, 0, 0)
                    ?: xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
            } else {
                xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
            }
            if (fingerLeft === finger) fingerLeft = null
        }, 30)
    }

    private fun releaseRight(finger: Finger? = fingerRight) {
        if (finger == null || finger !== fingerRight) return
        if (!xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_RIGHT)) {
            fingerRight = null
            return
        }
        handler.postDelayed({
            if (xServer.isRelativeMouseMovement) {
                xServer.winHandler?.mouseEvent(MouseEventFlags.RIGHTUP, 0, 0, 0)
                    ?: xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT)
            } else {
                xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT)
            }
            if (fingerRight === finger) fingerRight = null
        }, 30)
    }

    private fun clickScroll(btn: Pointer.Button) {
        xServer.injectPointerButtonPress(btn)
        xServer.injectPointerButtonRelease(btn)
    }

    private fun emitWheel(vScroll: Float, hScroll: Float) {
        fun ticks(v: Float): Int {
            if (v == 0f) return 0
            val a = abs(v)
            val n = when {
                a < 1f -> 1
                else -> a.toInt().coerceIn(1, 8)
            }
            return if (v > 0) n else -n
        }
        val vt = ticks(vScroll)
        if (vt != 0) {
            val btn = if (vt > 0) Pointer.Button.BUTTON_SCROLL_UP else Pointer.Button.BUTTON_SCROLL_DOWN
            repeat(abs(vt)) { clickScroll(btn) }
        }
        val ht = ticks(hScroll)
        if (ht != 0) {
            val btn =
                if (ht > 0) Pointer.Button.BUTTON_SCROLL_CLICK_RIGHT
                else Pointer.Button.BUTTON_SCROLL_CLICK_LEFT
            repeat(abs(ht)) { clickScroll(btn) }
        }
    }

    private fun pressMouseButton(actionButton: Int) {
        when (actionButton) {
            MotionEvent.BUTTON_PRIMARY ->
                xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT)
            MotionEvent.BUTTON_SECONDARY ->
                xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT)
            MotionEvent.BUTTON_TERTIARY ->
                xServer.injectPointerButtonPress(Pointer.Button.BUTTON_MIDDLE)
        }
    }

    private fun releaseMouseButton(actionButton: Int) {
        when (actionButton) {
            MotionEvent.BUTTON_PRIMARY ->
                xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
            MotionEvent.BUTTON_SECONDARY ->
                xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT)
            MotionEvent.BUTTON_TERTIARY ->
                xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_MIDDLE)
        }
    }

    private fun onExternalMouseTouch(event: MotionEvent): Boolean {
        val mapped = DesktopLayout.viewToDesktop(event.x, event.y, blitRect, desktopW, desktopH)
        if (mapped != null && event.actionMasked == MotionEvent.ACTION_MOVE) {
            xServer.injectPointerMove(mapped.first, mapped.second)
            return true
        }
        return false
    }

    // ── gamepad ────────────────────────────────────────────────────

    private fun onGamepadKey(event: KeyEvent): Boolean {
        val code = mapGamepadKey(event.keyCode) ?: return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    xServer.injectKeyPress(code)
                    padHeld.add(event.keyCode)
                }
            }
            KeyEvent.ACTION_UP -> {
                xServer.injectKeyRelease(code)
                padHeld.remove(event.keyCode)
            }
        }
        return true
    }

    private fun onGamepadMotion(event: MotionEvent): Boolean {
        val lx = axis(event, MotionEvent.AXIS_X)
        val ly = axis(event, MotionEvent.AXIS_Y)
        val hx = axis(event, MotionEvent.AXIS_HAT_X)
        val hy = axis(event, MotionEvent.AXIS_HAT_Y)

        setAxisKey(XKeycode.KEY_A, lx < -DEAD, TOKEN_AXIS_A)
        setAxisKey(XKeycode.KEY_D, lx > DEAD, TOKEN_AXIS_D)
        setAxisKey(XKeycode.KEY_W, ly < -DEAD, TOKEN_AXIS_W)
        setAxisKey(XKeycode.KEY_S, ly > DEAD, TOKEN_AXIS_S)

        setAxisKey(XKeycode.KEY_LEFT, hx < -DEAD, TOKEN_HAT_L)
        setAxisKey(XKeycode.KEY_RIGHT, hx > DEAD, TOKEN_HAT_R)
        setAxisKey(XKeycode.KEY_UP, hy < -DEAD, TOKEN_HAT_U)
        setAxisKey(XKeycode.KEY_DOWN, hy > DEAD, TOKEN_HAT_D)

        val rx = firstNonZero(
            axis(event, MotionEvent.AXIS_Z),
            axis(event, MotionEvent.AXIS_RX),
        )
        val ry = firstNonZero(
            axis(event, MotionEvent.AXIS_RZ),
            axis(event, MotionEvent.AXIS_RY),
        )
        if (abs(rx) > DEAD || abs(ry) > DEAD) {
            moveRelative((rx * 14f).toInt(), (ry * 14f).toInt())
        }
        return true
    }

    private fun setAxisKey(key: XKeycode, down: Boolean, token: Int) {
        val held = padHeld.contains(token)
        if (down && !held) {
            xServer.injectKeyPress(key)
            padHeld.add(token)
        } else if (!down && held) {
            xServer.injectKeyRelease(key)
            padHeld.remove(token)
        }
    }

    private fun tokenToKey(token: Int): XKeycode? = when (token) {
        TOKEN_AXIS_A -> XKeycode.KEY_A
        TOKEN_AXIS_D -> XKeycode.KEY_D
        TOKEN_AXIS_W -> XKeycode.KEY_W
        TOKEN_AXIS_S -> XKeycode.KEY_S
        TOKEN_HAT_L -> XKeycode.KEY_LEFT
        TOKEN_HAT_R -> XKeycode.KEY_RIGHT
        TOKEN_HAT_U -> XKeycode.KEY_UP
        TOKEN_HAT_D -> XKeycode.KEY_DOWN
        else -> mapGamepadKey(token)
    }

    private fun mapGamepadKey(keyCode: Int): XKeycode? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> XKeycode.KEY_ENTER
        KeyEvent.KEYCODE_BUTTON_B -> XKeycode.KEY_ESC
        KeyEvent.KEYCODE_BUTTON_X -> XKeycode.KEY_SPACE
        KeyEvent.KEYCODE_BUTTON_Y -> XKeycode.KEY_SHIFT_L
        KeyEvent.KEYCODE_BUTTON_L1 -> XKeycode.KEY_Q
        KeyEvent.KEYCODE_BUTTON_R1 -> XKeycode.KEY_E
        KeyEvent.KEYCODE_BUTTON_L2 -> XKeycode.KEY_CTRL_L
        KeyEvent.KEYCODE_BUTTON_R2 -> XKeycode.KEY_ALT_L
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT -> XKeycode.KEY_TAB
        KeyEvent.KEYCODE_BUTTON_THUMBL -> XKeycode.KEY_SHIFT_L
        KeyEvent.KEYCODE_BUTTON_THUMBR -> XKeycode.KEY_CTRL_L
        KeyEvent.KEYCODE_DPAD_UP -> XKeycode.KEY_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> XKeycode.KEY_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> XKeycode.KEY_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> XKeycode.KEY_RIGHT
        else -> null
    }

    private fun isGamepadKey(keyCode: Int): Boolean =
        keyCode in KeyEvent.KEYCODE_BUTTON_A..KeyEvent.KEYCODE_BUTTON_MODE ||
            keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_DPAD_CENTER

    private fun axis(e: MotionEvent, axis: Int): Float =
        try {
            e.getAxisValue(axis)
        } catch (_: Throwable) {
            0f
        }

    private fun firstNonZero(a: Float, b: Float): Float =
        when {
            abs(a) > DEAD -> a
            abs(b) > DEAD -> b
            else -> 0f
        }

    private fun findSecond(finger: Finger): Finger? =
        fingers.firstOrNull { it != null && it !== finger }

    private fun mapDesktop(viewX: Float, viewY: Float): Pair<Int, Int>? =
        DesktopLayout.viewToDesktop(viewX, viewY, blitRect, desktopW, desktopH)

    private fun cancelLongPressKeepRight() {
        handler.removeCallbacks(longPressRunnable)
        longPressFinger = null
        // longPressFired 保留至 finger up 以免誤觸左鍵 tap
    }

    private inner class Finger(viewX0: Float, viewY0: Float) {
        var viewX: Float = viewX0
        var viewY: Float = viewY0
        var x: Int
        var y: Int
        val startX: Int
        val startY: Int
        var lastX: Int
        var lastY: Int
        private val touchTime = System.currentTimeMillis()

        init {
            val mapped = transform(viewX0, viewY0)
            x = mapped.first
            y = mapped.second
            startX = x
            startY = y
            lastX = x
            lastY = y
        }

        fun update(vx: Float, vy: Float) {
            viewX = vx
            viewY = vy
            lastX = x
            lastY = y
            val mapped = transform(vx, vy)
            x = mapped.first
            y = mapped.second
        }

        fun deltaX(sens: Float): Int {
            var dx = (x - lastX) * sens
            if (abs(dx) > CURSOR_ACCEL_THRESHOLD) dx *= CURSOR_ACCEL
            return Mathf.roundPoint(dx)
        }

        fun deltaY(sens: Float): Int {
            var dy = (y - lastY) * sens
            if (abs(dy) > CURSOR_ACCEL_THRESHOLD) dy *= CURSOR_ACCEL
            return Mathf.roundPoint(dy)
        }

        fun isTap(): Boolean =
            System.currentTimeMillis() - touchTime < MAX_TAP_MS &&
                travelDistance() < MAX_TAP_TRAVEL

        fun travelDistance(): Float =
            hypot((x - startX).toDouble(), (y - startY).toDouble()).toFloat()

        private fun transform(vx: Float, vy: Float): Pair<Int, Int> {
            // 觸控板座標：相對桌面比例（對齊 letterbox）
            val mapped = DesktopLayout.viewToDesktop(vx, vy, blitRect, desktopW, desktopH)
            return if (mapped != null) mapped
            else {
                // 落在 letterbox 外：仍用線性比例，供 delta 計算
                val bw = blitRect.width().coerceAtLeast(1)
                val bh = blitRect.height().coerceAtLeast(1)
                val nx = ((vx - blitRect.left) / bw * desktopW).toInt()
                val ny = ((vy - blitRect.top) / bh * desktopH).toInt()
                nx to ny
            }
        }
    }

    companion object {
        private const val MAX_FINGERS = 4
        private const val MAX_TAP_TRAVEL = 10f
        private const val MAX_TAP_MS = 200L
        private const val MAX_TWO_FINGER_SCROLL_DIST = 350f
        private const val LONG_PRESS_MS = 450L
        private const val CURSOR_ACCEL = 1.5f
        private const val CURSOR_ACCEL_THRESHOLD = 6
        private const val DEAD = 0.35f

        private const val TOKEN_AXIS_A = -1001
        private const val TOKEN_AXIS_D = -1002
        private const val TOKEN_AXIS_W = -1003
        private const val TOKEN_AXIS_S = -1004
        private const val TOKEN_HAT_L = -1005
        private const val TOKEN_HAT_R = -1006
        private const val TOKEN_HAT_U = -1007
        private const val TOKEN_HAT_D = -1008
    }
}
