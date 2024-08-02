cmake_minimum_required(VERSION 3.22.1)

message(NOTICE "dependencies built in ${DEPS_DIR}")
message(NOTICE "building for ${ANDROID_ABI}")
if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    set(deps_abi_path "${DEPS_DIR}/arm64-android/vcpkg_installed/arm64-android")
elseif (${ANDROID_ABI} STREQUAL "armeabi-v7a")
    set(deps_abi_path "${DEPS_DIR}/arm-android/vcpkg_installed/arm-neon-android")
elseif (${ANDROID_ABI} STREQUAL "x86")
    set(deps_abi_path "${DEPS_DIR}/x86-android/vcpkg_installed/x86-android")
elseif (${ANDROID_ABI} STREQUAL "x86_64")
    set(deps_abi_path "${DEPS_DIR}/x64-android/vcpkg_installed/x64-android")
endif ()

message(NOTICE "deps_abi_path = ${deps_abi_path}")

set(CMAKE_FIND_ROOT_PATH "${deps_abi_path}")
set(ENV{PKG_CONFIG_PATH} "${deps_abi_path}/lib/pkgconfig")

# Declares the project name. The project name can be accessed via ${ PROJECT_NAME},
# Since this is the top level CMakeLists.txt, the project name is also accessible
# with ${CMAKE_PROJECT_NAME} (both CMake variables are in-sync within the top level
# build script scope).
project("tunnel")

set(ZITI_BUILD_TESTS OFF)
set(ZITI_BUILD_PROGRAMS OFF)
set(ZITI_TUNNEL_BUILD_TESTS OFF)
set(EXCLUDE_PROGRAMS ON)

add_library(${CMAKE_PROJECT_NAME} SHARED
        tunnel.cpp
        keychain.cpp
        netif.cpp
)

target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
        ZITI_LOG_MODULE="${PROJECT_NAME}"
)

target_link_libraries(${CMAKE_PROJECT_NAME}
        android
        log
)

set(ZITI_SDK_VERSION 2.0.0-alpha9)
if (tunnel_DIR)
    add_subdirectory(${tunnel_DIR} ${CMAKE_CURRENT_BINARY_DIR}/tunnel-sdk)
else (tunnel_DIR)
    include(FetchContent)
    FetchContent_Declare(tun-sdk
            GIT_REPOSITORY http://github.com/openziti/ziti-tunnel-sdk-c.git
            GIT_TAG v2.0.0-alpha11
    )
    FetchContent_MakeAvailable(tun-sdk)
endif ()
target_link_libraries(${CMAKE_PROJECT_NAME}
        ziti-tunnel-sdk-c
        ziti-tunnel-cbs-c
)