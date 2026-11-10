package com.wusper.x86.x11

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.winlator.inputcontrols.ExternalController
import com.winlator.math.Mathf
import com.winlator.xserver.Pointer
import com.winlator.xserver.Window
import com.winlator.xserver.XKeycode
import com.winlator.xserver.XServer
import com.wusper.x86.graphics.DesktopLayout
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Session Surface → 進程內 X.
 *
 * 0.1.117 default is direct touch (按哪點哪). Never set
 * [XServer.isRelativeMouseMovement]; always [XServer.injectPointerMove] /
 * [XServer.injectPointerButtonPress] / [XServer.injectPointerButtonRelease].
 *
 * Direct (TOUCHSCREEN):
 *  - DOWN: move to letterbox-aligned 1280×720 desk, then left down
 *  - UP: left up
 *  - single-finger drag: scroll wheel (not cursor / window drag)
 *  - long-press: right click
 *
 * Pad (TOUCHPAD):
 *  - drag anywhere: relative mouse move
 *  - single tap: left click at the cursor
 *  - double tap: start left-button hold; another double tap ends it
 *  - triple tap: right click
 *  - two fingers: scroll wheel
 */
class InProcessXInput(
    private val xServer: XServer,
    private val desktopW: Int = DesktopLayout.WIDTH,
    private val desktopH: Int = DesktopLayout.HEIGHT,
    var style: Style = Style.TOUCHSCREEN,
) {
    enum class Style { TOUCHPAD, TOUCHSCREEN }

    @Volatile
    var blitRect: Rect = Rect(0, 0, desktopW, desktopH)

    var sensitivity: Float = 1.25f

    /** Optional stamp sink (main thread). Format: INPUT_TOUCH action=… */
    var onStamp: ((String) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val fingers = arrayOfNulls<Finger>(MAX_FINGERS)
    private var numFingers = 0
    private var scrollAccumY = 0f
    private var scrolling = false
    private var fingerLeft: Finger? = null
    private var fingerRight: Finger? = null
    private var longPressFinger: Finger? = null
    private var longPressFired = false
    private var dragConvertedToWheel = false
    private val padHeld = HashSet<Int>()

    // Pad multi-tap
    private var padTapCount = 0
    private var padTapArmed = false
    private var padLeftHeld = false

    private var lastDispatchKey = 0L

    private val longPressRunnable = Runnable {
        val f = longPressFinger ?: return@Runnable
        if (style != Style.TOUCHSCREEN) return@Runnable
        if (numFingers == 1 && !longPressFired && f.travelDistance() < MAX_TAP_TRAVEL) {
            longPressFired = true
            releaseLeftImmediate()
            pressRight(f)
            stamp("down", f.viewX, f.viewY, "right")
        }
    }

    private val padTapCommit = Runnable {
        if (!padTapArmed) return@Runnable
        val count = padTapCount
        padTapArmed = false
        padTapCount = 0
        when (count) {
            1 -> {
                if (!padLeftHeld) {
                    clickLeftAtCursor()
                    stampCursor("down", "left")
                    stampCursor("up", "left")
                }
            }
            2 -> {
                if (!padLeftHeld) {
                    injectLeftDown()
                    padLeftHeld = true
                    stampCursor("down", "left")
                } else {
                    injectLeftUp()
                    padLeftHeld = false
                    stampCursor("up", "left")
                }
            }
            else -> if (count >= 3) {
                clickRightAtCursor()
                stampCursor("down", "right")
                stampCursor("up", "right")
            }
        }
    }

    fun updateBlitFromSurface(surfaceW: Int, surfaceH: Int) {
        if (surfaceW <= 0 || surfaceH <= 0) return
        blitRect = DesktopLayout.fitRect(desktopW, desktopH, surfaceW, surfaceH)
    }

    fun onTouch(event: MotionEvent): Boolean {
        if (isDuplicateDispatch(event)) return true
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
                if (style == Style.TOUCHSCREEN && event.actionMasked == MotionEvent.ACTION_DOWN) {
                    dragConvertedToWheel = false
                    longPressFired = false
                    val mapped = mapDesktop(f.viewX, f.viewY)
                    if (mapped != null) {
                        xServer.injectPointerMove(mapped.first, mapped.second)
                        pressLeft(f)
                    }
                    longPressFinger = f
                    handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                    stamp("down", f.viewX, f.viewY, "left")
                } else if (style == Style.TOUCHPAD && event.actionMasked == MotionEvent.ACTION_DOWN) {
                    longPressFinger = f
                    longPressFired = false
                    // do not move cursor to the finger — pad clicks land at the cursor
                } else {
                    cancelLongPressKeepRight()
                    if (style == Style.TOUCHSCREEN && numFingers >= 2) {
                        // Second finger: cancel left so we don't drag the window; scroll instead.
                        releaseLeftImmediate()
                        dragConvertedToWheel = true
                    }
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
                    dragConvertedToWheel = false
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelLongPressKeepRight()
                releaseLeftImmediate()
                releaseRightImmediate()
                for (i in 0 until MAX_FINGERS) fingers[i] = null
                numFingers = 0
                scrolling = false
                dragConvertedToWheel = false
                padTapArmed = false
                padTapCount = 0
                handler.removeCallbacks(padTapCommit)
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
                if (mapped != null) stamp("move", event.x, event.y, "wheel")
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
        handler.removeCallbacks(padTapCommit)
        longPressFinger = null
        releaseLeftImmediate()
        releaseRightImmediate()
        for (i in 0 until MAX_FINGERS) fingers[i] = null
        numFingers = 0
        padLeftHeld = false
        padTapArmed = false
        padTapCount = 0
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

        if (style == Style.TOUCHSCREEN) {
            handleDirectMove(finger)
            return
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
                    scrollAccumY < -WHEEL_STEP -> {
                        clickScroll(Pointer.Button.BUTTON_SCROLL_DOWN)
                        stamp("move", finger.viewX, finger.viewY, "wheel")
                        scrollAccumY = 0f
                    }
                    scrollAccumY > WHEEL_STEP -> {
                        clickScroll(Pointer.Button.BUTTON_SCROLL_UP)
                        stamp("move", finger.viewX, finger.viewY, "wheel")
                        scrollAccumY = 0f
                    }
                }
                scrolling = true
            }
        }

        if (!scrolling && numFingers == 1 && !skipMove) {
            val dx = finger.deltaX(sensitivity)
            val dy = finger.deltaY(sensitivity)
            if (dx != 0 || dy != 0) {
                moveRelative(dx, dy)
                if (abs(dx) + abs(dy) >= MOVE_STAMP_MIN) {
                    stamp("move", finger.viewX, finger.viewY, "")
                }
            }
        }
    }

    private fun handleDirectMove(finger: Finger) {
        if (numFingers >= 2) {
            val finger2 = findSecond(finger) ?: return
            scrollAccumY += ((finger.y + finger2.y) * 0.5f) -
                ((finger.lastY + finger2.lastY) * 0.5f)
            drainWheel(finger)
            return
        }
        if (numFingers != 1) return
        if (finger.travelDistance() < MAX_TAP_TRAVEL) return
        // Drag must not move the cursor or drag the window.
        if (!dragConvertedToWheel) {
            releaseLeftImmediate()
            dragConvertedToWheel = true
            cancelLongPressKeepRight()
        }
        scrollAccumY += (finger.y - finger.lastY).toFloat()
        drainWheel(finger)
    }

    private fun drainWheel(finger: Finger) {
        while (scrollAccumY <= -WHEEL_STEP) {
            clickScroll(Pointer.Button.BUTTON_SCROLL_DOWN)
            stamp("move", finger.viewX, finger.viewY, "wheel")
            scrollAccumY += WHEEL_STEP
        }
        while (scrollAccumY >= WHEEL_STEP) {
            clickScroll(Pointer.Button.BUTTON_SCROLL_UP)
            stamp("move", finger.viewX, finger.viewY, "wheel")
            scrollAccumY -= WHEEL_STEP
        }
        scrolling = true
    }

    private fun handleFingerUp(finger: Finger) {
        when (style) {
            Style.TOUCHSCREEN -> {
                val btn = when {
                    longPressFired -> "right"
                    dragConvertedToWheel -> "wheel"
                    else -> "left"
                }
                if (longPressFired) {
                    releaseRightImmediate()
                } else if (!dragConvertedToWheel) {
                    releaseLeftImmediate()
                } else {
                    releaseLeftImmediate()
                }
                stamp("up", finger.viewX, finger.viewY, btn)
            }
            Style.TOUCHPAD -> {
                if (numFingers == 1 && !scrolling && finger.isTap()) {
                    notePadTap()
                }
                // two-finger is wheel only; do not treat two-finger tap as right click
            }
        }
        if (fingerLeft === finger) fingerLeft = null
        if (fingerRight === finger) {
            releaseRightImmediate()
        }
        if (longPressFinger === finger) cancelLongPressKeepRight()
    }

    private fun notePadTap() {
        handler.removeCallbacks(padTapCommit)
        padTapCount += 1
        if (padTapCount >= 3) {
            padTapArmed = false
            padTapCount = 0
            clickRightAtCursor()
            stampCursor("down", "right")
            stampCursor("up", "right")
            return
        }
        padTapArmed = true
        handler.postDelayed(padTapCommit, PAD_MULTI_TAP_MS)
    }

    private fun moveRelative(dx: Int, dy: Int) {
        // P1-Y: never WinHandler.mouseEvent / isRelativeMouseMovement.
        xServer.injectPointerMoveDelta(dx, dy)
    }

    private fun pressLeft(finger: Finger) {
        if (xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT)) return
        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT)
        fingerLeft = finger
    }

    private fun pressRight(finger: Finger) {
        if (xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_RIGHT)) return
        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT)
        fingerRight = finger
    }

    private fun releaseLeftImmediate() {
        fingerLeft = null
        if (xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT)) {
            xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
        }
    }

    private fun releaseRightImmediate() {
        fingerRight = null
        if (xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_RIGHT)) {
            xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT)
        }
    }

    private fun injectLeftDown() {
        if (!xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT)) {
            xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT)
        }
    }

    private fun injectLeftUp() {
        if (xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT)) {
            xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
        }
    }

    private fun clickLeftAtCursor() {
        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT)
        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
    }

    private fun clickRightAtCursor() {
        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT)
        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT)
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
    }

    private fun styleTag(): String = if (style == Style.TOUCHPAD) "pad" else "direct"

    private fun hwndAt(deskX: Int, deskY: Int): Long {
        return try {
            val w: Window? = xServer.windowManager.findPointWindow(
                deskX.toShort(),
                deskY.toShort(),
            )
            w?.getHandleOrXid() ?: 0L
        } catch (_: Throwable) {
            0L
        }
    }

    private fun stamp(action: String, viewX: Float, viewY: Float, btn: String) {
        val mapped = mapDesktop(viewX, viewY)
        val dx = mapped?.first ?: -1
        val dy = mapped?.second ?: -1
        val hwnd = if (dx >= 0) hwndAt(dx, dy) else 0L
        val btnPart = if (btn.isEmpty()) "" else " btn=$btn"
        onStamp?.invoke(
            "INPUT_TOUCH action=$action style=${styleTag()} " +
                "view=${viewX.toInt()},${viewY.toInt()} desk=$dx,$dy " +
                "hwnd=0x${java.lang.Long.toHexString(hwnd)}$btnPart",
        )
    }

    private fun stampCursor(action: String, btn: String) {
        val dx = xServer.pointer.clampedX.toInt()
        val dy = xServer.pointer.clampedY.toInt()
        val hwnd = hwndAt(dx, dy)
        onStamp?.invoke(
            "INPUT_TOUCH action=$action style=${styleTag()} " +
                "view=-1,-1 desk=$dx,$dy hwnd=0x${java.lang.Long.toHexString(hwnd)} btn=$btn",
        )
    }

    private fun isDuplicateDispatch(event: MotionEvent): Boolean {
        val key = (event.eventTime shl 16) xor
            (event.action.toLong() shl 8) xor
            event.pointerCount.toLong() xor
            event.x.toRawBits().toLong() xor
            (event.y.toRawBits().toLong() shl 32)
        if (key == lastDispatchKey) return true
        lastDispatchKey = key
        return false
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
            val mapped = DesktopLayout.viewToDesktop(vx, vy, blitRect, desktopW, desktopH)
            return if (mapped != null) mapped
            else {
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
        private const val MAX_TAP_MS = 250L
        private const val MAX_TWO_FINGER_SCROLL_DIST = 350f
        private const val LONG_PRESS_MS = 450L
        private const val PAD_MULTI_TAP_MS = 320L
        private const val WHEEL_STEP = 48f
        private const val MOVE_STAMP_MIN = 8
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
