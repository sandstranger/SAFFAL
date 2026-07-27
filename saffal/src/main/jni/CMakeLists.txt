cmake_minimum_required(VERSION 3.22.1)

project(saffal LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

find_package(shadowhook REQUIRED CONFIG)

set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)

add_library(saffal SHARED
        FileSAF.cpp
        FileJNI.cpp
        FileCache.cpp
        Utils.cpp
)

target_include_directories(saffal PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
)

find_library(LOG_LIB log)
find_library(DL_LIB dl)

target_link_libraries(saffal
        PRIVATE
        shadowhook::shadowhook
        ${LOG_LIB}
        ${DL_LIB}
)