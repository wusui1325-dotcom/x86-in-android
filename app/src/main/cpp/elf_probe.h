#pragma once
#include <cstddef>

enum class ElfKind {
    kUnknown = 0,
    kPe = 1,
    kElfX86_64 = 2,
    kElfX86 = 3,
    kElfAarch64 = 4,
    kElfOther = 5,
};

ElfKind probe_elf(const char* path, char* out_msg, size_t out_len);
