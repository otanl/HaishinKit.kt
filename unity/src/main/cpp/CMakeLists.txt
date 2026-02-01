cmake_minimum_required(VERSION 3.10)

project(NativeTexturePlugin)

set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Add the shared library
add_library(NativeTexturePlugin SHARED
    NativeTexturePlugin.cpp
)

# Include directories
target_include_directories(NativeTexturePlugin PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# Link libraries
target_link_libraries(NativeTexturePlugin
    android
    log
    EGL
    GLESv2
)

# Compiler flags
target_compile_options(NativeTexturePlugin PRIVATE
    -Wall
    -Wextra
    -O2
    -fvisibility=hidden
)

# Export only necessary symbols
set_target_properties(NativeTexturePlugin PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)
