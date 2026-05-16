# Reglas críticas de desarrollo

Reglas **innegociables** para mantener la estabilidad y compatibilidad de OpenClaw Android.

---

## Índice

- [Regla 1 — `ProcessBuilder`](#regla-1--processbuilder)
- [Regla 2 — Captura de logs (stdout/stderr)](#regla-2--captura-de-logs-stdoutstderr)
- [Regla 3 — Compresión de assets](#regla-3--compresión-de-assets)
- [Regla 4 — Tipos en el bridge](#regla-4--tipos-en-el-bridge)
- [Regla 5 — Operaciones de E/S y hilos](#regla-5--operaciones-de-es-y-hilos)
- [Regla 6 — Ejecución de proot](#regla-6--ejecución-de-proot)

---

## Regla 1 — `ProcessBuilder`

- **Estado:** ✅ SIEMPRE.
- **Qué hacer:** usar `ProcessBuilder` en lugar de `Runtime.exec()`.
- **Por qué:** permite control total sobre variables de entorno, directorio de trabajo y redirección de errores.
- **Consecuencia de violarlo:** procesos zombi o falta de acceso a variables críticas como `PATH`.

---

## Regla 2 — Captura de logs (stdout/stderr)

- **Estado:** ✅ SIEMPRE.
- **Qué hacer:** consumir activamente los streams de salida de los procesos lanzados.
- **Por qué:** si el buffer de salida se llena y nadie lo lee, el proceso entra en **deadlock**.
- **Consecuencia:** el gateway se "congela" aleatoriamente.

---

## Regla 3 — Compresión de assets

- **Estado:** ❌ NUNCA.
- **Qué hacer:** incluir las extensiones `.xz` y `.gz` en la lista `noCompress` de Gradle.
- **Por qué:** si Android comprime estos archivos dentro del APK, la descompresión fallará o será extremadamente lenta.
- **Consecuencia:** errores de "Corrupt Archive" durante la instalación.

---

## Regla 4 — Tipos en el bridge

- **Estado:** ✅ SIEMPRE.
- **Qué hacer:** usar solo `String`, `Int` o `Boolean` como parámetros y retornos. Para datos complejos, **JSON String**.
- **Por qué:** `JavascriptInterface` tiene limitaciones severas con objetos complejos de Java/Kotlin.
- **Consecuencia:** errores silenciosos o valores `undefined` en el frontend.

---

## Regla 5 — Operaciones de E/S y hilos

- **Estado:** ✅ SIEMPRE.
- **Qué hacer:** usar `Dispatchers.IO` (corutinas) para cualquier operación de disco o red.
- **Por qué:** realizar estas tareas en el Main Thread provoca errores de `Application Not Responding` (ANR).
- **Consecuencia:** Android mata la aplicación por falta de respuesta.

---

## Regla 6 — Ejecución de proot

- **Estado:** ✅ SIEMPRE.
- **Qué hacer:** ejecutar proot desde `nativeLibraryDir` (único directorio ejecutable en Android 12+).
- **Por qué:** Android bloquea ejecución en `filesDir`, `cacheDir` — el binario `libproot.so` (o proot estático) debe estar en `nativeLibraryDir`.
- **Consecuencia:** `Permission denied` al ejecutar proot.
