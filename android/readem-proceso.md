# OpenClaw Android — Estado del Proceso y Plan de Continuación

**Última actualización:** Mayo 2026  
**Problema central:** La app funciona pero no puede instalar OpenClaw (error de glibc)

---

## Estado actual

### ✅ Lo que funciona
- App arranca y muestra el WebView con el dashboard
- Terminal embebido (Termux) funciona
- `OpenClawService` (foreground service) activo — protege contra Phantom Process Killer
- `InstallerManager` orquesta el flujo de instalación
- `SetupManager` + `ProotManager` implementados (flujo proot)
- Overlay de progreso de instalación funciona en la UI
- **Payload bundleado en APK: `assets/openclaw-payload.tar.gz` (167MB)**

### ❌ El problema que se estaba resolviendo

**Error de glibc:** `glibc/lib/ld-linux-aarch64.so.1` no llegaba a su ubicación correcta.

**Causa raíz:**
```
installOffline()
  → extrae openclaw-payload.tar.gz → homeDir/
  → el payload queda en homeDir/payload/ (o homeDir/openclaw-payload/)
  → completeInstallation() buscaba glibc en PREFIX/glibc/lib/
  → PERO post-setup.sh NUNCA se ejecutaba
  → glibc-aarch64.tar.xz dentro del payload nunca se descomprimía
  → ld-linux-aarch64.so.1 nunca llegaba a PREFIX/glibc/lib/
  → run-openclaw.sh fallaba: "glibc linker not found"
```

---

## Cambios implementados (Mayo 2026 — v2)

### Problema raíz identificado
`post-setup.sh` usa `xz -dc | tar` para extraer `glibc-aarch64.tar.xz`.
**`xz` NO existe en `/system/bin` de Android** — solo existe si Termux lo instaló.
Por eso el Step 1 de post-setup.sh siempre fallaba silenciosamente.

### Solución: separar glibc (Kotlin) del resto (shell)

#### `InstallerManager.kt`

**`completeInstallation()`** — nuevo flujo:
```
1. setupGlibcFromPayload()   ← Kotlin puro, PayloadExtractor, NO necesita xz binario
2. runPostSetupShell()       ← shell solo para certs/node/openclaw (no usa xz)
3. repairGlibcSymlinks()     ← libc.so → libc.so.6
4. applyPermissions()
5. validatePayload()
6. writeMarker()
```

**`setupGlibcFromPayload()`** — nuevo método principal:
- Usa `PayloadExtractor.extractTarXzFile()` (Apache Commons Compress bundleado en APK)
- Busca `glibc-aarch64.tar.xz` en: payload/ → home/ → filesDir/ → Descargas/
- Si ya está extraído (ld-linux-aarch64.so.1 existe), lo salta
- Crea el marker `.glibc-extracted` para que post-setup.sh salte el Step 1

**`findGlibcArchive()`** — busca en 4 ubicaciones incluyendo Descargas del teléfono

**`installGlibcFromFile(file)`** — público, para instalación manual desde UI

**`runPostSetupShell()`** — reemplaza `runPostSetup()`:
- Crea marker `.glibc-extracted` antes de ejecutar el script
- post-setup.sh detecta el marker y salta el Step 1 (xz)
- Solo ejecuta Steps 2-5: certs, node wrapper, openclaw, DNS

#### `JsBridge.kt` — nuevos métodos JavaScript

- `getGlibcStatus()` — estado completo de glibc para la UI
- `installGlibcManually()` — instala desde archivo en homeDir o Descargas
- `pickGlibcFile()` — abre selector de archivos para glibc-aarch64.tar.xz

#### `MainActivity.kt`

- `glibcFilePickerLauncher` — nuevo launcher para seleccionar .tar.xz
- `pickGlibcFile()` — abre el selector, copia el archivo y llama `installGlibcFromFile()`

### Flujo de instalación manual de glibc (si falla automáticamente)

El usuario puede:
1. Descargar `glibc-aarch64.tar.xz` desde Termux o GitHub
2. Colocarlo en Descargas del teléfono
3. En la UI: llamar `OpenClaw.installGlibcManually()` o `OpenClaw.pickGlibcFile()`
4. La app lo extrae a `PREFIX/glibc/lib/` con PayloadExtractor

O desde terminal:
```bash
# Copiar al homeDir de la app
cp ~/storage/downloads/glibc-aarch64.tar.xz /data/data/com.openclaw.android/files/home/
# La app lo detectará automáticamente en el próximo intento de instalación
```

### `InstallerManager.kt` — cambios aplicados

#### 1. `installOffline()` mejorado
- Logs detallados de cada paso
- Verifica tamaño del payload antes de re-copiar
- Llama a `resolvePayloadDir()` para confirmar extracción
- Progreso más granular (0% → 75% extracción, 75%+ configuración)

#### 2. `completeInstallation()` — NUEVO paso crítico
Ahora ejecuta `post-setup.sh` ANTES de validar:
```
completeInstallation()
  → applyScriptUpdate()
  → runPostSetup()          ← NUEVO: ejecuta post-setup.sh
      → extrae glibc-aarch64.tar.xz → PREFIX/glibc/lib/  ← FIX PRINCIPAL
      → instala CA certs
      → crea wrapper node (glibc-wrapped)
      → instala OpenClaw desde payload/lib/openclaw/
      → configura DNS, nsswitch.conf
  → repairGlibcManually()   ← fallback si post-setup.sh falla
  → repairGlibcSymlinks()   ← repara libc.so → libc.so.6
  → applyPermissions()
  → validatePayload()
  → writeMarker()
```

#### 3. Nuevos métodos agregados
- `runPostSetup(listener)` — ejecuta `post-setup.sh` con env correcto
- `repairGlibcManually()` — fallback Kotlin puro si shell falla
- `extractGlibcArchive(archive)` — extrae tar.xz con PayloadExtractor
- `resolvePayloadDir()` — resuelve dónde quedó el payload extraído

---

## Estructura REAL del payload (confirmada Mayo 2026)

```
payload/                          ← se extrae a homeDir/payload/
├── glibc/
│   ├── lib/
│   │   ├── ld-linux-aarch64.so.1  ← 241KB — CRÍTICO, ya extraído
│   │   ├── libc.so.6              ← 2.2MB
│   │   ├── libc.so                ← symlink → libc.so.6
│   │   ├── libm.so.6              ← 1MB
│   │   └── libpthread.so.0        ← 70KB
│   └── bin/
│       ├── node                   ← Node.js ELF glibc (el binario real)
│       ├── bash
│       └── ... (herramientas glibc)
├── openclaw/
│   ├── openclaw.mjs               ← OpenClaw 2026.4.29
│   ├── package.json               ← version: "2026.4.29"
│   ├── node_modules/              ← dependencias bundleadas
│   └── dist/
├── ssl/
│   └── cert.pem                   ← CA certs
├── run-openclaw.sh                ← launcher: usa `node` del PATH
└── install.sh                     ← script mínimo (solo chmod)
```

**IMPORTANTE:** No hay `glibc-aarch64.tar.xz` dentro del payload.
glibc ya está extraído directamente en `payload/glibc/`.
`run-openclaw.sh` usa `node` del PATH — necesita `payload/glibc/bin/` en PATH.

## Versiones del payload bundleado
- **OpenClaw:** 2026.4.29
- **Node.js:** en `payload/glibc/bin/node` (ELF glibc, versión a detectar en runtime)
- **glibc:** ld-linux-aarch64.so.1 (241KB)

```
payload/                          ← se extrae a homeDir/payload/
├── glibc-aarch64.tar.xz          ← glibc comprimido (post-setup.sh lo extrae)
├── glibc/                        ← glibc ya extraído (si el tar lo incluye)
│   └── lib/
│       ├── ld-linux-aarch64.so.1 ← CRÍTICO
│       ├── libc.so.6
│       ├── libc.so → libc.so.6   ← symlink CRÍTICO
│       └── ...
├── lib/
│   ├── node/
│   │   └── bin/
│   │       └── node.real         ← binario ELF glibc Node.js v22
│   └── openclaw/
│       └── openclaw.mjs          ← OpenClaw package
├── certs/
│   └── cert.pem
└── patches/
    └── glibc-compat.js
```

**Rutas finales después de post-setup.sh:**
```
filesDir/
├── home/
│   ├── payload/                  ← payload extraído
│   ├── openclaw-payload.tar.gz   ← copia del asset
│   ├── openclaw-start.sh         ← launcher generado por post-setup.sh
│   └── .openclaw-android/
│       ├── bin/
│       │   ├── node              ← wrapper glibc-wrapped
│       │   ├── npm
│       │   └── npx
│       ├── node/
│       │   └── bin/
│       │       └── node.real     ← binario ELF
│       ├── patches/
│       │   └── glibc-compat.js
│       └── installed.json        ← marcador de instalación
└── usr/                          ← PREFIX (si no hay payload dir)
    └── glibc/
        └── lib/
            └── ld-linux-aarch64.so.1
```

---

## Flujos de instalación (A y B)

### Camino A — Payload offline (assets/openclaw-payload.tar.gz)
```
InstallerManager.install("auto" o "offline")
  → hasPayloadAsset() = true (167MB en assets)
  → installOffline()
      1. Copia asset → homeDir/openclaw-payload.tar.gz
      2. Extrae tar.gz → homeDir/
      3. completeInstallation()
          → runPostSetup() → extrae glibc, configura todo
          → validatePayload()
          → writeMarker()
```

### Camino B — proot + Ubuntu rootfs (online)
```
InstallerManager.install("proot")
  → installViaProot()
      → SetupManager.install()
          1. downloadProot() → filesDir/bin/proot
          2. downloadAndExtractRootfs() → filesDir/ubuntu-rootfs/
          3. runUbuntuSetup() → apt-get update dentro de proot
          4. installNodeInProot() → Node.js 22 dentro de Ubuntu
          5. installOpenClawInProot() → npm install -g openclaw
          6. createLaunchScripts() → openclaw-start.sh
          7. writeMarkers()
```

---

## Próximos pasos pendientes

### Prioridad 1 — Verificar que el fix funciona
Compilar y probar en dispositivo. Revisar logcat:
```
adb logcat -s InstallerManager:D post-setup:D PayloadExtractor:D
```
Buscar:
- `[post-setup] Step 1/5: Setting up glibc` → glibc extrayéndose
- `[post-setup] glibc extracted: /data/.../usr/glibc/lib/ld-linux-aarch64.so.1`
- `[post-setup] Node.js v22.x.x ready (glibc-wrapped)`
- `[post-setup] Post-setup completed successfully!`

### Prioridad 2 — Si post-setup.sh falla
Verificar que `glibc-aarch64.tar.xz` está dentro del payload:
```bash
# En Termux o adb shell
tar -tzf /data/data/com.openclaw.android/files/home/openclaw-payload.tar.gz | grep glibc
```
Si no está → el payload necesita reconstruirse con `scripts/build-payload.sh`

### Prioridad 3 — Mejorar flujo proot (Camino B)
Problemas conocidos del flujo proot:
1. `apt-get update` dentro de proot falla si DNS no está configurado
2. `npm install` tarda 5-10 min → Android puede matar el proceso
3. Solución: pre-configurar DNS antes de apt, usar `--prefer-offline`

### Prioridad 4 — ProotManager multi-arquitectura
El `ProotManager.kt` actual solo descarga binarios `aarch64`.
El APK soporta también `x86_64` (ChromeOS/emuladores).
Pendiente: detectar ABI en runtime y descargar URL correcta.

---

## Archivos clave modificados

| Archivo | Cambio |
|---------|--------|
| `InstallerManager.kt` | Agregado `runPostSetup()`, `repairGlibcManually()`, `extractGlibcArchive()`, `resolvePayloadDir()`. Mejorado `installOffline()` y `completeInstallation()` |
| `ProotManager.kt` | Sin cambios (implementación completa previa) |
| `SetupManager.kt` | Sin cambios |
| `post-setup.sh` | Sin cambios (script correcto, solo faltaba ejecutarlo) |

---

## Comandos útiles para debugging

```bash
# Ver logs de instalación en tiempo real
adb logcat -s InstallerManager:D SetupManager:D ProotManager:D PayloadExtractor:D InstallValidator:D

# Verificar estructura después de instalación
adb shell ls -la /data/data/com.openclaw.android/files/home/
adb shell ls -la /data/data/com.openclaw.android/files/home/payload/glibc/lib/
adb shell ls -la /data/data/com.openclaw.android/files/home/.openclaw-android/bin/

# Verificar que glibc linker existe y es ejecutable
adb shell ls -la /data/data/com.openclaw.android/files/home/payload/glibc/lib/ld-linux-aarch64.so.1

# Probar node manualmente
adb shell /data/data/com.openclaw.android/files/home/.openclaw-android/bin/node --version

# Ver contenido del payload tar.gz
adb shell "tar -tzf /data/data/com.openclaw.android/files/home/openclaw-payload.tar.gz | head -50"
```
