# 1. Required Boilerplate (Fixes "No cmake_minimum_required" and "No project" errors)
cmake_minimum_required(VERSION 3.22.1)
project("GBAEmulator")

# 2. Define your library (Fixes "target not built by this project" error)
# This tells CMake to compile native-lib.cpp into a shared library named "native-lib"
add_library( # Sets the name of the library.
             native-lib

             # Sets the library as a shared library.
             SHARED

             # Provides a relative path to your source file(s).
             native-lib.cpp )

# 3. Locate system libraries
find_library( # Sets the name of the path variable.
              log-lib

              # Specifies the name of the NDK library that
              # you want CMake to locate.
              log )

find_library( graphics-lib 
              jnigraphics ) # Required for Bitmap operations

# 4. Link libraries
# This must come AFTER add_library
target_link_libraries( # Specifies the target library.
                       native-lib

                       # Links the target library to the log library
                       # included in the NDK.
                       ${log-lib}
                       ${graphics-lib} )

add_compile_options(-O3 -ffast-math)