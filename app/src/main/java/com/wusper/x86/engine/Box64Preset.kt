package com.wusper.x86.engine

/**
 * Boss 硬指標：同機原生 arm64 ≥70% 為效能目標。
 *
 * SIGILL（exit 132）防線：
 * 1) 客體 ISA 廣告全關（AVX／SSE42／AES／SHA／PCLMUL）— 對齊 Winlator safe
 * 2) BOX64_DYNAREC_NOHOSTEXT=1 — dynarec **只**用 ASIMD，不發射宿主 LSE／FLAGM／AES…
 *    （getauxval HWCAP 在 PRoot／部分 SoC 若誤報，會打出 CPU 沒有的指令 → 宿主 SIGILL）
 */
object Box64Preset {
    /** Wine／PE 預設：安全優先（0.1.28+） */
    val performance: Array<String> = arrayOf(
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

    /** 更保守／除錯 */
    val stability: Array<String> = arrayOf(
        "BOX64_DYNAREC=0",
        "BOX64_DYNAREC_NOHOSTEXT=1",
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
