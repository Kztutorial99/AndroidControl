cmake_minimum_required(VERSION 3.22.1)
project("androidconnector")

# ── Guard library ─────────────────────────────────────────────────────────────
add_library(guard SHARED guard.cpp)
target_include_directories(guard PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(guard android log)

# ── DexCrypt library (CDC v2) ─────────────────────────────────────────────────
add_library(dexcrypt SHARED dex_crypt.cpp)
target_include_directories(dexcrypt PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(dexcrypt android log)

# Optimisasi: strip symbols di release
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    set_target_properties(guard    PROPERTIES LINK_FLAGS "-s")
    set_target_properties(dexcrypt PROPERTIES LINK_FLAGS "-s")
endif()
