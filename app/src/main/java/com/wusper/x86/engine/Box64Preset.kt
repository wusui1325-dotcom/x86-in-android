package com.wusper.x86.engine

/**
 * Wine／PE 預設走 **dynarecSafe（DYNAREC=1 + NOHOSTEXT）**；日誌預設安靜（BOX64_LOG=0）。
 * 0.1.37：配合 wine64k-align-0x100000；除錯時再開 BOX64_LOG=1。
 * 0.1.99：NORCFILES=0 — wine-launch BOX64_RCFILE [app.exe] child-only MMAP32；
 * parent 仍 BOX64_MMAP32=0（勿在 preset 設 MMAP32=1）。
 */
object Box64Preset {
    /**
     * Wine／PE 預設（0.1.37+）：DYNAREC=1 + NOHOSTEXT + 安靜 log + Winlator COMPAT。
     */
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
        "BOX64_DYNAREC_X87DOUBLE=1",
        "BOX64_DYNAREC_WAIT=1",
        "BOX64_DYNAREC_ALIGNED_ATOMICS=1",
        "BOX64_AVX=0",
        "BOX64_SSE42=0",
        "BOX64_AES=0",
        "BOX64_SHAEXT=0",
        "BOX64_PCLMULQDQ=0",
        "BOX64_CPUTYPE=0",
        "BOX64_MMAP32=0",
        "BOX64_NORCFILES=0",
        "BOX64_X11GLX=1",
        "BOX64_LOG=0",
        "BOX64_SHOWSEGV=0",
        "BOX64_SHOWBT=0",
        "BOX64_ROLLING_LOG=0",
        "BOX64_NOBANNER=1",
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
        "BOX64_MMAP32=0",
        "BOX64_NORCFILES=0",
        "BOX64_X11GLX=1",
        "BOX64_LOG=0",
        "BOX64_SHOWSEGV=0",
        "BOX64_SHOWBT=0",
        "BOX64_ROLLING_LOG=0",
        "BOX64_NOBANNER=1",
    )
}
