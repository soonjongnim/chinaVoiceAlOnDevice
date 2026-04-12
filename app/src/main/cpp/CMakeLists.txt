cmake_minimum_required(VERSION 3.10.2)

project("stt")

# Add sherpa-onnx-c-api library
add_library(sherpa-onnx-c-api SHARED IMPORTED)
set_target_properties(sherpa-onnx-c-api PROPERTIES IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libsherpa-onnx-c-api.so)

include_directories(${CMAKE_SOURCE_DIR})

add_library(
        stt
        SHARED
        stt.cpp)

find_library(
        log-lib
        log)

# Link native library against log, sherpa-onnx-c-api
target_link_libraries(
        stt
        ${log-lib}
        sherpa-onnx-c-api
)
