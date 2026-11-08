#include "elf_probe.h"
#include <cstdio>
#include <cstring>

ElfKind probe_elf(const char* path, char* out_msg, size_t out_len) {
    if (!path || !out_msg || out_len == 0) return ElfKind::kUnknown;
    out_msg[0] = '\0';
    FILE* f = fopen(path, "rb");
    if (!f) {
        snprintf(out_msg, out_len, "無法開啟：%s", path);
        return ElfKind::kUnknown;
    }
    unsigned char hdr[64] = {0};
    size_t n = fread(hdr, 1, sizeof(hdr), f);
    fclose(f);
    if (n < 20) {
        snprintf(out_msg, out_len, "檔案過短，非 ELF");
        return ElfKind::kUnknown;
    }
    // PE MZ
    if (hdr[0] == 'M' && hdr[1] == 'Z') {
        snprintf(out_msg, out_len, "Windows PE（需 Wine，P2）");
        return ElfKind::kPe;
    }
    if (!(hdr[0] == 0x7f && hdr[1] == 'E' && hdr[2] == 'L' && hdr[3] == 'F')) {
        snprintf(out_msg, out_len, "非 ELF／PE");
        return ElfKind::kUnknown;
    }
    int cls = hdr[4]; // 1=32 2=64
    int endian = hdr[5];
    unsigned short machine = (unsigned short)hdr[18] | ((unsigned short)hdr[19] << 8);
    if (endian == 2) { // big-endian — rare
        machine = (unsigned short)hdr[19] | ((unsigned short)hdr[18] << 8);
    }
    if (cls == 2 && machine == 0x3E) {
        snprintf(out_msg, out_len, "ELF x86_64（需 Box64）");
        return ElfKind::kElfX86_64;
    }
    if (cls == 1 && machine == 0x03) {
        snprintf(out_msg, out_len, "ELF i386（需 Box86／BOX32）");
        return ElfKind::kElfX86;
    }
    if (cls == 2 && machine == 0xB7) {
        snprintf(out_msg, out_len, "ELF AArch64（宿主可直接 exec）");
        return ElfKind::kElfAarch64;
    }
    snprintf(out_msg, out_len, "ELF class=%d machine=0x%04x", cls, machine);
    return ElfKind::kElfOther;
}
