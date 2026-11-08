cmake_minimum_required(VERSION 3.22.1)
# Vendored from brunodev85/winlator-app (LGPL-2.1) — Gladio OpenGL→GLES for GLX
project(GladioRenderer)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wno-unused-function -Wno-implicit-function-declaration")

include_directories(include)
include_directories(../winlator/include)

add_library(gladiorenderer SHARED
    src/main.c
    src/gl_context.c
    src/gl_renderer.c
    src/request_handler.c
    src/shader_material.c
    src/shader_converter.c
    src/gl_formats.c
    src/gl_texture.c
    src/attrib_stack.c
    src/gl_query.c
    src/compressed_texture.c
    src/arb_program.c
    src/gl_buffer.c
    src/gl_vao.c
    src/gl_framebuffer.c
)

find_library(log-lib log)
find_library(android-lib android)
find_library(EGL-lib EGL)
find_library(GLESv2-lib GLESv2)
find_library(GLESv3-lib GLESv3)

target_link_libraries(gladiorenderer
    winlator
    ${log-lib}
    ${android-lib}
    ${EGL-lib}
    ${GLESv2-lib}
    ${GLESv3-lib}
)
