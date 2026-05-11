#!/bin/bash
# Script para verificar alineación de 16 KB en libtermux.so

set -e

# ──────────────────────────────────────────────────────────────────────────────
# Configuración
# ──────────────────────────────────────────────────────────────────────────────

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

# Encontrar readelf
READELF="$NDK_PATH/toolchains/llvm/prebuilt/linux-x86_64/bin/readelf"
if [ "$(uname -s)" = "Darwin" ]; then
    READELF="$NDK_PATH/toolchains/llvm/prebuilt/darwin-x86_64/bin/readelf"
fi
if [ "$(uname -s | cut -c1-5)" = "MINGW" ]; then
    READELF="$NDK_PATH/toolchains/llvm/prebuilt/windows-x86_64/bin/readelf.exe"
fi

echo "========================================"
echo "  Verificar alineación 16 KB"
echo "========================================"
echo ""
echo "✓ NDK: $NDK_PATH"
echo "✓ readelf: $READELF"
echo ""

# ──────────────────────────────────────────────────────────────────────────────
# Buscar libtermux.so
# ──────────────────────────────────────────────────────────────────────────────

LIB_FOUND=""
SEARCH_PATHS=(
    "./output/lib/$ABI/libtermux.so"
    "../app/src/main/jniLibs/$ABI/libtermux.so"
    "./build/lib/$ABI/libtermux.so"
    "./libtermux.so"
)

for path in "${SEARCH_PATHS[@]}"; do
    if [ -f "$path" ]; then
        LIB_FOUND="$path"
        break
    fi
done

if [ -z "$LIB_FOUND" ]; then
    echo "❌ No se encontró libtermux.so"
    echo ""
    echo "Buscado en:"
    for p in "${SEARCH_PATHS[@]}"; do
        echo "  - $p"
    done
    echo ""
    echo "Uso: $0 [ruta/a/libtermux.so]"
    exit 1
fi

echo "✓ Librería: $LIB_FOUND"
echo ""

# ──────────────────────────────────────────────────────────────────────────────
# Verificar alineación
# ──────────────────────────────────────────────────────────────────────────────

echo "────────────────────────────────────────────────────────"
echo "  Segmentos LOAD (readelf -l)"
echo "────────────────────────────────────────────────────────"
"$READELF" -l "$LIB_FOUND"

echo ""
echo "────────────────────────────────────────────────────────"
echo "  Verificación detallada"
echo "────────────────────────────────────────────────────────"

ALIGNED=true
SEGMENT_COUNT=0

while read -r line; do
    if [[ "$line" == *"LOAD"* ]]; then
        ((SEGMENT_COUNT++))
        
        # Extraer campos: Type Offset VirtAddr PhysAddr FileSiz MemSiz Flg Align
        OFFSET_HEX=$(echo "$line" | awk '{print $2}')
        VIRTADDR_HEX=$(echo "$line" | awk '{print $3}')
        ALIGN_HEX=$(echo "$line" | awk '{print $NF}')
        
        OFFSET=$((16#$OFFSET_HEX))
        VIRTADDR=$((16#$VIRTADDR_HEX))
        ALIGN=$((16#$ALIGN_HEX))
        
        echo "Segmento $SEGMENT_COUNT:"
        echo "  Offset:   0x$OFFSET_HEX ($OFFSET bytes)"
        echo "  VirtAddr: 0x$VIRTADDR_HEX ($VIRTADDR bytes)"
        echo "  Align:    0x$ALIGN_HEX ($ALIGN bytes)"
        
        if (( OFFSET % 16384 != 0 )); then
            echo "  ❌ Offset NO alineado a 16 KB!"
            ALIGNED=false
        else
            echo "  ✓ Offset alineado"
        fi
        
        if (( ALIGN < 16384 )); then
            echo "  ❌ Align < 16 KB!"
            ALIGNED=false
        fi
        
        echo ""
    fi
done < <("$READELF" -l "$LIB_FOUND")

echo "────────────────────────────────────────────────────────"
echo "  Resultado final"
echo "────────────────────────────────────────────────────────"
echo ""

if [ "$ALIGNED" = true ] && [ $SEGMENT_COUNT -gt 0 ]; then
    echo "✅ ✅ ✅ TODO CORRECTO! ✅ ✅ ✅"
    echo ""
    echo "libtermux.so está correctamente alineado a 16 KB"
    echo "Listo para usar en Android 15+ y Google Play"
    echo ""
    exit 0
else
    echo "❌ ❌ ❌ FALLO! ❌ ❌ ❌"
    echo ""
    if [ $SEGMENT_COUNT -eq 0 ]; then
        echo "No se encontraron segmentos LOAD"
    else
        echo "La librería NO está alineada a 16 KB"
    fi
    echo ""
    echo "Para solucionar:"
    echo "1. Asegúrate de compilar con: -Wl,-z,max-page-size=16384"
    echo "2. Usa Android NDK r26 o superior"
    echo "3. Ejecuta: ./build.sh"
    echo ""
    exit 1
fi
