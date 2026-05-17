# Solución de problemas

Problemas comunes al usar **OpenClaw en Android** (app nativa con proot + Alpine Linux).

> **Nota:** Esta guía cubre tanto la app nativa (`com.openclaw.android`) como el stack clásico sobre Termux.
> Los problemas marcados como **[Legacy]** solo afectan a instalaciones pre-`v1.5` (glibc-runner) o a instalaciones sobre Termux.
> En la app nativa con proot + Alpine, Node.js corre dentro del contenedor Alpine con glibc nativa, resolviendo la mayoría de los problemas del stack legacy.

---

## Índice

- [El gateway no arranca: "gateway already running"](#el-gateway-no-arranca-gateway-already-running)
- [Gateway desconectado: "gateway not connected"](#gateway-desconectado-gateway-not-connected)
- [SSH fallido: "Connection refused"](#ssh-fallido-connection-refused)
- [`openclaw --version` falla](#openclaw---version-falla)
- ["Cannot find module glibc-compat.js" [Legacy]](#cannot-find-module-glibc-compatjs-legacy)
- [`systemctl --user unavailable` durante el update [Legacy]](#systemctl---user-unavailable-durante-el-update-legacy)
- [`sharp` falla durante `openclaw update` [Legacy]](#sharp-falla-durante-openclaw-update-legacy)
- [`clawdhub` falla con "Cannot find package 'undici'" [Legacy]](#clawdhub-falla-con-cannot-find-package-undici-legacy)
- ["not supported on android" [Legacy]](#not-supported-on-android-legacy)
- [`openclaw update` con error de `node-llama-cpp` [Legacy]](#openclaw-update-con-error-de-node-llama-cpp-legacy)
- [OpenCode con errores `EACCES` [Legacy]](#opencode-con-errores-eacces-legacy)
- [`libldlinux.so` rechaza `--disable-warning=ExperimentalWarning` [Legacy]](#libldlinuxso-rechaza---disable-warningexperimentalwarning-legacy)

---

## El gateway no arranca: "gateway already running"

```
Gateway failed to start: gateway already running (pid XXXXX); lock timeout after 5000ms
Port 18789 is already in use.
```

### Causa

Un proceso gateway previo terminó de forma anormal y dejó un lock file o un proceso zombi. Suele ocurrir cuando:

- La conexión SSH cae y el proceso gateway queda huérfano.
- Se usó **`Ctrl+Z`** (suspender) en lugar de **`Ctrl+C`** (terminar), dejando el proceso vivo en segundo plano.
- Termux fue forzado a cerrar por Android.

> **Nota:** usa siempre `Ctrl+C` para detener el gateway. `Ctrl+Z` solo lo suspende.

### Solución

**Paso 1 — Buscar y matar procesos restantes:**

```bash
ps aux | grep -E "node|openclaw" | grep -v grep
```

Si hay procesos listados, anota el PID y mátalos:

```bash
kill -9 <PID>
```

**Paso 2 — Eliminar lock files:**

```bash
rm -rf $PREFIX/tmp/openclaw-*
```

**Paso 3 — Reiniciar el gateway:**

```bash
openclaw gateway
```

### Si sigue sin funcionar

Cierra completamente la app Termux y ábrela de nuevo, después ejecuta `openclaw gateway`. Reiniciar el teléfono limpia todo el estado de forma confiable.

---

## Gateway desconectado: "gateway not connected"

```
send failed: Error: gateway not connected
disconnected | error
```

### Causa

El proceso gateway se detuvo o la sesión SSH se desconectó.

### Solución

Revisa la sesión SSH donde corría el gateway. Si se desconectó, reconecta y arranca de nuevo:

```bash
openclaw gateway
```

Si aparece el error "gateway already running", consulta la sección anterior.

---

## SSH fallido: "Connection refused"

```
ssh: connect to host 192.168.45.139 port 8022: Connection refused
```

### Causa

El servidor SSH de Termux (`sshd`) no está corriendo. Cerrar la app Termux o reiniciar el teléfono detienen `sshd`.

### Solución

Abre la app Termux en el teléfono y ejecuta `sshd`. Puedes escribirlo directamente o enviarlo vía adb:

```bash
adb shell input text 'sshd'
adb shell input keyevent 66
```

La IP puede haber cambiado — verifícala:

```bash
adb shell input text 'ifconfig'
adb shell input keyevent 66
```

> Para arrancar `sshd` automáticamente, añade `sshd 2>/dev/null` al final de tu `~/.bashrc` para que se inicie cada vez que abres Termux.

---

## `openclaw --version` falla

### Causa

Las variables de entorno no están cargadas.

### Solución

```bash
source ~/.bashrc
```

O cierra y abre Termux por completo.

---

## "Cannot find module glibc-compat.js" [Legacy]

```
Error: Cannot find module '/data/data/com.termux/files/home/.openclaw-lite/patches/glibc-compat.js'
```

> Este problema solo afecta a instalaciones pre-`1.0.0` (Bionic). En `v1.0.0+` (glibc), `glibc-compat.js` se carga desde el wrapper de node, no desde `NODE_OPTIONS`.

### Causa

La variable `NODE_OPTIONS` en `~/.bashrc` aún referencia la ruta antigua (`.openclaw-lite`). Ocurre al actualizar desde una versión donde el proyecto se llamaba "OpenClaw Lite".

### Solución

Ejecuta el updater para refrescar el bloque de env:

```bash
oa --update && source ~/.bashrc
```

O arréglalo manualmente:

```bash
sed -i 's/\.openclaw-lite/\.openclaw-android/g' ~/.bashrc && source ~/.bashrc
```

---

## `systemctl --user unavailable` durante el update [Legacy]

```
Gateway service check failed: Error: systemctl --user unavailable: spawn systemctl ENOENT
```

### Causa

Tras `openclaw update`, OpenClaw intenta reiniciar el servicio gateway con `systemctl`. Como Termux no tiene systemd, el binario `systemctl` no existe y el comando falla con `ENOENT`.

### Impacto

**El error es inofensivo.** El update se completó correctamente — solo falló el reinicio automático del servicio.

### Solución

Inicia el gateway manualmente:

```bash
openclaw gateway
```

Si ya estaba corriendo antes del update, quizá necesites matar el proceso antiguo primero (ver sección "gateway already running").

---

## `sharp` falla durante `openclaw update` [Legacy]

```
npm error gyp ERR! not ok
Update Result: ERROR
Reason: global update
```

### Causa

- **v1.0.0+ (glibc):** `sharp` usa binarios prebuilt (`@img/sharp-linux-arm64`). El error es raro — generalmente significa que el binario prebuilt falta o está corrupto.
- **Pre-1.0.0 (Bionic):** cuando `openclaw update` ejecuta npm como subproceso, las env vars de build específicas de Termux (`CXXFLAGS`, `GYP_DEFINES`) no están disponibles en el contexto del subproceso, provocando fallos al compilar el módulo nativo.

### Impacto

**El error es no crítico.** OpenClaw se actualizó correctamente — solo el módulo `sharp` (usado para procesamiento de imágenes) falló al rebuild. OpenClaw funciona normalmente sin él.

### Solución

Recompila `sharp` manualmente con el script provisto:

```bash
bash ~/.openclaw-android/scripts/build-sharp.sh
```

Alternativamente, usa `oa --update` en lugar de `openclaw update` — maneja `sharp` automáticamente:

```bash
oa --update && source ~/.bashrc
```

---

## `clawdhub` falla con "Cannot find package 'undici'" [Legacy]

```
Error [ERR_MODULE_NOT_FOUND]: Cannot find package 'undici' imported from /data/data/com.termux/files/usr/lib/node_modules/clawdhub/dist/http.js
```

### Causa

Node.js v24+ en Termux no incluye el paquete `undici`, del que depende `clawdhub` para sus peticiones HTTP.

### Solución

Ejecuta el updater para que instale automáticamente `clawdhub` y su dependencia `undici`:

```bash
oa --update && source ~/.bashrc
```

O arréglalo manualmente:

```bash
cd $(npm root -g)/clawdhub && npm install undici
```

---

## "not supported on android" [Legacy]

```
Gateway status failed: Error: Gateway service install not supported on android
```

> Este problema solo afecta a instalaciones pre-`1.0.0` (Bionic). En `v1.0.0+` (glibc), Node.js reporta nativamente `process.platform` como `'linux'`.

### Causa

**Pre-1.0.0 (Bionic):** el override de `process.platform` en `glibc-compat.js` no se está aplicando porque `NODE_OPTIONS` no está seteado.

### Solución

Verifica qué Node.js se está usando:

```bash
node -e "console.log(process.platform)"
```

Si imprime `android`, el wrapper glibc de node no está siendo usado. Carga el entorno:

```bash
source ~/.bashrc
```

Si sigue imprimiendo `android`, actualiza a la última versión (v1.0.0+ usa glibc y lo resuelve permanentemente):

```bash
oa --update && source ~/.bashrc
```

---

## `openclaw update` con error de `node-llama-cpp` [Legacy]

```
[node-llama-cpp] Cloning ggml-org/llama.cpp (local bundle)
npm error 48%
Update Result: ERROR
```

### Causa

Al actualizar OpenClaw vía npm, el `postinstall` de `node-llama-cpp` intenta clonar y compilar `llama.cpp` desde fuente. Esto falla en Termux porque el toolchain (`cmake`, `clang`) está linkado contra Bionic mientras Node.js corre bajo glibc — los dos son incompatibles para compilación nativa.

### Impacto

**El error es inofensivo.** Los binarios prebuilt de `node-llama-cpp` (`@node-llama-cpp/linux-arm64`) ya están instalados y funcionan correctamente bajo glibc. El build fallido **no los sobrescribe**.

`node-llama-cpp` se usa para embeddings locales opcionales. Si los binarios prebuilt no cargan, OpenClaw cae a proveedores remotos de embedding (OpenAI, Gemini, etc.).

### Solución

No requiere acción. El error puede ignorarse. Para verificar que los binarios prebuilt funcionan:

```bash
node -e "require('$(npm root -g)/openclaw/node_modules/@node-llama-cpp/linux-arm64/bins/linux-arm64/llama-addon.node'); console.log('OK')"
```

---

## OpenCode con errores `EACCES` [Legacy]

```
EACCES: Permission denied while installing opencode-ai
Failed to install 118 packages
```

### Causa

Bun intenta crear hardlinks y symlinks al instalar paquetes. El filesystem de Android restringe estas operaciones, provocando errores `EACCES` para las dependencias.

### Impacto

**Los errores son inofensivos.** El binario principal (`opencode`) se instala correctamente a pesar de los fallos de links de dependencias. La concatenación `ld.so` y el wrapper proot manejan la ejecución.

### Solución

No requiere acción. Verifica que OpenCode funciona:

```bash
opencode --version
```

---

## `libldlinux.so` rechaza `--disable-warning=ExperimentalWarning` [Legacy]

```
.../libldlinux.so: unrecognized option '--disable-warning=ExperimentalWarning'
```

### Causa

Un `NODE_OPTIONS` heredado o stale se inyecta en el arranque del runtime. Algunos builds de Node empaquetados en Android no soportan esta opción de warning y rompen el arranque.

### Solución

OpenClaw sanea ahora los wrappers de runtime (`openclaw`, `node`, `npm`) para:

- `unset NODE_OPTIONS`
- `set NODE_NO_WARNINGS=1`
- Invocar `libldlinux.so` solo con argumentos soportados por el loader.

Si tu payload se instaló antes de este fix, regenera los wrappers reejecutando el onboarding:

```bash
openclaw onboard
```
