package com.wusper.x86.engine

/**
 * Wine／PE 預設走 **interpreter（DYNAREC=0）** 直到 whoami／explorer 冒煙過關
 * （找茬 R1：dynarec 發射非法宿主指令 → exit 132）。Winlator safe 風格。
 *
 * 效能向 preset 仍保留給日後 ELF microbench／通過冒煙後再切回。
 */
object Box64Preset {
    /**
     * Wine／PE 預設（0.1.29+）：核選項 DYNAREC=0 + 客體 ISA 全關 + NOHOSTEXT。
     * 慢，但先證明能過 SIGILL。
     */
    val performance: Array<String> = arrayOf(
        "BOX64_DYNAREC=0",
        "BOX64_DYNAREC_NOHOSTEXT=1",
        "BOX64_DYNAREC_NATIVEFLAGS=0",
        "BOX64_DYNAREC_FASTNAN=0",
        "BOX64_DYNAREC_FASTROUND=0",
        "BOX64_DYNAREC_CALLRET=0",
        "BOX64_DYNAREC_STRONGMEM=1",
        "BOX64_DYNAREC_SAFEFLAGS=2",
        "BOX64_DYNAREC_BIGBLOCK=0",
        "BOX64_AVX=0",
        "BOX64_SSE42=0",
        "BOX64_AES=0",
        "BOX64_SHAEXT=0",
        "BOX64_PCLMULQDQ=0",
        "BOX64_LOG=1",
        "BOX64_SHOWSEGV=1",
        "BOX64_SHOWBT=1",
        "BOX64_NOBANNER=0",
    )

    /** 同 performance（Wine 冒煙期）；日後可再分叉 */
    val stability: Array<String> = performance

    /** ELF microbench／通過 SIGILL 後可改回的 dynarec 安全檔 */
    val dynarecSafe: Array<String> = arrayOf(
        "BOX64_DYNAREC=1",
        "BOX64_DYNAREC_NOHOSTEXT=1",
        "BOX64_DYNAREC_NATIVEFLAGS=0",
        "BOX64_DYNAREC_FASTNAN=0",
        "BOX64_DYNAREC_FASTROUND=0",
        "BOX64_DYNAREC_CALLRET=0",
        "BOX64_DYNAREC_STRONGMEM=1",
        "BOX64_DYNAREC_SAFEFLAGS=2",
        "BOX64_DYNAREC_BIGBLOCK=0",
        "BOX64_AVX=0",
        "BOX64_SSE42=0",
        "BOX64_AES=0",
        "BOX64_SHAEXT=0",
        "BOX64_PCLMULQDQ=0",
        "BOX64_LOG=1",
        "BOX64_SHOWSEGV=1",
        "BOX64_SHOWBT=1",
        "BOX64_NOBANNER=0",
    )
}
