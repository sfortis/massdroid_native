cmake_minimum_required(VERSION 3.22.1)
project(acoustic_calibrator LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Reproducible builds: the libraries must not depend on where the tree is checked out or
# where the NDK is installed. Without this the source, build and NDK directories are
# written into the debug info and, through it, the build ID, so F-Droid's build never
# matched the published APK.
add_compile_options(
    "-ffile-prefix-map=${CMAKE_SOURCE_DIR}=."
    "-ffile-prefix-map=${CMAKE_BINARY_DIR}=."
    "-ffile-prefix-map=${ANDROID_NDK}=/ndk"
)

find_package(oboe REQUIRED CONFIG)

# The Oboe headers come from the Gradle cache, whose path differs between machines.
get_target_property(OBOE_INCLUDE_DIRS oboe::oboe INTERFACE_INCLUDE_DIRECTORIES)
foreach(OBOE_INCLUDE_DIR IN LISTS OBOE_INCLUDE_DIRS)
    add_compile_options("-ffile-prefix-map=${OBOE_INCLUDE_DIR}=/oboe/include")
endforeach()

add_library(acoustic_calibrator SHARED
    acoustic_calibrator_jni.cpp
    calibration_engine.cpp
)

target_link_libraries(acoustic_calibrator
    oboe::oboe
    android
    log
)

# GC-immune Sendspin audio output (Oboe callback + lock-free PCM ring).
add_library(sendspin_output SHARED
    sendspin_output_jni.cpp
    sendspin_output_engine.cpp
)

target_link_libraries(sendspin_output
    oboe::oboe
    android
    log
)
