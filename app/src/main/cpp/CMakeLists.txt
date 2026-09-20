cmake_minimum_required(VERSION 3.22.1)
project(lumena_llama LANGUAGES C CXX)

# This file is in <repo>/app/src/main/cpp: exactly four parents to <repo>.
get_filename_component(LUMENA_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
set(LLAMA_CPP_DIR "${LUMENA_ROOT}/third_party/llama.cpp" CACHE PATH "Pinned llama.cpp sources")
if(NOT EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "llama.cpp missing at ${LLAMA_CPP_DIR}. Run bash scripts/sync_llama_cpp.sh from ${LUMENA_ROOT}.")
endif()
message(STATUS "Lumena llama.cpp source: ${LLAMA_CPP_DIR}")

# Link CPU inference statically into the JNI library. No server, CLI or SSL needed.
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)

add_subdirectory("${LLAMA_CPP_DIR}" "${CMAKE_BINARY_DIR}/llama.cpp" EXCLUDE_FROM_ALL)

add_library(lumena_llama SHARED lumena_llama.cpp)
target_link_libraries(lumena_llama PRIVATE llama android log)
target_compile_features(lumena_llama PRIVATE cxx_std_17)
target_compile_options(lumena_llama PRIVATE -Wall -Wextra)
if(ANDROID)
    target_link_options(lumena_llama PRIVATE "-Wl,-z,max-page-size=16384")
endif()
