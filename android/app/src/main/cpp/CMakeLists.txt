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

# ── Native Core (JNI facade: strings + anti-analysis) ─────────────────────────
add_library(native_core SHARED native_core.cpp)
target_include_directories(native_core PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(native_core android log)
target_compile_features(native_core PRIVATE cxx_std_17)

# Optimisasi + strip di release (semua lib)
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    set_target_properties(guard       PROPERTIES LINK_FLAGS "-s")
    set_target_properties(dexcrypt    PROPERTIES LINK_FLAGS "-s")
    set_target_properties(native_core PROPERTIES LINK_FLAGS "-s")
endif()
