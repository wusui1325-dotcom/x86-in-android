package com.wusper.x86.engine

enum class BinaryKind {
    Unknown,
    Pe,
    ElfX86_64,
    ElfX86,
    ElfAarch64,
    ElfOther;

    companion object {
        fun fromNative(code: Int): BinaryKind = when (code) {
            NativeEngine.KIND_PE -> Pe
            NativeEngine.KIND_ELF_X86_64 -> ElfX86_64
            NativeEngine.KIND_ELF_X86 -> ElfX86
            NativeEngine.KIND_ELF_AARCH64 -> ElfAarch64
            NativeEngine.KIND_ELF_OTHER -> ElfOther
            else -> Unknown
        }
    }
}
