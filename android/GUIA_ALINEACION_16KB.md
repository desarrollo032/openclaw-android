# Guía: Alineación de 16 KB para Android 15+

## 📋 Problema

Google Play está rechazando tu APK con el error:
> "APK is not compatible with 16 KB devices. Some libraries have LOAD segments not aligned at 16 KB boundaries"

## 🎯 Solución Completa

---

## Paso 1: Verificar alineación actual

Ejecuta el script que creamos para verificar qué librerías no cumplen:

```powershell
cd android
.\verify_alignment.ps1
```

Este script:
1. 🔍 Detecta automáticamente tu Android NDK
2. 📦 Extrae temporalmente los AARs (terminal-emulator.aar, terminal-view.aar)
3. 🛠️ Usa `readelf.exe` para verificar los segmentos LOAD
4. ✅ Muestra un resumen de librerías OK vs fallidas

---

## Paso 2: Recompilar librerías con flags correctos

### 2.1 Configurar Android NDK

Asegúrate de tener instalado un NDK moderno (r25 o superior):

```gradle
// android/build.gradle.kts (ya configurado)
android {
    defaultConfig {
        // Alineación de 16 KB para Android 15+
        externalNativeBuild {
            cmake {
                // Flags de linker para 16 KB
                cppFlags += listOf("-Wl,-z,max-page-size=16384")
            }
        }
        
        // Configuración NDK para arm64-v8a
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }
}
```

### 2.2 Si usas CMakeLists.txt directamente

Si compilas librerías nativas con CMake:

```cmake
# CMakeLists.txt
target_link_options(tu_libreria PRIVATE
    "-Wl,-z,max-page-size=16384"
)
```

### 2.3 Si usas ndk-build

Si usas `ndk-build` (Android.mk):

```makefile
# Android.mk
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384
```

---

## Paso 3: Recompilar las librerías del Terminal Emulator

Las librerías problemáticas probablemente están en los AARs:

### Opción A: Usar versiones actualizadas del terminal-emulator

Busca versiones actualizadas del terminal-emulator que ya tengan la alineación correcta:

```gradle
// En lugar de usar AARs locales, usa una dependencia de Maven
dependencies {
    // Si hay una versión oficial actualizada:
    implementation("com.termux:terminal-emulator:1.0.XX")
    implementation("com.termux:terminal-view:1.0.XX")
}
```

### Opción B: Recompilar el terminal-emulator desde fuente

Si necesitas recompilarlo tú mismo:

```bash
# 1. Clonar el repositorio
git clone https://github.com/termux/termux-app.git
cd termux-app

# 2. Configurar flags en build.gradle
# Añade los flags de linker como en el paso 2.1

# 3. Compilar
./gradlew :terminal-emulator:assembleRelease
./gradlew :terminal-view:assembleRelease

# 4. Copiar los AARs actualizados a tu proyecto
cp terminal-emulator/build/outputs/aar/*.aar tu-proyecto/android/app/libs/
cp terminal-view/build/outputs/aar/*.aar tu-proyecto/android/app/libs/
```

---

## Paso 4: Verificar el payload-v2.tar.xz

Tu payload también contiene librerías. Extrae y verifica:

```bash
# 1. Extraer el payload
cd android/app/src/main/assets
mkdir temp_payload
cd temp_payload
tar -xf ../payload-v2.tar.xz

# 2. Buscar y verificar librerías .so
find . -name "*.so" -print0 | while IFS= read -r -d $'\0' lib; do
    echo "Verificando: $lib"
    readelf -l "$lib" | grep LOAD
done

# 3. Si hay librerías mal alineadas, recompila el payload
# con los mismos flags de linker
```

---

## Paso 5: Limpiar y recompilar tu app

```powershell
cd android

# Limpiar build anterior
.\gradlew clean

# Compilar debug (para probar)
.\gradlew assembleDebug

# Compilar release (para Google Play)
.\gradlew assembleRelease
```

---

## Paso 6: Verificar el APK final

Después de compilar, extrae el APK y verifica las librerías:

```powershell
# 1. Localiza el APK
# android/app/build/outputs/apk/release/app-release.apk

# 2. Extraer el APK
# (usa 7-Zip, WinRAR, o unzip)

# 3. Verifica las librerías en lib/arm64-v8a/
# Usa el script verify_alignment.ps1 o readelf directamente
```

---

## 🔧 Comandos Útiles (readelf)

### Verificar una librería individual
```bash
readelf -l libtu_libreria.so
```

Busca líneas como:
```
  LOAD           0x0000000000000000 0x0000000000000000 0x0000000000000000
```

✅ **OK**: El offset empieza en `0x00000000` o múltiplo de `0x4000` (16384 bytes)
❌ **Mal**: El offset no es múltiplo de 16384

---

## 📋 Checklist Final

- [ ] Ejecutaste `verify_alignment.ps1` y identificaste las librerías problemáticas
- [ ] Actualizaste `build.gradle.kts` con flags de linker (`-Wl,-z,max-page-size=16384`)
- [ ] Recompilaste o actualizaste el `terminal-emulator.aar` y `terminal-view.aar`
- [ ] Verificaste y recompilaste las librerías dentro del `payload-v2.tar.xz`
- [ ] Limpiaste y recompilaste tu app (`gradlew clean assembleRelease`)
- [ ] Verificaste el APK final con `readelf`

---

## 🚀 Si todo sale bien

Tu APK pasará la validación de Google Play y será compatible con dispositivos Android 15+ que usan páginas de 16 KB.

---

## 📚 Recursos

- [Android 15 Behavior Changes: 16 KB Page Alignment](https://developer.android.com/about/versions/15/behavior-changes-15#16kb-page-alignment)
- [NDK r25 Release Notes](https://developer.android.com/ndk/downloads/revision_history#r25)
- [readelf Manual](https://man7.org/linux/man-pages/man1/readelf.1.html)
