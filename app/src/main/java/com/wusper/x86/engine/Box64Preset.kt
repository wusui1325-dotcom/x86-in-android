package com.wusper.x86.engine

/**
 * Wine／PE 預設走 **interpreter（DYNAREC=0）**。
 * 0.1.29 已證 dynarec 非本機 SIGILL 主因；0.1.30 改打 x64lib／wine 包裝鏈。
 */
object Box64Preset {
    /**
     * Wine／PE 預設（0.1.30+）：DYNAREC=0 + NOHOSTEXT + ISA 廣告關 + Winlator COMPAT 向收斂。
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
        "BOX64_DYNAREC_X87DOUBLE=1",
        "BOX64_DYNAREC_WAIT=1",
        "BOX64_DYNAREC_ALIGNED_ATOMICS=1",
        "BOX64_AVX=0",
        "BOX64_SSE42=0",
        "BOX64_AES=0",
        "BOX64_SHAEXT=0",
        "BOX64_PCLMULQDQ=0",
        "BOX64_CPUTYPE=0",
        "BOX64_NORCFILES=1",
        "BOX64_X11GLX=1",
        "BOX64_LOG=1",
        "BOX64_SHOWSEGV=1",
        "BOX64_SHOWBT=1",
        "BOX64_ROLLING_LOG=1",
        "BOX64_NOBANNER=0",
    )

    val stability: Array<String> = performance

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
        "BOX64_DYNAREC_X87DOUBLE=1",
        "BOX64_DYNAREC_WAIT=1",
        "BOX64_DYNAREC_ALIGNED_ATOMICS=1",
        "BOX64_AVX=0",
        "BOX64_SSE42=0",
        "BOX64_AES=0",
        "BOX64_SHAEXT=0",
        "BOX64_PCLMULQDQ=0",
        "BOX64_CPUTYPE=0",
        "BOX64_NORCFILES=1",
        "BOX64_X11GLX=1",
        "BOX64_LOG=1",
        "BOX64_SHOWSEGV=1",
        "BOX64_SHOWBT=1",
        "BOX64_ROLLING_LOG=1",
        "BOX64_NOBANNER=0",
    )
}
