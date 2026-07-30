cmake_minimum_required(VERSION 3.22.1)
project("guard")

add_library(guard SHARED guard.cpp)

target_include_directories(guard PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

if(CMAKE_BUILD_TYPE STREQUAL "Release")
    target_compile_options(guard PRIVATE -O3 -fvisibility=hidden -ffunction-sections -fdata-sections)
    target_link_options(guard PRIVATE -Wl,--strip-all -Wl,--gc-sections)
endif()

find_library(log-lib log)
target_link_libraries(guard ${log-lib})
