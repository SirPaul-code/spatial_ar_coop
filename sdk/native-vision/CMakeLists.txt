cmake_minimum_required(VERSION 3.20)
project(StableARVision VERSION 0.2.0 LANGUAGES C CXX)
option(STABLEAR_VISION_BUILD_SHARED "Build StableAR vision as shared library" OFF)
option(STABLEAR_VISION_BUILD_TESTS "Build StableAR vision smoke tests" ON)

if(NOT TARGET StableAR::stablear)
  set(STABLEAR_BUILD_TESTS OFF CACHE BOOL "" FORCE)
  add_subdirectory(../native stablear-native EXCLUDE_FROM_ALL)
endif()

if(TARGET opencv::opencv_java4)
  set(STABLEAR_OPENCV_LIBS opencv::opencv_java4)
elseif(TARGET OpenCV::opencv_java4)
  set(STABLEAR_OPENCV_LIBS OpenCV::opencv_java4)
elseif(TARGET opencv_java4)
  set(STABLEAR_OPENCV_LIBS opencv_java4)
else()
  find_package(OpenCV 4 REQUIRED COMPONENTS core imgproc video features2d calib3d)
  set(STABLEAR_OPENCV_LIBS ${OpenCV_LIBS})
endif()

set(_kind STATIC)
if(STABLEAR_VISION_BUILD_SHARED)
  set(_kind SHARED)
endif()
add_library(stablear_vision ${_kind}
  src/vision.cpp
  src/vision_c.cpp
  src/xfeat.cpp
  src/xfeat_tracker.cpp
  src/xfeat_c.cpp)
add_library(StableAR::vision ALIAS stablear_vision)
target_include_directories(stablear_vision PUBLIC
  $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
  $<INSTALL_INTERFACE:include>)
target_link_libraries(stablear_vision PUBLIC StableAR::stablear ${STABLEAR_OPENCV_LIBS})
target_compile_features(stablear_vision PUBLIC cxx_std_20)
if(STABLEAR_VISION_BUILD_SHARED)
  target_compile_definitions(stablear_vision PRIVATE STABLEAR_VISION_BUILDING_DLL)
endif()
include(GNUInstallDirs)
install(TARGETS stablear_vision ARCHIVE DESTINATION ${CMAKE_INSTALL_LIBDIR} LIBRARY DESTINATION ${CMAKE_INSTALL_LIBDIR} RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR})
install(DIRECTORY include/ DESTINATION ${CMAKE_INSTALL_INCLUDEDIR})
if(STABLEAR_VISION_BUILD_TESTS)
  enable_testing()
  add_executable(stablear_vision_c_smoke tests/c_smoke.c)
  set_target_properties(stablear_vision_c_smoke PROPERTIES LINKER_LANGUAGE CXX)
  target_link_libraries(stablear_vision_c_smoke PRIVATE stablear_vision)
  add_test(NAME stablear_vision_c_smoke COMMAND stablear_vision_c_smoke)

  add_executable(stablear_xfeat_contract tests/xfeat_contract.cpp)
  target_link_libraries(stablear_xfeat_contract PRIVATE stablear_vision)
  add_test(NAME stablear_xfeat_contract COMMAND stablear_xfeat_contract)
endif()
