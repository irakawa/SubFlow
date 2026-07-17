cmake_minimum_required(VERSION 3.22)
project(subflow_whisper CXX)

# whisper.cpp statik olarak derlenir, JNI köprüsüyle tek .so'da paketlenir
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp whisper_build)

add_library(subflow_whisper SHARED subflow_whisper.cpp)

target_include_directories(subflow_whisper PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)
target_link_libraries(subflow_whisper whisper log android)
