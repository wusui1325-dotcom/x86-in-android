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

target_link_libraries(x86engine
    ${log-lib}
    ${android-lib}
    ${nativewindow-lib}
)

target_compile_options(x86engine PRIVATE -Wall -Wextra)
