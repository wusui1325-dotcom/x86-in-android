package com.wusper.x86.engine

/**
 * Boss 硬指標：同機原生 arm64 ≥70% 為效能目標。
 * 預設走 Dynarec 效能向參數（犧牲部分正確性邊緣行為）。
 */
object Box64Preset {
    /** 效能向（P0 預設） */
    val performance: Array<String> = arrayOf(
        "BOX64_DYNAREC=1",
        "BOX64_DYNAREC_FASTNAN=1",
        "BOX64_DYNAREC_FASTROUND=1",
        "BOX64_DYNAREC_CALLRET=1",
        "BOX64_DYNAREC_STRONGMEM=0",
        "BOX64_DYNAREC_SAFEFLAGS=1",
        "BOX64_AVX=1",
        "BOX64_LOG=0",
        "BOX64_NOBANNER=1",
    )

    /** 穩定向（除錯／異常程式） */
    val stability: Array<String> = arrayOf(
        "BOX64_DYNAREC=1",
        "BOX64_DYNAREC_STRONGMEM=1",
        "BOX64_DYNAREC_SAFEFLAGS=2",
        "BOX64_LOG=1",
        "BOX64_NOBANNER=1",
    )
}
