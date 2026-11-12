cmake_minimum_required(VERSION 3.18)
project(airplayjni C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

set(ALAC_DIR ${CMAKE_CURRENT_SOURCE_DIR}/alac)
add_library(alac_dec STATIC
        ${ALAC_DIR}/ALACDecoder.cpp
        ${ALAC_DIR}/ALACBitUtilities.c
        ${ALAC_DIR}/EndianPortable.c
        ${ALAC_DIR}/ag_dec.c
        ${ALAC_DIR}/dp_dec.c
        ${ALAC_DIR}/matrix_dec.c)
target_include_directories(alac_dec PUBLIC ${ALAC_DIR})
target_compile_definitions(alac_dec PRIVATE TARGET_OS_GENERIC=1 TARGET_RT_LITTLE_ENDIAN=1)
set_target_properties(alac_dec PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_library(alacjni SHARED alac_decoder_jni.cpp)
target_link_libraries(alacjni alac_dec)

set(PREBUILT ${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/${ANDROID_ABI})
if(NOT EXISTS ${PREBUILT}/lib/libcrypto.a)
    message(FATAL_ERROR "missing prebuilt deps for ${ANDROID_ABI} at ${PREBUILT}")
endif()

add_library(crypto STATIC IMPORTED)
set_target_properties(crypto PROPERTIES IMPORTED_LOCATION ${PREBUILT}/lib/libcrypto.a)

add_library(plist STATIC IMPORTED)
set_target_properties(plist PROPERTIES IMPORTED_LOCATION ${PREBUILT}/lib/libplist-2.0.a)

file(GLOB AIRPLAY_SRC
        ${CMAKE_CURRENT_SOURCE_DIR}/airplay/*.c
        ${CMAKE_CURRENT_SOURCE_DIR}/airplay/playfair/*.c
        ${CMAKE_CURRENT_SOURCE_DIR}/airplay/llhttp/*.c
        ${CMAKE_CURRENT_SOURCE_DIR}/airplay/mdnsd/*.c)

add_library(airplayjni SHARED ${AIRPLAY_SRC} airplay_jni.c)

target_include_directories(airplayjni PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/airplay
        ${CMAKE_CURRENT_SOURCE_DIR}/airplay/playfair
        ${CMAKE_CURRENT_SOURCE_DIR}/airplay/llhttp
        ${CMAKE_CURRENT_SOURCE_DIR}/airplay/mdnsd
        ${PREBUILT}/include)

# NOHOLD: let a new client take over an existing session.
# PLIST_210/230: vendored libplist is 2.6.0.
target_compile_definitions(airplayjni PRIVATE
        NOHOLD
        PLIST_210
        PLIST_230
        OPENSSL_API_COMPAT=0x10101000L)

target_compile_options(airplayjni PRIVATE -O2 -Wno-unused-variable -Wno-unused-function)

target_link_libraries(airplayjni plist crypto log)
