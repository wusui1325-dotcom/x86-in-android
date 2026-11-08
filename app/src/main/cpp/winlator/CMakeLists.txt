cmake_minimum_required(VERSION 3.22.1)
# Vendored from brunodev85/winlator-app (LGPL-2.1) — X connector / drawable / shm / GPUImage
add_library(winlator SHARED
    src/drawable.c
    src/gpu_image.c
    src/gpu_helper.c
    src/sysvshared_memory.c
    src/xconnector_epoll.c
    src/xinput_stream.c
    src/xoutput_stream.c
    src/arrays.c
    src/ring_buffer.c
)
target_include_directories(winlator PRIVATE include)
find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)
find_library(EGL-lib EGL)
find_library(GLESv2-lib GLESv2)
find_library(GLESv3-lib GLESv3)
target_link_libraries(winlator
    ${log-lib}
    ${android-lib}
    ${jnigraphics-lib}
    ${EGL-lib}
    ${GLESv2-lib}
    ${GLESv3-lib}
)
target_compile_options(winlator PRIVATE -O2 -Wno-unused-function -D_POSIX_C_SOURCE=200809L)
