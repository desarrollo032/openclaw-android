# Historial de cambios

Todos los cambios relevantes de este proyecto se documentan en este archivo.

El formato sigue [Keep a Changelog](https://keepachangelog.com/) y el proyecto se adhiere a [Semantic Versioning](https://semver.org/).

---

## Índice

- [App v0.5.0](#app-v050---2026-05-08)
- [Script v1.0.27](#script-v1027---2026-04-13)
- [Script v1.0.26](#script-v1026---2026-04-12)
- [Script v1.0.25](#script-v1025---2026-04-11)
- [Script v1.0.24](#script-v1024---2026-04-11)
- [Script v1.0.23](#script-v1023---2026-04-11)
- [Script v1.0.22](#script-v1022---2026-04-10)
- [Script v1.0.21](#script-v1021---2026-04-07)
- [Script v1.0.20](#script-v1020---2026-04-06)
- [Script v1.0.19](#script-v1019---2026-04-06)
- [Script v1.0.18](#script-v1018---2026-04-04)
- [Script v1.0.17](#script-v1017---2026-04-03)
- [Script v1.0.16](#script-v1016---2026-04-02)
- [Script v1.0.15](#script-v1015---2026-04-01)
- [Script v1.0.14](#script-v1014---2026-04-01)
- [Script v1.0.13](#script-v1013---2026-03-31)
- [App v0.4.0 / Script v1.0.12](#app-v040--script-v1012---2026-03-30)
- [v1.0.6](#106---2026-03-10)
- [v1.0.5](#105---2026-03-06)
- [v1.0.4](#104---2025-12-15)
- [v1.0.3](#103---2025-11-20)
- [v1.0.2](#102---2025-10-15)
- [v1.0.1](#101---2025-09-01)
- [v1.0.0](#100---2025-08-15)

---

## [App v0.5.0] - 2026-05-08

### Añadido
- **Terminal interactivo autocontenido**: implementación completa de terminal usando las librerías oficiales de Termux (`terminal-emulator` y `terminal-view` v0.119.0).
  - Shell funcional con `/system/bin/sh` (no requiere la app Termux instalada).
  - Soporte para comandos básicos: `ls`, `cd`, `pwd`, `echo`, `cat`, `mkdir`, etc.
  - Entrada de teclado completa (físico y virtual).
  - Scroll táctil y selección de texto.
  - Configuración de colores ANSI y tamaño de fuente.
  - Manejo completo del ciclo de vida (`TerminalSession`, `TerminalView`).
  - `TerminalActivity.kt`: actividad principal del terminal interactivo.
  - Soporte para gestos de zoom y selección de texto.
- **Documentación actualizada**: `README.md` y `DOCUMENTACION_TECNICA.md` con detalles del terminal.

### Cambiado
- Actualizar `build.gradle.kts` con dependencias de Termux desde JitPack.
- Registrar `TerminalActivity` en `AndroidManifest.xml`.
- Crear layout `activity_terminal.xml` con `TerminalView`.

---

## [Script v1.0.27] - 2026-04-13

### Corregido
- `oa --update` mostraba un error de sintaxis tras la auto-actualización — el proceso shell en ejecución seguía leyendo el archivo `oa` ya reemplazado, provocando un error de parsing en la línea 240. Se añadió `exit 0` después de la actualización para evitarlo ([#110](https://github.com/desarrollo032/openclaw-android/issues/110)).

---

## [Script v1.0.26] - 2026-04-12

### Cambiado
- Migración de Codex CLI desde `@openai/codex` (upstream) a `@mmmbuto/codex-cli-termux` (fork DioNanos/codex-termux, optimizado para Termux). El paquete upstream incluye un binario musl estático cuyo resolver DNS hardcodea `/etc/resolv.conf` (archivo que no existe en Android), provocando conexiones inestables. El fork compila como binario Bionic dinámico que usa el stack DNS nativo de Android, resolviendo el patrón `Stream disconnected` / `error sending request` reportado por usuarios detrás de proxies. El comando CLI (`codex`) no cambia ([#108](https://github.com/desarrollo032/openclaw-android/issues/108)).

### Corregido
- El launcher de Codex CLI fallaba bajo el namespace `com.openclaw.android` — el symlink `$PREFIX/bin/codex` creado por npm apunta a una cadena de launchers JS que calcula mal las rutas bajo el namespace no estándar de la app Android. Se reemplaza el symlink por un wrapper bash que setea `LD_LIBRARY_PATH` y hace `exec` directo a `codex.bin`, siguiendo el mismo patrón que el wrapper del CLI de openclaw. Aplicado a todos los flujos de entrega (App Install, Termux Install, Update) vía hook de npm wrapper y creación inline post-install ([#108](https://github.com/desarrollo032/openclaw-android/issues/108)).

---

## [Script v1.0.25] - 2026-04-11

### Corregido
- `bad interpreter: Permission denied` al ejecutar herramientas CLI instaladas globalmente con npm (codex, claude, clawdhub, etc.) directamente desde la shell en Android/Termux. La causa raíz es el shebang `#!/usr/bin/env node` en los `.js` de entrada, que Android no puede resolver. Fix en dos capas: (1) el hook wrapper de npm reescribe automáticamente los shebangs después de cada `npm install -g`, y (2) defensa en profundidad en los scripts de instalación/actualización para capturar cualquier caso que escape la capa 1.

---

## [Script v1.0.24] - 2026-04-11

### Corregido
- Se evita contaminar permanentemente el `~/.npmrc` del usuario durante la instalación — antes `post-setup.sh` detectaba que `registry.npmjs.org` era lento y escribía `registry=https://registry.npmmirror.com` en `~/.npmrc`, afectando todos los proyectos npm del usuario para siempre sin recuperación automática. Ahora el instalador usa la variable de entorno `NPM_CONFIG_REGISTRY` con alcance de sesión y cachea el registry elegido en `~/.openclaw-android/.npm-registry`, re-exportándolo desde `~/.bashrc` en cada login. Los usuarios afectados por v1.0.22/v1.0.23 se rescatan automáticamente en el siguiente `oa --update` porque las env vars sobrescriben `~/.npmrc`, dejando su npmrc personal intacto (con tokens de auth, registries de scope, etc.) ([#107](https://github.com/desarrollo032/openclaw-android/issues/107)).
- Se cubren las tres vías de instalación para la detección del registry npm — App Install (`post-setup.sh`), Termux Install (`install.sh`) y Update (`update-core.sh`). `scripts/setup-env.sh` inyecta la línea de re-export de `NPM_CONFIG_REGISTRY` dentro del bloque marcado `# >>> OpenClaw on Android >>>` de `~/.bashrc`, garantizando que la vía Termux y la de update tengan la misma reevaluación sesión-a-sesión que la App Install.

---

## [Script v1.0.23] - 2026-04-11

### Corregido
- Se preserva el `~/.gitconfig` existente del usuario durante el post-setup — antes `cat > ~/.gitconfig` sobrescribía todos los ajustes del usuario (nombre, email, aliases). Ahora se usa `git config --global` para establecer únicamente `http.sslCAInfo` y `url.https://github.com/.insteadOf`, manteniendo intactas las entradas del usuario ([#107](https://github.com/desarrollo032/openclaw-android/issues/107)).

---

## [Script v1.0.22] - 2026-04-10

### Añadido
- **Auto-wrapping de binarios ELF**: detecta binarios glibc vía `PT_INTERP` y los enruta a través de `ld.so`, permitiendo ejecutar binarios nativos instalados con npx (como `codex-acp`) en Android ([#103](https://github.com/desarrollo032/openclaw-android/issues/103)).
- **Resolución de shebang**: maneja scripts `#!/usr/bin/env` sin `libtermux-exec.so` resolviendo intérpretes desde `PATH` en JavaScript.
- **Interceptación de invocaciones a shell**: detecta el patrón `spawn('sh', ['-c', 'cmd'])` usado por npm/npx y resuelve los comandos directamente.
- **Deploy de librerías glibc complementarias**: empaqueta `libcap.so.2` para soporte de binarios nativos de terceros.
- **Atajo DNS para localhost**: devuelve `127.0.0.1` inmediatamente para resoluciones de localhost sin consultar DNS externo ([#105](https://github.com/desarrollo032/openclaw-android/issues/105)).
- Se crea `$PREFIX/glibc/etc/hosts` si no existe, garantizando que `getaddrinfo` pueda resolver localhost ([#105](https://github.com/desarrollo032/openclaw-android/issues/105)).

### Cambiado
- Ya no se restaura `LD_PRELOAD` en `glibc-compat.js` — `libtermux-exec.so` lo reinyectaba vía hook de `execve`, haciendo crashear los procesos hijos de glibc con errores `Could not find a PHDR`.
- Se usa siempre la shell de Termux para `exec`/`execSync` en todas las versiones de Android (antes solo Android 7-8).

---

## [Script v1.0.21] - 2026-04-07

### Corregido
- `oa --backup` salía con código de error 1 por `tmpdir: unbound variable` — el `trap` usaba una variable local que salía de alcance.
- `oa --backup` / `oa --restore` y `ask_yn` fallaban en entornos sin TTY (SSH pipe, no interactivo) — fallback a stdin cuando `/dev/tty` no está disponible.

---

## [Script v1.0.20] - 2026-04-06

### Corregido
- La restauración de dependencias se bloqueaba por el build fallido de `sharp` — `npm install` dentro del directorio de openclaw disparaba el build nativo de sharp, que falla en Termux y bloquea todas las demás dependencias. Ahora se ejecuta `postinstall-bundled-plugins.mjs` directamente con `npm_config_ignore_scripts=true` para saltarse sharp mientras se instalan las dependencias del channel ([#92](https://github.com/desarrollo032/openclaw-android/issues/92)).
- La restauración de dependencias se saltaba cuando openclaw ya estaba en la última versión — ahora se verifica la presencia de `@buape/carbon` en lugar de la bandera `OPENCLAW_UPDATED`.

---

## [Script v1.0.19] - 2026-04-06

### Corregido
- Dependencias del channel faltantes tras un install con `--ignore-scripts` — se reinstalan dentro del directorio del paquete openclaw para restaurar módulos opcionales como `@buape/carbon` y `grammy` ([#92](https://github.com/desarrollo032/openclaw-android/issues/92)).

---

## [Script v1.0.18] - 2026-04-04

### Corregido
- `process.execPath` apuntaba a `ld-linux-aarch64.so.1` en lugar del wrapper de node — `glibc-compat.js` tenía la ruta incorrecta (`node/bin/node` en vez de `bin/node`), provocando fallos al hacer spawn de procesos hijos en OpenClaw 4.2 con `--disable-warning=ExperimentalWarning` ([#88](https://github.com/desarrollo032/openclaw-android/issues/88)).
- Se añade la env var `_OA_WRAPPER_PATH` al wrapper de node — elimina la adivinanza de rutas en `glibc-compat.js`.
- `verify-compat.sh` ya no chequea rutas de wrapper incorrectas — los tests ahora verifican comportamiento (script ejecutable, no ELF) en lugar de rutas hardcodeadas.
- `install.sh` ya no omite `$BIN_DIR` del `PATH` de la sesión — los comandos node/npm podían fallar después del Step 5.
- Se corrige el README (en/ko/zh) que documentaba mal la ruta del wrapper (`node/bin/node` → `bin/node`).
- El wrapper de npm escribía a través de un symlink y corrompía `openclaw.mjs` — npm crea el symlink `$PREFIX/bin/openclaw` → `openclaw.mjs`, y nuestro shim seguía el link destruyendo el archivo original ([#89](https://github.com/desarrollo032/openclaw-android/issues/89)).

---

## [Script v1.0.17] - 2026-04-03

### Corregido
- Falso positivo de "glibc node wrapper not found" en la verificación de instalación — `verify-install.sh` y `status.sh` referenciaban la ruta antigua `node/bin/` en lugar de la nueva `bin/` ([#87](https://github.com/desarrollo032/openclaw-android/issues/87)).
- Se añade la constante `BIN_DIR` a `lib.sh` para evitar derivas de rutas hardcodeadas en los scripts de verificación.

---

## [Script v1.0.16] - 2026-04-02

### Corregido
- Auto-reparcheo del wrapper del CLI openclaw tras `npm install/update -g openclaw` — previene la ruptura del shebang `/usr/bin/env` en Termux ([#86](https://github.com/desarrollo032/openclaw-android/issues/86)).
- Movidos los wrappers node/npm/npx a un directorio dedicado `bin/` que no es sobrescrito por npm.
- Se corrige la creación faltante del wrapper `bin/node` en el path de reparación cuando ya estaba instalado.

---

## [Script v1.0.15] - 2026-04-01

### Corregido
- `dns.promises.lookup` no estaba parcheado en `glibc-compat.js` — la SSRF guard de `web_search` en OpenClaw usa `node:dns/promises`, que se saltaba el fix de DNS c-ares y provocaba `getaddrinfo EAI_AGAIN` en hosts sin `resolv.conf` ([#83](https://github.com/desarrollo032/openclaw-android/issues/83)).

---

## [Script v1.0.14] - 2026-04-01

### Corregido
- Auto-desactivación de Bonjour/mDNS en runtime cuando solo se ve la interfaz loopback — Android/Termux no puede emitir multicast, provocando repetidos logs "Announcement failed as of socket errors!" del gateway ([#84](https://github.com/desarrollo032/openclaw-android/issues/84)).

---

## [Script v1.0.13] - 2026-03-31

### Añadido
- Playwright como herramienta opcional de instalación (`oa --install`) — instala `playwright-core`, autoconfigura la ruta de Chromium y las variables de entorno.
- Auto-reparcheo del wrapper del CLI openclaw tras `npm install/update -g openclaw` — previene la ruptura del shebang en Termux (#86).

### Cambiado
- Gson `2.12.1 → 2.13.2`.
- androidx.core:core-ktx `1.17.0 → 1.18.0`.
- ktlint gradle plugin `14.1.0 → 14.2.0`.
- Gradle wrapper `9.3.1 → 9.4.1`.
- eslint `9.39.4 → 10.0.3`.
- globals `16.5.0 → 17.4.0`.
- eslint-plugin-react-refresh `0.4.24 → 0.5.2`.
- GitHub Actions: `checkout v4 → v6`, `setup-node v4 → v6`, `setup-java v4 → v5`, `upload-artifact v4 → v7`, `download-artifact v4 → v8`.

---

## [App v0.4.0 / Script v1.0.12] - 2026-03-30

### Añadido
- App: soporte **i18n** — inglés, coreano (한국어), chino (中文) con auto-detección.
- App: selector de idioma en *Settings*.
- README en chino (`README.zh.md`) con enlace de descarga de mirror para China.
- Enlaces de cambio de idioma en `README.md`, `README.ko.md`, `README.zh.md`.
- Fallback a mirrors GitHub para redes restringidas (`ghfast.top`, `ghproxy.net`).
- Auto-cambio de registry npm a `npmmirror.com` cuando `npmjs.org` no responde.
- Wrapper centralizado de logging `AppLogger`, reemplazando todas las llamadas a `android.util.Log`.
- Infraestructura de tests unitarios (JUnit5 + MockK, 22 tests).
- Workflow CI de calidad de código (shellcheck, sync check, markdownlint, freshness de docs, kotlin lint, tests unitarios).
- `shellcheck` y `markdownlint` en el hook `pre-commit`.
- Verificación de sincronía de `post-setup.sh` en el hook `pre-commit`.
- Hooks de Claude Code (push warning, freshness de documentos, ejecución automática de shellcheck).

### Cambiado
- Resueltas las 48 violaciones de detekt — sin necesidad de baseline.
- Resueltas las 43 violaciones de shellcheck en todos los scripts.
- Resueltas las 125 violaciones de markdownlint en toda la documentación.
- Refactor de `BootstrapManager`, `JsBridge` y `MainActivity` para reducir complejidad.
- Patrones `A && B || C` convertidos a `if/then/else` en `install.sh` e `install-tools.sh`.
- Bump de la versión de app a `v0.4.0` (`versionCode 9`).
- Bump de la versión de script a `v1.0.12`.

---

## [1.0.6] - 2026-03-10

### Cambiado
- Limpieza de la instalación existente al reinstalar.

---

## [1.0.5] - 2026-03-06

### Añadido
- APK Android autónomo con UI WebView, terminal nativa y barra de teclas extra.
- Tab bar multi-sesión con navegación por swipe.
- Auto-arranque al boot vía `BootReceiver`.
- Soporte de automatización de Chromium (`scripts/install-chromium.sh`).
- Comando `oa --install` para instalar herramientas opcionales de forma independiente.

### Corregido
- Error de sintaxis en `update-core.sh` (`fi` extra en la línea 237).
- Procesado de imágenes `sharp` con fallback WASM para la frontera glibc/bionic.

### Cambiado
- Modo de entrada del terminal a `TYPE_NULL` para comportamiento estricto.

---

## [1.0.4] - 2025-12-15

### Cambiado
- Actualización de Node.js a `v22.22.0` para soporte FTS5 (`node:sqlite` bundle estático).
- Mostrar versión en todos los mensajes de skip y de update completado.

### Eliminado
- Soporte de oh-my-opencode (OpenCode usa Bun interno, los plugins basados en PATH no se detectaban).

### Corregido
- El glob de actualización de versión seleccionaba la más antigua en lugar de la más reciente.
- Fallos de build de módulos nativos durante el update.

---

## [1.0.3] - 2025-11-20

### Añadido
- `.gitattributes` para forzar finales de línea LF.

### Cambiado
- Bump de versión a `v1.0.3`.

---

## [1.0.2] - 2025-10-15

### Añadido
- Arquitectura de plugins de plataforma (estructura `platforms/<nombre>/`).
- Librería compartida de scripts (`scripts/lib.sh`).
- Sistema de verificación (`tests/verify-install.sh`).

### Cambiado
- Refactor del flujo de instalación en scripts modulares.
- Separación del código específico de plataforma de la infraestructura.

---

## [1.0.1] - 2025-09-01

### Corregido
- Correcciones iniciales de bugs y mejoras de estabilidad.

---

## [1.0.0] - 2025-08-15

### Añadido
- Release inicial.
- Ejecución basada en `glibc-runner` (sin requerir proot-distro).
- Instalador one-command (`curl | bash`).
- Wrapper de Node.js sobre glibc para ejecutar binarios Linux estándar en Android.
- Conversión de rutas para compatibilidad con Termux.
- Herramientas opcionales: `tmux`, `code-server`, OpenCode, AI CLIs.
- Verificación post-instalación.
