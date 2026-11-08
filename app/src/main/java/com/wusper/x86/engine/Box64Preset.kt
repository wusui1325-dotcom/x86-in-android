package com.wusper.x86.engine

/**
 * Boss 硬指標：同機原生 arm64 ≥70% 為效能目標。
 *
 * Wine／PE 預設必須對齊 Winlator：關閉 AVX。AVX=1 時客體 libc／Wine 走 AVX 路徑，
 * Box64 dynarec 在部分 SoC 會產生宿主 Illegal instruction（exit 132／SIGILL）。
 */
object Box64Preset {
    /** 效能向但 Wine 安全（P0 預設；AVX 關閉） */
    val performance: Array<String> = arrayOf(
        "BOX64_DYNAREC=1",
        "BOX64_DYNAREC_FASTNAN=1",
        "BOX64_DYNAREC_FASTROUND=1",
        "BOX64_DYNAREC_CALLRET=1",
        "BOX64_DYNAREC_STRONGMEM=1",
        "BOX64_DYNAREC_SAFEFLAGS=1",
        "BOX64_DYNAREC_BIGBLOCK=1",
        "BOX64_AVX=0",
        "BOX64_SSE42=1",
        "BOX64_LOG=0",
        "BOX64_NOBANNER=1",
    )

    /** 穩定向（除錯／異常程式） */
    val stability: Array<String> = arrayOf(
        "BOX64_DYNAREC=1",
        "BOX64_DYNAREC_FASTNAN=0",
        "BOX64_DYNAREC_FASTROUND=0",
        "BOX64_DYNAREC_CALLRET=0",
        "BOX64_DYNAREC_STRONGMEM=2",
        "BOX64_DYNAREC_SAFEFLAGS=2",
        "BOX64_DYNAREC_BIGBLOCK=0",
        "BOX64_AVX=0",
        "BOX64_SSE42=0",
        "BOX64_LOG=1",
        "BOX64_SHOWSEGV=1",
        "BOX64_NOBANNER=1",
    )
}
