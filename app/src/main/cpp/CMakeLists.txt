cmake_minimum_required(VERSION 3.22.1)
project(x86engine)

add_subdirectory(winlator)

add_library(x86engine SHARED
    native_engine.cpp
    elf_probe.cpp
    present_engine.cpp
)

find_library(log-lib log)
find_library(android-lib android)
find_library(nativewindow-lib nativewindow)
find_library(EGL-lib EGL)
find_library(GLESv2-lib GLESv2)

target_link_libraries(x86engine
    ${log-lib}
    ${android-lib}
    ${nativewindow-lib}
    ${EGL-lib}
    ${GLESv2-lib}
)

target_compile_options(x86engine PRIVATE -Wall -Wextra)
