cmake_minimum_required(VERSION 3.22.1)
project(termux LANGUAGES C)

# Configurar versión mínima de Android
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# Flags de compilación
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -Wall -Wextra -O2 -fPIC")
set(CMAKE_C_FLAGS_RELEASE "${CMAKE_C_FLAGS_RELEASE} -O3 -DNDEBUG")

# Flag CRÍTICO para alineación de 16 KB (Android 15+)
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

# Fuentes de libtermux
set(TERMUX_SOURCES
    termux.c
    termuxexec.c
    termuxpty.c
    termuxfile.c
    termuxutil.c
    termux-signal.c
)

# Incluir directorios
include_directories(
    ${CMAKE_SOURCE_DIR}
)

# Crear libtermux.so
add_library(termux SHARED ${TERMUX_SOURCES})

# Enlazar librerías del sistema Android
target_link_libraries(termux
    android
    log
)

# Establecer el nombre de salida
set_target_properties(termux PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/lib/${ANDROID_ABI}"
    OUTPUT_NAME "termux"
    PREFIX "lib"
    SUFFIX ".so"
)
