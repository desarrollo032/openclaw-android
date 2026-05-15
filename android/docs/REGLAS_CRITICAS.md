# Reglas críticas de desarrollo

Reglas **innegociables** para mantener la estabilidad y compatibilidad de OpenClaw Android en versiones modernas del sistema.

---

## Índice

- [Regla 1 — `ProcessBuilder`](#regla-1--processbuilder)
- [Regla 2 — Ejecución en Android 12+ (W^X)](#regla-2--ejecución-en-android-12-wx)
- [Regla 3 — Ubicación de binarios ELF](#regla-3--ubicación-de-binarios-elf)
- [Regla 4 — Variable `LD_PRELOAD`](#regla-4--variable-ld_preload)
- [Regla 5 — Captura de logs (stdout/stderr)](#regla-5--captura-de-logs-stdoutstderr)
- [Regla 6 — Compresión de assets](#regla-6--compresión-de-assets)
- [Regla 7 — Tipos en el bridge](#regla-7--tipos-en-el-bridge)
- [Regla 8 — Operaciones de E/S y hilos](#regla-8--operaciones-de-es-y-hilos)
- [Resumen — cadena ELF obligatoria](#resumen--cadena-elf-obligatoria)

---

## Regla 1 — `ProcessBuilder`

- **Estado:** ✅ SIEMPRE.
- **Qué hacer:** usar `ProcessBuilder` en lugar de `Runtime.exec()`.
- **Por qué:** permite control total sobre variables de entorno, directorio de trabajo y redirección de errores.
- **Consecuencia de violarlo:** procesos zombi o falta de acceso a variables críticas como `LD_LIBRARY_PATH`.

> **Nota (importante para `invalid ELF header`):** si usas una `glibc` personalizada con `libldlinux.so`, **evita setear `LD_LIBRARY_PATH` a nivel global** si ya pasas `--library-path` al loader. Forzarlo puede provocar resoluciones inesperadas en algunos dispositivos.

---

## Regla 2 — Ejecución en Android 12+ (W^X)

- **Estado:** ❌ NUNCA.
- **Qué hacer:** **nunca** intentar ejecutar archivos marcados con `setExecutable(true)` en `filesDir`, `cacheDir` o `getDir()`.
- **Por qué:** Android bloquea por seguridad la ejecución en directorios de datos.
- **Consecuencia:** `Permission Denied` persistente sin importar los permisos aplicados.

---

## Regla 3 — Ubicación de binarios ELF

- **Estado:** ✅ SIEMPRE.
- **Qué hacer:** usar exclusivamente `applicationInfo.nativeLibraryDir` para almacenar binarios ejecutables.
- **Por qué:** es el único directorio con permisos de ejecución permitidos por la política de seguridad del sistema.
- **Consecuencia:** error de carga de librerías o bloqueo del proceso por el kernel.

---

## Regla 4 — Variable `LD_PRELOAD`

- **Estado:** ❌ NUNCA.
- **Qué hacer:** eliminar **siempre** `LD_PRELOAD` del entorno antes de iniciar el Gateway o el Terminal.
- **Por qué:** Android inyecta librerías para debugging o profiling que entran en conflicto con la `glibc` del payload.
- **Consecuencia:** `Segmentation Fault` inmediato al iniciar Node.js.

---

## Regla 5 — Captura de logs (stdout/stderr)

- **Estado:** ✅ SIEMPRE.
- **Qué hacer:** consumir activamente los streams de salida de los procesos lanzados.
- **Por qué:** si el buffer de salida se llena y nadie lo lee, el proceso entra en **deadlock**.
- **Consecuencia:** el gateway se "congela" aleatoriamente.

---

## Regla 6 — Compresión de assets

- **Estado:** ❌ NUNCA.
- **Qué hacer:** incluir las extensiones `.xz` y `.gz` en la lista `noCompress` de Gradle.
- **Por qué:** si Android comprime estos archivos dentro del APK, la descompresión fallará o será extremadamente lenta.
- **Consecuencia:** errores de "Corrupt Archive" durante la instalación.

---

## Regla 7 — Tipos en el bridge

- **Estado:** ✅ SIEMPRE.
- **Qué hacer:** usar solo `String`, `Int` o `Boolean` como parámetros y retornos. Para datos complejos, **JSON String**.
- **Por qué:** `JavascriptInterface` tiene limitaciones severas con objetos complejos de Java/Kotlin.
- **Consecuencia:** errores silenciosos o valores `undefined` en el frontend.

---

## Regla 8 — Operaciones de E/S y hilos

- **Estado:** ✅ SIEMPRE.
- **Qué hacer:** usar `Dispatchers.IO` (corutinas) para cualquier operación de disco o red.
- **Por qué:** realizar estas tareas en el Main Thread provoca errores de `Application Not Responding` (ANR).
- **Consecuencia:** Android mata la aplicación por falta de respuesta.

---

## Resumen — cadena ELF obligatoria

Para ejecutar el gateway hay que seguir estrictamente este orden:

```kotlin
// CORRECTO
ProcessBuilder(
    "libldlinux.so",          // 1. Cargador dinámico (ejecutable)
    "--library-path", libs,   // 2. Ruta a librerías glibc
    "libnode.so",             // 3. Binario de Node.js (cargado por ldlinux)
    "openclaw.mjs"            // 4. Script de OpenClaw
)
```

**Nunca** intentes ejecutar `node` o `openclaw.mjs` directamente sin el cargador dinámico si estás usando `glibc` personalizada.
