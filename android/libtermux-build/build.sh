#!/bin/bash
# Script para recompilar libtermux.so con alineación de 16 KB (Android 15+)
# Requisitos: Android NDK r26+ instalado

set -e

# ──────────────────────────────────────────────────────────────────────────────
# Configuración editable
# ──────────────────────────────────────────────────────────────────────────────

# Ruta al Android NDK (cambia esto según tu instalación)
# Ejemplos:
# - Windows: C:/Users/TuUser/AppData/Local/Android/Sdk/ndk/26.1.10909125
# - macOS: ~/Library/Android/sdk/ndk/26.1.10909125
# - Linux: ~/Android/Sdk/ndk/26.1.10909125
if [ -z "$ANDROID_NDK_HOME" ]; then
    echo "❌ ERROR: La variable de entorno ANDROID_NDK_HOME no está definida"
    echo ""
    echo "Por favor, define la ruta a tu NDK:"
    echo "  export ANDROID_NDK_HOME=/ruta/a/tu/ndk"
    echo ""
    exit 1
fi

NDK_PATH="$ANDROID_NDK_HOME"
ABI="arm64-v8a"
MIN_SDK=24
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="$PROJECT_DIR/build"
OUTPUT_DIR="$PROJECT_DIR/output"

# ──────────────────────────────────────────────────────────────────────────────
# Verificaciones iniciales
# ──────────────────────────────────────────────────────────────────────────────

echo "========================================"
echo "  Build libtermux.so - 16 KB"
echo "========================================"
echo ""
echo "✓ NDK: $NDK_PATH"
echo "✓ ABI: $ABI"
echo "✓ Min SDK: $MIN_SDK"
echo "✓ Proyecto: $PROJECT_DIR"
echo ""

if [ ! -d "$NDK_PATH" ]; then
    echo "❌ ERROR: NDK no encontrado en $NDK_PATH"
    exit 1
fi

# ──────────────────────────────────────────────────────────────────────────────
# Paso 1: Limpiar builds anteriores
# ──────────────────────────────────────────────────────────────────────────────

echo "1. Limpiando..."
rm -rf "$BUILD_DIR"
rm -rf "$OUTPUT_DIR"
mkdir -p "$BUILD_DIR"
mkdir -p "$OUTPUT_DIR"
echo "   ✓ OK"
echo ""

# ──────────────────────────────────────────────────────────────────────────────
# Paso 2: Obtener código fuente de Termux
# ──────────────────────────────────────────────────────────────────────────────

echo "2. Obteniendo código fuente de Termux..."

TERMUX_REPO="https://github.com/termux/termux-app.git"
TERMUX_DIR="$PROJECT_DIR/termux-app"

if [ ! -d "$TERMUX_DIR" ]; then
    git clone --depth 1 "$TERMUX_REPO" "$TERMUX_DIR"
else
    echo "   ✓ Repositorio ya existe, actualizando..."
    cd "$TERMUX_DIR"
    git pull
    cd "$PROJECT_DIR"
fi

# Copiar archivos fuente al directorio de build
TERMUX_EXEC_SRC="$TERMUX_DIR/terminal-emulator/src/main/cpp"
if [ -d "$TERMUX_EXEC_SRC" ]; then
    cp -r "$TERMUX_EXEC_SRC"/* "$PROJECT_DIR/" 2>/dev/null || true
    echo "   ✓ Fuentes copiadas"
else
    echo "   ⚠️ No se encontraron fuentes en $TERMUX_EXEC_SRC"
    echo "   Por favor, copia manualmente los archivos .c y .h a $PROJECT_DIR"
fi
echo ""

# ──────────────────────────────────────────────────────────────────────────────
# Paso 3: Compilar usando CMake (recomendado)
# ──────────────────────────────────────────────────────────────────────────────

echo "3. Compilando con CMake..."

TOOLCHAIN_FILE="$NDK_PATH/build/cmake/android.toolchain.cmake"

cd "$BUILD_DIR"
cmake "$PROJECT_DIR" \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM=android-$MIN_SDK \
    -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release

cmake --build . --config Release

echo "   ✓ Compilación completada"
echo ""

# ──────────────────────────────────────────────────────────────────────────────
# Paso 4: Copiar librería compilada al directorio de salida
# ──────────────────────────────────────────────────────────────────────────────

echo "4. Preparando salida..."
OUTPUT_LIB_DIR="$OUTPUT_DIR/lib/$ABI"
mkdir -p "$OUTPUT_LIB_DIR"

COMPILED_LIB="$BUILD_DIR/lib/$ABI/libtermux.so"
if [ -f "$COMPILED_LIB" ]; then
    cp "$COMPILED_LIB" "$OUTPUT_LIB_DIR/"
    echo "   ✓ Librería copiada a: $OUTPUT_LIB_DIR"
else
    echo "   ⚠️ No se encontró la librería en $COMPILED_LIB"
    echo "   Buscando en otros directorios..."
    
    find "$BUILD_DIR" -name "libtermux.so" -type f | while read -r lib; do
        echo "   → Encontrado: $lib"
        cp "$lib" "$OUTPUT_LIB_DIR/"
    done
fi
echo ""

# ──────────────────────────────────────────────────────────────────────────────
# Paso 5: Verificar alineación de 16 KB
# ──────────────────────────────────────────────────────────────────────────────

echo "5. Verificando alineación..."

READELF="$NDK_PATH/toolchains/llvm/prebuilt/linux-x86_64/bin/readelf"
if [ "$(uname -s)" = "Darwin" ]; then
    READELF="$NDK_PATH/toolchains/llvm/prebuilt/darwin-x86_64/bin/readelf"
fi
if [ "$(uname -s | cut -c1-5)" = "MINGW" ]; then
    READELF="$NDK_PATH/toolchains/llvm/prebuilt/windows-x86_64/bin/readelf.exe"
fi

FINAL_LIB="$OUTPUT_LIB_DIR/libtermux.so"

if [ -f "$FINAL_LIB" ] && [ -f "$READELF" ]; then
    echo ""
    echo "────────────────────────────────────────────────────────"
    echo "  Resultado de readelf -l libtermux.so"
    echo "────────────────────────────────────────────────────────"
    "$READELF" -l "$FINAL_LIB" | grep -A5 LOAD
    echo ""
    
    # Verificar alineación
    ALIGNED=true
    while read -r line; do
        if [[ "$line" == *"LOAD"* ]]; then
            # Extraer el offset (segundo campo en hexadecimal)
            OFFSET_HEX=$(echo "$line" | awk '{print $2}')
            OFFSET=$((16#$OFFSET_HEX))
            
            if (( OFFSET % 16384 != 0 )); then
                ALIGNED=false
                echo "❌ Segmento NO alineado: Offset 0x$OFFSET_HEX ($OFFSET bytes)"
            fi
        fi
    done < <("$READELF" -l "$FINAL_LIB")
    
    if [ "$ALIGNED" = true ]; then
        echo ""
        echo "✅ ✅ ✅ LIBTERMUX.SO ESTÁ CORRECTAMENTE ALINEADO A 16 KB ✅ ✅ ✅"
        echo ""
    else
        echo ""
        echo "❌ ❌ ❌ ERROR: LA LIBRERÍA NO ESTÁ ALINEADA CORRECTAMENTE ❌ ❌ ❌"
        echo ""
        exit 1
    fi
else
    echo "   ⚠️ No se puede verificar (readelf o libtermux.so no encontrados)"
fi
echo ""

# ──────────────────────────────────────────────────────────────────────────────
# Paso 6: Instrucciones finales
# ──────────────────────────────────────────────────────────────────────────────

echo "========================================"
echo "  Build completado!"
echo "========================================"
echo ""
echo "Librería final:"
echo "  $FINAL_LIB"
echo ""
echo "Para usar en tu app Android:"
echo "1. Copiar:"
echo "   $FINAL_LIB"
echo "   →"
echo "   android/app/src/main/jniLibs/$ABI/libtermux.so"
echo ""
echo "2. O ejecuta:"
echo "   mkdir -p ../app/src/main/jniLibs/$ABI"
echo "   cp $FINAL_LIB ../app/src/main/jniLibs/$ABI/"
echo ""
echo "¡Listo para Google Play!"
