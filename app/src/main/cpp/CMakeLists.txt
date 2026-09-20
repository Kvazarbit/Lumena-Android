cmake_minimum_required(VERSION 3.22.1)
project(lumena_llama LANGUAGES C CXX)

# This file is in <repo>/app/src/main/cpp: exactly four parents to <repo>.
get_filename_component(LUMENA_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
set(LLAMA_CPP_DIR "${LUMENA_ROOT}/third_party/llama.cpp" CACHE PATH "Pinned llama.cpp sources")
if(NOT EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "llama.cpp missing at ${LLAMA_CPP_DIR}. Run bash scripts/sync_llama_cpp.sh from ${LUMENA_ROOT}.")
endif()
message(STATUS "Lumena llama.cpp source: ${LLAMA_CPP_DIR}")

# Link CPU inference statically into the JNI library. Vulkan is enabled when
# the Android target Vulkan loader plus host shader tooling are available.
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

# Vulkan remains optional at configure time so local CPU-only builds still work.
# glslc and SPIRV-Headers are host build tools; Vulkan headers/library come from NDK.
find_program(LUMENA_GLSLC NAMES glslc NO_CMAKE_FIND_ROOT_PATH)
find_path(LUMENA_VULKAN_HPP_ROOT NAMES vulkan/vulkan.hpp NO_CMAKE_FIND_ROOT_PATH)
if(LUMENA_GLSLC)
    set(Vulkan_GLSLC_EXECUTABLE "${LUMENA_GLSLC}" CACHE FILEPATH "" FORCE)
endif()
find_package(SPIRV-Headers CONFIG QUIET NO_CMAKE_FIND_ROOT_PATH)
find_package(Vulkan QUIET)

set(LUMENA_VULKAN_HPP_INCLUDE "")
if(LUMENA_VULKAN_HPP_ROOT)
    set(LUMENA_VULKAN_HPP_INCLUDE "${CMAKE_BINARY_DIR}/lumena-vulkan-headers")
    file(MAKE_DIRECTORY "${LUMENA_VULKAN_HPP_INCLUDE}")
    file(COPY "${LUMENA_VULKAN_HPP_ROOT}/vulkan"
         DESTINATION "${LUMENA_VULKAN_HPP_INCLUDE}")
endif()

if(Vulkan_FOUND AND Vulkan_GLSLC_EXECUTABLE AND LUMENA_VULKAN_HPP_INCLUDE
   AND TARGET SPIRV-Headers::SPIRV-Headers)
    message(STATUS "Lumena: enabling llama.cpp Vulkan backend")
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
else()
    message(WARNING "Lumena: Vulkan build dependencies missing; CPU backend only")
    set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
endif()

add_subdirectory("${LLAMA_CPP_DIR}" "${CMAKE_BINARY_DIR}/llama.cpp" EXCLUDE_FROM_ALL)

if(TARGET ggml-vulkan AND LUMENA_VULKAN_HPP_INCLUDE)
    target_include_directories(ggml-vulkan PRIVATE "${LUMENA_VULKAN_HPP_INCLUDE}")
endif()

add_library(lumena_llama SHARED lumena_llama.cpp)
target_link_libraries(lumena_llama PRIVATE llama android log)
target_compile_features(lumena_llama PRIVATE cxx_std_17)
target_compile_options(lumena_llama PRIVATE -Wall -Wextra)
if(ANDROID)
    target_link_options(lumena_llama PRIVATE "-Wl,-z,max-page-size=16384")
endif()
