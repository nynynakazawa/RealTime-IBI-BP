cmake_minimum_required(VERSION 3.4.1)

# Set the project name
project("realtimehr")

# Add library
add_library(native-lib SHARED src/main/cpp/native-lib.cpp)

# Include libraries needed for native code
find_library(log-lib log)
find_library(android-lib android)

# Link the libraries
target_link_libraries(native-lib ${log-lib} ${android-lib})
