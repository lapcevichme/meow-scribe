cmake_minimum_required(VERSION 3.22.1)

# llama.cpp всегда собираем с оптимизациями: в Debug инференс медленнее в десятки раз.
if(NOT CMAKE_BUILD_TYPE OR CMAKE_BUILD_TYPE STREQUAL "Debug")
    set(CMAKE_BUILD_TYPE Release)
endif()

project(meowllm C CXX)

include(FetchContent)

# Версия llama.cpp зафиксирована: API меняется часто.
set(LLAMA_CPP_TAG b12176)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE ON CACHE BOOL "" FORCE)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    # dotprod/fp16 есть на всех ARM-устройствах последних лет и в ARM-эмуляторе на Apple Silicon.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(GGML_AVX ON CACHE BOOL "" FORCE)
    set(GGML_AVX2 ON CACHE BOOL "" FORCE)
    set(GGML_FMA ON CACHE BOOL "" FORCE)
    set(GGML_F16C ON CACHE BOOL "" FORCE)
endif()

FetchContent_Declare(
    llama_cpp
    URL https://github.com/ggml-org/llama.cpp/archive/refs/tags/${LLAMA_CPP_TAG}.tar.gz
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
FetchContent_MakeAvailable(llama_cpp)

add_library(meowllm SHARED llama_jni.cpp)
target_compile_features(meowllm PRIVATE cxx_std_17)
target_link_libraries(meowllm PRIVATE llama ggml log android)
# 16 KB page size (обязательно для Android 15+)
target_link_options(meowllm PRIVATE "-Wl,-z,max-page-size=16384")
