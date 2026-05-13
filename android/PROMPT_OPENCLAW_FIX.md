# PROMPT — OpenClaw Android Fix (Copiar y pegar tal cual)

---

## CONTEXTO DEL PROYECTO

Estoy desarrollando **OpenClaw Android** (`com.openclaw.android`), una app Android nativa en **Kotlin** que ejecuta un gateway Node.js embebido sin Termux, sin Proot, sin dependencias externas en runtime.

**Stack:**
- Android API 31-33 (aarch64)
- Node.js via `libnode.so` (ELF ARM64, ~120MB en `nativeLibraryDir`)
- Loader: `libldlinux.so` (ld-linux-aarch64.so.1, en `nativeLibraryDir`)
- glibc embebido en `payload/glibc/lib/`
- Payload extraído en `context.getDir("payload", MODE_PRIVATE)`
- Shell: `/system/bin/sh` + Toybox nativo
- PTY: Termux terminal-emulator.aar + terminal-view.aar

**Regla crítica de ejecución (NUNCA violar):**
```
Todo argumento ANTES de libnode.so  → va al loader ldlinux
Todo argumento DESPUÉS de libnode.so → va a Node.js
```

**Cadena correcta de ProcessBuilder:**
```kotlin
ProcessBuilder(
    loader.absolutePath,                      // 1. ld-linux loader
    "--library-path", libs,                   // 2. solo dirs de .so
    nodeReal.absolutePath,                    // 3. Node.js binario ELF
    "--disable-warning=ExperimentalWarning",  // 4. flag Node (DESPUÉS del binario)
    openclaw.absolutePath,                    // 5. script .mjs
    "gateway"                                 // 6. argumento del script
)
```

---

## ERROR ACTUAL

Al ejecutar `openclaw onboard` aparece:

```
libldlinux.so: unrecognized option '--disable-warning=ExperimentalWarning'
Try '.../libldlinux.so --help' for more information.
```

**Causa identificada:** El flag `--disable-warning=ExperimentalWarning` está siendo
pasado al loader (`libldlinux.so`) en lugar de a Node.js, porque en el comando
`onboard` ese flag aparece ANTES de `nodeReal.absolutePath`.

---

## TAREA 1 — Corregir el ProcessBuilder del comando `onboard`

Busca en `OpenClawGatewayService.kt` (o donde esté el comando `onboard`) el
`ProcessBuilder` que lo construye y asegúrate de que el orden sea EXACTAMENTE:

```kotlin
ProcessBuilder(
    loader.absolutePath,
    "--library-path", libs,
    nodeReal.absolutePath,                     // ← libnode.so PRIMERO
    "--disable-warning=ExperimentalWarning",   // ← flag Node DESPUÉS
    openclaw.absolutePath,
    "onboard"
)
```

Aplica el mismo orden a TODOS los ProcessBuilder del proyecto que usen el loader
(gateway, onboard, o cualquier otro comando). El patrón es siempre:
`ldlinux → --library-path → libnode.so → [flags Node] → script.mjs → [args]`

---

## TAREA 2 — Crear estructura de rutas tipo home en filesDir

Quiero que el terminal de la app muestre rutas limpias así:

```
~ $ pwd
/data/data/com.openclaw.android/files/home

~ $ ls /data/data/com.openclaw.android/files
home  .openclaw  usr

~ $ ls /data/data/com.openclaw.android/files/usr
bin  glibc  lib  etc  tmp  opt
```

**Importante:** Usamos archivos nativos. Los binarios ELF (`libnode.so`,
`libldlinux.so`, `libbusybox.so`) SOLO pueden ejecutarse desde `nativeLibraryDir`
(política W^X de Android 12+). No mover ni copiar binarios ELF fuera de ahí.

Implementa la función `setupFilesLayout(context: Context)` en `OpenClawInstaller.kt`
que haga lo siguiente al finalizar la extracción del payload:

1. Crear `filesDir/home/` (directorio real — será el HOME del usuario)
2. Crear `filesDir/usr/tmp/` y `filesDir/usr/opt/` (directorios reales)
3. Crear estos symlinks con `Os.symlink()`:
   - `filesDir/usr/bin`   → `nativeLibraryDir`
   - `filesDir/usr/lib`   → `payload/lib`
   - `filesDir/usr/glibc` → `payload/glibc`
   - `filesDir/usr/etc`   → `payload/etc`
   - `filesDir/usr/tmp`   → `cacheDir`
4. Si el symlink ya existe, no recrearlo (check `!link.exists()`)

Luego actualiza el environment del PTY en `OpenClawTerminalManager.kt`:

```kotlin
val homeDir = File(context.filesDir, "home").also { it.mkdirs() }

// Cambiar HOME al nuevo directorio
"HOME=${homeDir.absolutePath}",

// Cambiar PS1 para mostrar ~ cuando estemos en home
"PS1=~ \$ ",
```

---

## RESTRICCIONES — NO hacer en ningún caso

- ❌ NO usar `Runtime.getRuntime().exec()`
- ❌ NO usar `file.setExecutable(true)`
- ❌ NO hardcodear rutas absolutas con el package name
- ❌ NO extraer ELFs a `filesDir`, `cacheDir` ni `getDir()` — solo `nativeLibraryDir`
- ❌ NO incluir `.mjs` o scripts en `LD_LIBRARY_PATH` — solo directorios de `.so`
- ❌ NO dejar `LD_PRELOAD` en el environment — siempre `environment().remove("LD_PRELOAD")`
- ❌ NO ejecutar `bin/node` directamente — es un wrapper bash de Termux, no el ELF real
- ❌ NO duplicar funciones — cada función existe exactamente UNA vez en su archivo

---

## ENTREGA ESPERADA

1. `OpenClawGatewayService.kt` — ProcessBuilder corregido (todos los comandos)
2. `OpenClawInstaller.kt` — función `setupFilesLayout()` añadida y llamada al final de la instalación
3. `OpenClawTerminalManager.kt` — environment actualizado con nuevo `HOME` y `PS1`

Muéstrame solo los bloques de código modificados con contexto suficiente para
ubicarlos. No reescribas archivos completos salvo que sea estrictamente necesario.
