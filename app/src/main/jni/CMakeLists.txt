cmake_minimum_required(VERSION 3.22.1)

project(moonlight-helios)

# Import NDK modules
include(AndroidNdkModules)
android_ndk_import_module_cpufeatures()

# Define prebuilt static libraries using CMAKE_CURRENT_LIST_DIR and relative paths
# We use absolute paths for IMPORTED_LOCATION as required by CMake, but ensured they are quoted.
add_library(libopus STATIC IMPORTED GLOBAL)
set_target_properties(libopus PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_LIST_DIR}/moonlight-core/libopus/${ANDROID_ABI}/libopus.a"
)

add_library(libssl STATIC IMPORTED GLOBAL)
set_target_properties(libssl PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_LIST_DIR}/moonlight-core/openssl/${ANDROID_ABI}/libssl.a"
)

add_library(libcrypto STATIC IMPORTED GLOBAL)
set_target_properties(libcrypto PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_LIST_DIR}/moonlight-core/openssl/${ANDROID_ABI}/libcrypto.a"
)

# Main moonlight-core library
# Use relative paths to avoid issues with drive letters and spaces in Ninja on Windows
add_library(moonlight-core SHARED
    moonlight-core/moonlight-common-c/src/AudioStream.c
    moonlight-core/moonlight-common-c/src/ByteBuffer.c
    moonlight-core/moonlight-common-c/src/Connection.c
    moonlight-core/moonlight-common-c/src/ConnectionTester.c
    moonlight-core/moonlight-common-c/src/ControlStream.c
    moonlight-core/moonlight-common-c/src/FakeCallbacks.c
    moonlight-core/moonlight-common-c/src/InputStream.c
    moonlight-core/moonlight-common-c/src/LinkedBlockingQueue.c
    moonlight-core/moonlight-common-c/src/Misc.c
    moonlight-core/moonlight-common-c/src/Platform.c
    moonlight-core/moonlight-common-c/src/PlatformCrypto.c
    moonlight-core/moonlight-common-c/src/PlatformSockets.c
    moonlight-core/moonlight-common-c/src/RtpAudioQueue.c
    moonlight-core/moonlight-common-c/src/RtpVideoQueue.c
    moonlight-core/moonlight-common-c/src/RtspConnection.c
    moonlight-core/moonlight-common-c/src/RtspParser.c
    moonlight-core/moonlight-common-c/src/SdpGenerator.c
    moonlight-core/moonlight-common-c/src/SimpleStun.c
    moonlight-core/moonlight-common-c/src/VideoDepacketizer.c
    moonlight-core/moonlight-common-c/src/VideoStream.c
    moonlight-core/moonlight-common-c/reedsolomon/rs.c
    moonlight-core/moonlight-common-c/enet/callbacks.c
    moonlight-core/moonlight-common-c/enet/compress.c
    moonlight-core/moonlight-common-c/enet/host.c
    moonlight-core/moonlight-common-c/enet/list.c
    moonlight-core/moonlight-common-c/enet/packet.c
    moonlight-core/moonlight-common-c/enet/peer.c
    moonlight-core/moonlight-common-c/enet/protocol.c
    moonlight-core/moonlight-common-c/enet/unix.c
    moonlight-core/moonlight-common-c/enet/win32.c
    moonlight-core/simplejni.c
    moonlight-core/callbacks.c
    moonlight-core/minisdl.c
)

target_include_directories(moonlight-core PRIVATE
    moonlight-core/moonlight-common-c/enet/include
    moonlight-core/moonlight-common-c/reedsolomon
    moonlight-core/moonlight-common-c/src
    moonlight-core/libopus/include
    moonlight-core/openssl/include
)

target_compile_definitions(moonlight-core PRIVATE
    HAS_SOCKLEN_T=1
    LC_ANDROID
    HAVE_CLOCK_GETTIME=1
)

if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_definitions(moonlight-core PRIVATE LC_DEBUG)
endif()

target_link_libraries(moonlight-core
    log
    libopus
    libssl
    libcrypto
    cpufeatures
)

# Force exclusion of static library symbols from export table
set_target_properties(moonlight-core PROPERTIES LINK_FLAGS "-Wl,--exclude-libs,ALL")

# Root-only evdev_reader
if(PRODUCT_FLAVOR STREQUAL "root")
    add_executable(evdev_reader evdev_reader/evdev_reader.c)
    target_link_libraries(evdev_reader log)
    # Package as .so for APK inclusion
    set_target_properties(evdev_reader PROPERTIES
        OUTPUT_NAME evdev_reader
        PREFIX "lib"
        SUFFIX ".so"
    )
endif()
