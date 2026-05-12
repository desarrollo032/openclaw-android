# Reglas Críticas de Desarrollo

Este documento enumera las reglas innegociables para mantener la estabilidad y compatibilidad de OpenClaw Android en versiones modernas del sistema.

---

### REGLA 1 — Uso de ProcessBuilder
**Estado:** ✅ SIEMPRE
**Qué hacer:** Usar `ProcessBuilder` en lugar de `Runtime.exec()`.
**Por qué:** Permite un control total sobre las variables de entorno, el directorio de trabajo y la redirección de errores de forma segura.
**Consecuencia:** Fuga de procesos (zombies) o falta de acceso a variables críticas como `LD_LIBRARY_PATH`.

> Nota (importante para `invalid ELF header`): si usas una `glibc` personalizada con `libldlinux.so`, **evita setear `LD_LIBRARY_PATH` a nivel global** si el flujo correcto ya pasa `--library-path` al loader. Forzar `LD_LIBRARY_PATH` puede provocar resoluciones inesperadas en algunos dispositivos/cadenas.


### REGLA 2 — Ejecución en Android 12+ (W^X)
**Estado:** ❌ NUNCA
**Qué hacer:** NUNCA intentar ejecutar archivos marcados con `setExecutable(true)` en `filesDir`, `cacheDir` o `getDir()`.
**Por qué:** Android bloquea por seguridad la ejecución en directorios de datos.
**Consecuencia:** `Permission Denied` persistente sin importar los permisos aplicados.

### REGLA 3 — Ubicación de Binarios ELF
**Estado:** ✅ SIEMPRE
**Qué hacer:** Usar exclusivamente `applicationInfo.nativeLibraryDir` para almacenar binarios ejecutables.
**Por qué:** Es el único directorio con permisos de ejecución permitidos por la política de seguridad del sistema.
**Consecuencia:** Error de carga de librerías o bloqueo del proceso por el kernel.

### REGLA 4 — Variable LD_PRELOAD
**Estado:** ❌ NUNCA
**Qué hacer:** Eliminar siempre `LD_PRELOAD` del entorno antes de iniciar el Gateway o la Terminal.
**Por qué:** Android inyecta librerías para debugging o profiling que entran en conflicto con la `glibc` del payload.
**Consecuencia:** `Segmentation Fault` inmediato al iniciar Node.js.

### REGLA 5 — Captura de Logs (Stdout/Stderr)
**Estado:** ✅ SIEMPRE
**Qué hacer:** Consumir activamente los streams de salida de los procesos lanzados.
**Por qué:** Si el buffer de salida se llena y nadie lo lee, el proceso se bloquea (deadlock).
**Consecuencia:** El Gateway se queda "congelado" aleatoriamente.

### REGLA 6 — Compresión de Assets
**Estado:** ❌ NUNCA
**Qué hacer:** Incluir extensiones `.xz` y `.gz` en la lista `noCompress` de Gradle.
**Por qué:** Si Android comprime estos archivos dentro del APK, la descompresión fallará o será extremadamente lenta al no poder leer el stream correctamente.
**Consecuencia:** Errores de "Corrupt Archive" durante la instalación.

### REGLA 7 — Tipos en el Bridge
**Estado:** ✅ SIEMPRE
**Qué hacer:** Usar solo `String`, `Int` o `Boolean` como parámetros y retornos. Para datos complejos, usar `JSON String`.
**Por qué:** El `JavascriptInterface` tiene limitaciones severas con objetos complejos de Java/Kotlin.
**Consecuencia:** Errores silenciosos o valores `undefined` en el frontend.

### REGLA 8 — Operaciones de E/S y Hilos
**Estado:** ✅ SIEMPRE
**Qué hacer:** Usar `Dispatchers.IO` para cualquier operación de disco o red.
**Por qué:** Realizar estas tareas en el Main Thread provoca errores de `Application Not Responding` (ANR).
**Consecuencia:** Android matará la aplicación por falta de respuesta.

---

## 🏗️ Resumen de la Cadena ELF Obligatoria

Para ejecutar el gateway, se debe seguir estrictamente este orden de ejecución:

```kotlin
// CORRECTO
ProcessBuilder(
    "libldlinux.so",          // 1. Cargador dinámico (ejecutable)
    "--library-path", libs,   // 2. Ruta a librerías glibc
    "libnode.so",             // 3. Binario de Node.js (cargado por ldlinux)
    "openclaw.mjs"            // 4. El script de OpenClaw
)
```

**NUNCA** intentes ejecutar `node` o `openclaw.mjs` directamente sin el cargador dinámico si estás usando una `glibc` personalizada.
