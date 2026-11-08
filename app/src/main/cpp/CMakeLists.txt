cmake_minimum_required(VERSION 3.22.1)
project(x86engine)

add_library(x86engine SHARED
    native_engine.cpp
    elf_probe.cpp
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(x86engine
    ${log-lib}
    ${android-lib}
)

target_compile_options(x86engine PRIVATE -Wall -Wextra)
