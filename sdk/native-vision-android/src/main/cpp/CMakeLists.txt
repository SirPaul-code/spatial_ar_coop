cmake_minimum_required(VERSION 3.22)
project(StableARVisionAndroid LANGUAGES C CXX)
# The official org.opencv:opencv Android AAR exposes Prefab package `OpenCV`
# with module `opencv_java4`. Its native binary requires c++_shared; that STL
# selection is configured by this Android module's Gradle build.
find_package(OpenCV REQUIRED CONFIG)
set(STABLEAR_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(STABLEAR_VISION_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(STABLEAR_VISION_BUILD_OPENCV ON CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_LIST_DIR}/../../../../native-vision ${CMAKE_CURRENT_BINARY_DIR}/stablear-native-vision)
add_library(stablear_vision_jni SHARED stablear_vision_jni.cpp)
target_link_libraries(stablear_vision_jni PRIVATE StableAR::vision StableAR::xfeat log)
target_compile_features(stablear_vision_jni PRIVATE cxx_std_20)
