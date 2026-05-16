# PROMPT — Migrar OpenClaw Android a proot + Alpine Linux

## PROYECTO
App Android Kotlin `com.openclaw.android`, API 31-33, aarch64.
Actualmente usa `libldlinux.so` + `libnode.so` + glibc embebido para ejecutar Node.js.
**Migrar completamente a proot + Alpine Linux.**

## QUÉ ENTRA / QUÉ SALE

**ELIMINAR de jniLibs/arm64-v8a/:**
- libnode.so, libldlinux.so, libglibc*.so, libbusybox.so

**AGREGAR en jniLibs/arm64-v8a/:**
- `libproot.so` — proot estático ARM64 desde:
  https://skirsten.github.io/proot-portable-android-binaries/aarch64/proot

**Alpine descarga en primer arranque (~10 MB):**
https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/alpine-minirootfs-3.22.0-aarch64.tar.gz

**openclaw se instala dentro de Alpine:**
`apk add nodejs npm ca-certificates && npm install -g openclaw`

## RUTAS CLAVE

| Qué | Ruta host Android | Vista desde proot |
|---|---|---|
| Alpine rootfs | `filesDir/alpine-rootfs/` | `/` |
| filesDir | `context.filesDir` | `/data/` |
| OPENCLAW_HOME | `filesDir/home/.openclaw/` | `/data/home/.openclaw/` |
| TMPDIR proot | `filesDir/proot-tmp/` | `/tmp/` |

## VARIABLES DE ENTORNO — CRÍTICAS (sin estas proot falla en Android)

```
PROOT_TMP_DIR   = filesDir/proot-tmp/   ← Android no tiene /tmp en host
PROOT_NO_SECCOMP= 1                      ← fix ptrace en kernels Android 12+
OPENCLAW_HOME   = /data/home/.openclaw  ← ruta dentro del proot
TMPDIR          = /tmp                   ← dentro del proot = proot-tmp en host
PATH            = /usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
# NUNCA: LD_PRELOAD, LD_LIBRARY_PATH
```

## COMANDO PROOT — orden exacto de argumentos

```
libproot.so
  --rootfs=<filesDir>/alpine-rootfs
  --bind=/proc
  --bind=/dev
  --bind=/sys
  --bind=/dev/urandom
  --bind=<filesDir>:/data
  --bind=<filesDir>/proot-tmp:/tmp
  --change-id=0:0
  --cwd=/root
  <comando>
```

## ARCHIVOS A CREAR/REESCRIBIR

### 1. OpenClawProot.kt (NUEVO)
Clase helper central. Métodos requeridos:
- `buildProotProcess(command: List<String>): ProcessBuilder` — usa el comando exacto de arriba
- `downloadAndExtractAlpine(onProgress, onError)` — descarga + `/system/bin/tar -xzf` (usar tar de Android, NO de Alpine) + escribe `resolv.conf`
- `installOpenClaw(onProgress, onDone, onError)` — ejecuta dentro del proot: `apk update && apk add nodejs npm ca-certificates && npm install -g openclaw`
- `isAlpineInstalled(): Boolean` — check `rootfs/bin/sh`
- `isOpenClawInstalled(): Boolean` — check path de openclaw.mjs
- `buildShellCommand(): Array<String>` — argumentos completos para terminal PTY
- `buildShellEnv(): Array<String>` — environment para terminal PTY

### 2. OpenClawInstaller.kt (REESCRIBIR)
Reemplazar toda la lógica de payload glibc:
```kotlin
if (!proot.isAlpineInstalled())   proot.downloadAndExtractAlpine(...)
if (!proot.isOpenClawInstalled()) proot.installOpenClaw(...)
```
Crear dirs: `home/.openclaw/`, `home/.openclaw/tmp/`, `proot-tmp/`

### 3. OpenClawGatewayService.kt (REESCRIBIR)
```kotlin
// ANTES: ProcessBuilder(loader, "--library-path", libs, nodeReal, ...)
// DESPUÉS:
proot.buildProotProcess(listOf("/bin/sh", "-c", "openclaw gateway")).start()
```

### 4. OpenClawTerminalManager.kt (REESCRIBIR)
```kotlin
TerminalSession(
    proot.proot,               // = nativeDir/libproot.so
    "/data/home/.openclaw",    // working dir
    proot.buildShellCommand(), // argumentos proot completos
    proot.buildShellEnv(),     // environment
    ...
)
```

## RESTRICCIONES

- ❌ NO LD_PRELOAD ni LD_LIBRARY_PATH en ningún ProcessBuilder
- ❌ NO omitir PROOT_TMP_DIR (crash inmediato en Android)
- ❌ NO omitir PROOT_NO_SECCOMP=1 (falla en Android 12+)
- ❌ NO usar tar de Alpine para extraer Alpine (aún no existe)
- ❌ NO hardcodear /data/user/0/ — siempre context.filesDir
- ❌ NO doble .openclaw en ninguna ruta

## RESULTADO ESPERADO

```sh
~ $ node --version    → v22.x.x
~ $ openclaw --version → OpenClaw 2026.x.x
~ $ openclaw onboard  → Telegram configura sin errores
# localhost:18789 → Channels: Telegram ✅  Chat ✅  Usage ✅
```

## ENTREGA
Código completo de los 4 archivos. Esta es una reescritura total, no un patch.
