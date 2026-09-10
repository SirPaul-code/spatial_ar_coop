cmake_minimum_required(VERSION 3.22)
project(StableARSdk VERSION 0.2.0 LANGUAGES C CXX)

option(STABLEAR_SDK_WITH_VISION "Build the optional OpenCV visual front-end" OFF)
option(STABLEAR_SDK_WITH_OPENXR "Build the optional OpenXR adapter" OFF)

add_subdirectory(native)
if(STABLEAR_SDK_WITH_VISION)
  add_subdirectory(native-vision)
endif()
if(STABLEAR_SDK_WITH_OPENXR)
  add_subdirectory(openxr)
endif()
