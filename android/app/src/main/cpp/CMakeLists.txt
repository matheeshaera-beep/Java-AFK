cmake_minimum_required(VERSION 3.18.1)
project("java-engine" LANGUAGES CXX)

add_library(native-lib SHARED native-lib.cpp)

# Import prebuilt libnode
add_library(node SHARED IMPORTED)
set_target_properties(node PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../../../src/main/jniLibs/${ANDROID_ABI}/libnode.so
)

# Include Node.js headers
include_directories(${CMAKE_SOURCE_DIR}/../../../src/main/include)

find_library(log-lib log)

target_link_libraries(native-lib node ${log-lib})

# Copy libnode.so to the output directory so it gets packaged in the APK
add_custom_command(TARGET native-lib POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        ${CMAKE_SOURCE_DIR}/../../../src/main/jniLibs/${ANDROID_ABI}/libnode.so
        $<TARGET_FILE_DIR:native-lib>/libnode.so
)