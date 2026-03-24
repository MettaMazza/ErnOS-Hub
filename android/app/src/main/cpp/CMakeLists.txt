cmake_minimum_required(VERSION 3.22.1)
project(ernos_llama CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── llama.cpp configuration ──────────────────────────────────────────────────
# llama.cpp is a git submodule at: app/src/main/cpp/llama.cpp
# Run: git submodule update --init --recursive
# to initialise it before building.

set(LLAMA_NATIVE              OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS         OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER        OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP               OFF CACHE BOOL "" FORCE)
set(GGML_METAL                OFF CACHE BOOL "" FORCE)
set(GGML_CUDA                 OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN               OFF CACHE BOOL "" FORCE)
set(GGML_BLAS                 OFF CACHE BOOL "" FORCE)
# Enable NEON SIMD for arm64
set(GGML_NEON                 ON  CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp)

# ── JNI bridge library ───────────────────────────────────────────────────────
add_library(llama_bridge SHARED llama_bridge.cpp)

target_include_directories(llama_bridge PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/include
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/ggml/include
)

target_link_libraries(llama_bridge
    llama
    ggml
    android
    log
)

target_compile_options(llama_bridge PRIVATE
    -O3
    -ffast-math
    -march=armv8-a+fp16+dotprod
)
