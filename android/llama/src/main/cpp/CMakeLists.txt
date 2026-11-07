cmake_minimum_required(VERSION 3.22.1)
project(meow_llama C CXX)

set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party/llama.cpp)

# Только библиотека: без тулов, тестов, сервера и сетевого кода.
set(BUILD_SHARED_LIBS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP       OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL         OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL            OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE           OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP           OFF CACHE BOOL "" FORCE)

# Наборы инструкций. Без них ggml собирается под «голый» baseline и работает в разы медленнее.
# arm64: dotprod + fp16 есть на всех актуальных телефонах и на Apple Silicon (эмулятор на Mac).
# x86_64: AVX2/FMA/F16C — любой x86 с 2013 года; эмулятор пробрасывает инструкции хоста.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(GGML_SSE42 ON CACHE BOOL "" FORCE)
    set(GGML_AVX   ON CACHE BOOL "" FORCE)
    set(GGML_AVX2  ON CACHE BOOL "" FORCE)
    set(GGML_FMA   ON CACHE BOOL "" FORCE)
    set(GGML_F16C  ON CACHE BOOL "" FORCE)
    set(GGML_BMI2  ON CACHE BOOL "" FORCE)
endif()

add_subdirectory(${LLAMA_DIR} llama-build EXCLUDE_FROM_ALL)

add_library(meow_llama SHARED llama_jni.cpp)
target_link_libraries(meow_llama PRIVATE llama ggml android log)
target_link_options(meow_llama PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--gc-sections")
