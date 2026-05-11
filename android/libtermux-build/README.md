# Guía Completa: Recompilar libtermux.so con Alineación de 16 KB

## 📋 Problema

Tu APK falla en Google Play con:
> "APK is not compatible with 16 KB devices. Some libraries have LOAD segments not aligned at 16 KB boundaries"

## 🚀 Solución: 7 Pasos para Recompilar libtermux.so

---

## 📦 Estructura de Archivos (ya creados

```
android/libtermux-build/
├── CMakeLists.txt       # Build con CMake (recomendado)
├── Android.mk         # Build con ndk-build (alternativa)
├── Application.mk      # Configuración para ndk-build
├── build.sh           # Script de compilación todo-en-uno
└── verify.sh          # Script de verificación de alineación
```

---

## Paso 1: Preparar el Entorno

### 1.1 Instalar Android NDK r26+

Abre Android Studio → Tools → SDK Manager → SDK Tools → Instala:
- ✅ NDK (Side by side) → elige la versión **26.1.10909125 o superior

### 1.2 Definir Variable de Entorno

**Windows (PowerShell):
```powershell
$env:ANDROID_NDK_HOME = "C:\Users\TuUsuario\AppData\Local\Android\Sdk\ndk\26.1.10909125"
```

**macOS/Linux:
```bash
export ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk/26.1.10909125"
# O en ~/.zshrc o ~/.bashrc para que sea permanente
```

---

## Paso 2: Obtener Código Fuente (ejecutar build.sh)

El script `build.sh` se encarga de todo:
1. Clonar automáticamente el repositorio de Termux
2. Copiar los fuentes
3. Compilar con flags de 16 KB
4. Verificar la alineación

**macOS/Linux:
```bash
cd android/libtermux-build
chmod +x build.sh verify.sh
./build.sh
```

**Windows (Git Bash o WSL):
```bash
cd android/libtermux-build
chmod +x build.sh verify.sh
./build.sh
```

---

## Paso 3: Compilar Manualmente (si build.sh falla)

### Opción A: Usar CMake (recomendado)
```bash
cd android/libtermux-build
mkdir -p build
cd build

cmake .. \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-24 \
    -DCMAKE_BUILD_TYPE=Release

cmake --build . --config Release
```

### Opción B: Usar ndk-build
```bash
cd android/libtermux-build
"$ANDROID_NDK_HOME/ndk-build" NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=./Android.mk APP_PLATFORM=android-24
```

---

## Paso 4: Verificar la Alineación

Ejecuta el script de verificación:
```bash
./verify.sh
```

O manualmente:
```bash
readelf -l output/lib/arm64-v8a/libtermux.so | grep LOAD
```

**✅ Resultado esperado**:
Cada línea LOAD debe tener:
- El offset empieza en `0x00000000` o múltiplo de `0x4000` (16384 bytes)
- El campo `Align` muestra `0x4000` (16384)

---

## Paso 5: Integrar en tu App

Copiar la librería compilada:
```bash
mkdir -p ../app/src/main/jniLibs/arm64-v8a
cp output/lib/arm64-v8a/libtermux.so ../app/src/main/jniLibs/arm64-v8a/
```

---

## Paso 6: Actualizar build.gradle.kts

Asegúrate de que tu `app/build.gradle.kts` ya incluye:
```gradle
android {
    defaultConfig {
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-Wl,-z,max-page-size=16384")
            }
        }
    }
}
```

---

## Paso 7: Limpiar y Recompilar la App

```bash
cd android
./gradlew clean
./gradlew assembleRelease
```

---

## 🛠️ Diagnóstico de Errores Comunes

### Error: "No se encuentra ANDROID_NDK_HOME"
Solución: Define la variable de entorno correctamente.

### Error: Segmentos no alineados
Solución: Asegúrate de que el flag `-Wl,-z,max-page-size=16384` está presente.

### Error: Dependencias faltantes (termux.c no encontrado
Solución: Clona el repositorio de Termux manualmente:
```bash
git clone https://github.com/termux/termux-app.git
```

---

## 📋 Checklist Final

- [ ] Android NDK r26+ instalado
- [ ] Variable `ANDROID_NDK_HOME` definida
- [ ] Ejecutaste `./build.sh` con éxito
- [ ] Verificaste con `./verify.sh` → ✅ TODO CORRECTO
- [ ] Copiaste libtermux.so a `app/src/main/jniLibs/arm64-v8a/`
- [ ] Actualizaste build.gradle.kts
- [ ] Compilaste la app con `gradlew assembleRelease`

---

## 🎉 Listo!

Tu libtermux.so ahora es compatible con:
- ✅ Android 15+
- ✅ Dispositivos de 16 KB
- ✅ Google Play

¡Sube tu APK sin problemas!
