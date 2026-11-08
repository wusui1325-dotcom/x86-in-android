package com.wusper.x86.graphics

import android.graphics.Rect

/** 與 Wine 虛擬桌面／Xvfb 對齊的預設解析度。 */
object DesktopLayout {
    const val WIDTH = 1280
    const val HEIGHT = 720

    fun fitRect(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Rect {
        if (srcW <= 0 || srcH <= 0) return Rect(0, 0, dstW, dstH)
        val scale = minOf(dstW.toFloat() / srcW, dstH.toFloat() / srcH)
        val w = (srcW * scale).toInt().coerceAtLeast(1)
        val h = (srcH * scale).toInt().coerceAtLeast(1)
        val left = (dstW - w) / 2
        val top = (dstH - h) / 2
        return Rect(left, top, left + w, top + h)
    }

    /**
     * Surface 視圖座標 → 桌面絕對座標。
     * @return null 表示點在 letterbox 外
     */
    fun viewToDesktop(
        viewX: Float,
        viewY: Float,
        blitRect: Rect,
        deskW: Int = WIDTH,
        deskH: Int = HEIGHT,
    ): Pair<Int, Int>? {
        if (blitRect.width() <= 0 || blitRect.height() <= 0) return null
        if (viewX < blitRect.left || viewX > blitRect.right ||
            viewY < blitRect.top || viewY > blitRect.bottom
        ) {
            return null
        }
        val nx = (viewX - blitRect.left) / blitRect.width().toFloat()
        val ny = (viewY - blitRect.top) / blitRect.height().toFloat()
        val x = (nx * deskW).toInt().coerceIn(0, deskW - 1)
        val y = (ny * deskH).toInt().coerceIn(0, deskH - 1)
        return x to y
    }
}
