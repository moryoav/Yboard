cmake_minimum_required(VERSION 3.22.1)
project(yboard_qwen LANGUAGES C CXX)
include(FetchContent)

# Immutable source archive. FetchContent stores it in the ignored native build directory.
FetchContent_Declare(llama
    URL https://codeload.github.com/ggml-org/llama.cpp/tar.gz/2d8a32502909ca065c41c4cf5f2fb1c427c047fc
    URL_HASH SHA256=7befec5f2a7fbd7fc69b69e4f80c9824a64cb760cd1b18aa0e1a6b1af53de7d8)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
# Android's toolchain overrides the debug flag cache. Apply optimization to the
# runtime's child directories too, otherwise debug inference runs unoptimized.
add_compile_options(-O3)
set(CMAKE_C_FLAGS_DEBUG "-O3 -g0" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -g0" CACHE STRING "" FORCE)
# Avoid exhausting this development machine's memory on the first two-ABI build.
set_property(GLOBAL PROPERTY JOB_POOLS native_compile=2)
set(CMAKE_JOB_POOL_COMPILE native_compile)
FetchContent_MakeAvailable(llama)

add_library(yboard_qwen SHARED qwen_jni.cpp)
target_compile_features(yboard_qwen PRIVATE cxx_std_17)
target_compile_options(yboard_qwen PRIVATE -O3 -Wall -Wextra)
target_link_options(yboard_qwen PRIVATE -Wl,-z,max-page-size=16384)
target_link_libraries(yboard_qwen PRIVATE llama android log)
