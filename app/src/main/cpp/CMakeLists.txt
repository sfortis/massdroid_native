cmake_minimum_required(VERSION 3.22.1)
project(acoustic_calibrator LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(oboe REQUIRED CONFIG)

add_library(acoustic_calibrator SHARED
    acoustic_calibrator_jni.cpp
    calibration_engine.cpp
    tone_generator.cpp
    dsp_pipeline.cpp
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
