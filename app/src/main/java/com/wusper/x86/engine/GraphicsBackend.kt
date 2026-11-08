package com.wusper.x86.engine

/**
 * 圖形後端（Boss 定案 A · 2026-11-09）：
 * - [GpuStack]：**預設**。Turnip／DXVK／真 Surface（Winlator 級），禁止當預設走 Xvfb 軟渲。
 * - [SoftLegacy]：工程保底（Xvfb→XWD→blit）。需顯式 forceSoftLegacy；產品路徑不再預設啟用。
 */
enum class GraphicsBackend {
    GpuStack,
    SoftLegacy,
}
