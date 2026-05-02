# OpenClaw en Android 🦞

[![Android 7.0+](https://img.shields.io/badge/Android-7.0+-brightgreen)](https://developer.android.com)
[![Termux Requerido](https://img.shields.io/badge/Termux-Requerido-orange)](https://f-droid.org/packages/com.termux/)
[![Sin proot](https://img.shields.io/badge/proot--distro-No%20Requerido-blue)](https://github.com/termux/proot-distro)
[![Licencia MIT](https://img.shields.io/github/license/AidanPark/openclaw-android)](https://github.com/AidanPark/openclaw-android/blob/main/LICENSE)
[![Estrellas](https://img.shields.io/github/stars/AidanPark/openclaw-android)](https://github.com/AidanPark/openclaw-android)
[![Descargas](https://img.shields.io/github/downloads/AidanPark/openclaw-android/total)](https://github.com/AidanPark/openclaw-android/releases)
[![Issues](https://img.shields.io/github/issues/AidanPark/openclaw-android)](https://github.com/AidanPark/openclaw-android/issues)

[English](README.in.md) | [한국어](README.ko.md) | [中文](README.zh.md)

<div align="center">
  <img src="docs/images/openclaw_android.jpg" alt="OpenClaw on Android" width="800">
  <br><br>
  <a href="#quick-start"><img src="https://img.shields.io/badge/Get%20Started-Now-brightgreen" alt="Empezar"></a>
  <a href="https://github.com/AidanPark/openclaw-android/releases"><img src="https://img.shields.io/badge/Download-APK-blue" alt="Descargar APK"></a>
  <a href="https://github.com/AidanPark/openclaw-android/stargazers"><img src="https://img.shields.io/badge/⭐-Star-yellow" alt="Estrella"></a>
</div>

> **Listo en 5 minutos** • **200MB de almacenamiento** • **Sin distro Linux necesaria**

Porque Android merece un shell.

---

## 📖 Tabla de Contenidos

- [🌟 Características](#-características)
- [🚀 Inicio Rápido](#-inicio-rápido)
- [📱 App Claw](#-app-claw)
- [📋 Configuración Paso a Paso](#-configuración-paso-a-paso)
- [⚙️ Referencia CLI](#️-referencia-cli)
- [🔄 Actualización y Respaldo](#-actualización-y-respaldo)
- [🛠️ Detalles Técnicos](#️-detalles-técnicos)
- [❓ Solución de Problemas](#-solución-de-problemas)
- [📊 Rendimiento](#-rendimiento)
- [🤖 LLM Local](#-llm-local-en-android)
- [📚 Licencia](#-licencia)

---

## 🌟 Características

| | |
|---|---|
| 🚀 **Configuración Relámpago** | Un comando instala glibc + Node.js + OpenClaw. **3-10 min** en WiFi. |
| 📱 **App Independiente** | APK con dashboard WebView + terminal PTY. No Termux necesario. |
| ⚡ **Velocidad Nativa** | Solo glibc ld.so — **sin sobrecarga proot**. Mismo rendimiento que PC. |
| 🛠️ **Cadena de Herramientas Completa** | code-server, Playwright, CLIs IA. Actualizar con `oa --update`. |

---

## Sin instalación de Linux requerida

El enfoque estándar requiere instalar proot-distro con Linux, añadiendo 700MB-1GB de sobrecarga. OpenClaw en Android instala solo el enlazador dinámico glibc (ld.so).

**Enfoque estándar** — proot-distro + Linux completo:

```
┌───────────────────────────────────────────────────┐
│ Linux Kernel                                      │
│  Android · Bionic libc · Termux                   │
│    proot-distro · Debian/Ubuntu                   │
│      GNU glibc                                    │
│      Node.js → OpenClaw                           │
└───────────────────────────────────────────────────┘
```

**Este proyecto** — solo el enlazador dinámico glibc:

```
┌───────────────────────────────────────────────────┐
│ Linux Kernel                                      │
│  Android · Bionic libc · Termux                   │
│    glibc ld.so (solo enlazador)                   │
│    ld.so → Node.js → OpenClaw                     │
└───────────────────────────────────────────────────┘
```

| | Estándar (proot-distro) | OpenClaw Android |
|---|---|---|
| 💾 Almacenamiento | 1-2GB | **~200MB** |
| ⏱️ Configuración | 20-30 min | **3-10 min** |
| ⚡ Rendimiento | Más lento (capa proot) | **Velocidad nativa** |
| 🔧 Pasos | Multi-paso | **Un comando** |

---

- **Dashboard Nativo**: Interfaz React que actúa como centro de control (Bootstrap UI).
- **Instalación Híbrida Inteligente**: 
  - *Offline (Payload)*: Extrae `openclaw-payload.tar.gz` desde assets o almacenamiento externo. Sin red, instantáneo.
  - *Online (OTA)*: Descarga el último entorno desde el repositorio si el payload no está presente.
- **CLI Integrado**: Comando `oa` compatible con el terminal de la App y Termux (detección automática de entorno).
- **Zero Overhead**: Ejecución directa vía glibc ld.so, sin capas de emulación proot.

Descarga el APK desde [Releases](https://github.com/AidanPark/openclaw-android/releases).

---

## 🚀 Inicio Rápido

> **Instalar desde F-Droid** — Termux de Play Store está descontinuado.

1. Instalar [Termux desde F-Droid](https://f-droid.org/packages/com.termux/)
2. `pkg update -y && pkg install -y curl`
3. `curl -sL myopenclawhub.com/install | bash`
4. `openclaw onboard`
5. Nueva pestaña: `openclaw gateway`
6. Abrir dashboard: [myopenclawhub.com](https://myopenclawhub.com)

---

## 📋 Configuración Paso a Paso

### Requisitos

- Android 7.0 o superior (Android 10+ recomendado)
- ~1GB de almacenamiento libre
- Conexión Wi-Fi o datos móviles

### Qué hace el instalador

1. **Entorno glibc** — Instala el enlazador dinámico glibc (vía glibc-runner de pacman)
2. **Node.js (glibc)** — Descarga Node.js linux-arm64 oficial con wrapper ld.so
3. **Conversión de rutas** — Convierte rutas Linux estándar a rutas Termux
4. **Carpeta temporal** — Configura una carpeta temp accesible para Android
5. **Bypass systemd** — Configura operación normal sin gestor de servicios
6. **Integración OpenCode** — Si se selecciona, instala OpenCode con proot + ld.so

### Paso 1: Preparar tu Teléfono

Activar **Opciones de desarrollador** → **Mantener despierto** + deshabilitar optimización de batería.

Ver la [guía Mantener Procesos Vivos](docs/disable-phantom-process-killer.md) para instrucciones detalladas.

### Paso 2: Instalar Termux

> La versión de Play Store está descontinuada. Instalar desde [F-Droid](https://f-droid.org/packages/com.termux/).

### Paso 3: Configuración Inicial de Termux

```bash
pkg update -y && pkg install -y curl
```

### Paso 4: Configuración (App o Termux)

> **Vía App Android (Recomendado)**: Simplemente abre la app, selecciona tus herramientas y comenzará la instalación híbrida de manera automática y offline si el payload está disponible.

> **Vía Termux**:
```bash
curl -sL myopenclawhub.com/install | bash && source ~/.bashrc
openclaw onboard
```

> Ejecuta `openclaw gateway` directamente en Termux, **no vía SSH**.

Abre una nueva pestaña (icono ☰ → **NEW SESSION**) y ejecuta:

```bash
openclaw gateway
```

Para detener: `Ctrl+C`. No uses `Ctrl+Z` — solo suspende el proceso.

---

## Mantener Procesos Vivos

Android puede matar procesos en segundo plano. Ver la [guía Mantener Procesos Vivos](docs/disable-phantom-process-killer.md).

## Acceder al Dashboard desde tu PC

Ver la [Guía de Configuración SSH Termux](docs/termux-ssh-guide.md).

## Gestionar Múltiples Dispositivos

Usa [Dashboard Connect](https://myopenclawhub.com) para gestionar múltiples dispositivos desde tu PC. Las configuraciones de conexión se guardan solo en localStorage del navegador — tus datos permanecen locales.

---

## ⚙️ Referencia CLI

```bash
oa --help
```

| Comando | Descripción |
|---------|-------------|
| `oa --status` | 📊 Estado del entorno (detecta App vs Termux) |
| `oa --update` | 🔄 Actualizar plataforma y herramientas |
| `oa --install` | 🛠️ Añadir herramientas (tmux, code-server, etc.) |
| `oa --backup` | 💾 Respaldo compatible con la App |
| `oa --restore` | ⬆️ Restaurar datos |
| `oa --uninstall` | 🗑️ Remover de forma limpia |

---

## 🔄 Actualización y Respaldo

### Actualización

```bash
oa --update && source ~/.bashrc
```

Actualiza de una vez: OpenClaw, code-server, OpenCode, CLIs IA y parches Android. Los componentes ya actualizados se saltan. Seguro ejecutar múltiples veces.

> Si `oa` no está disponible: `curl -sL myopenclawhub.com/update | bash && source ~/.bashrc`

### Respaldo

```bash
oa --backup
```

Los respaldos se almacenan en `~/.openclaw-android/backup/` con timestamp. Incluye configuración, estado, workspaces y agents. Ruta personalizada: `oa --backup ~/mis-respaldos/`.

### Restauración

```bash
oa --restore
```

Lista los respaldos disponibles y restaura el seleccionado a `~/.openclaw/`.

---

## ❓ Solución de Problemas

Ver la [Guía de Solución de Problemas](docs/troubleshooting.md) para soluciones detalladas.

---

## 📊 Rendimiento

Los comandos CLI pueden sentirse más lentos que en PC por la velocidad de almacenamiento del teléfono. Sin embargo, **una vez que el gateway está ejecutándose, no hay diferencia** — el proceso permanece en memoria y las respuestas IA se procesan en servidores externos.

---

## 🤖 LLM Local en Android

OpenClaw soporta inferencia LLM local vía [node-llama-cpp](https://github.com/withcatai/node-llama-cpp). El binario precompilado (`@node-llama-cpp/linux-arm64`) carga exitosamente bajo glibc — técnicamente funcional en el teléfono.

| Restricción | Detalles |
|-------------|----------|
| RAM | Modelos GGUF necesitan 2-4GB libres (7B, Q4). RAM compartida con Android |
| Almacenamiento | Modelos de 4GB a 70GB+. Espacio limitado |
| Velocidad | CPU-only en ARM es muy lento. Sin GPU offloading |
| Caso de uso | Para producción, usar APIs LLM cloud (misma velocidad que PC) |

Para experimentar: TinyLlama 1.1B (Q4, ~670MB) funciona en el teléfono.

> **¿Por qué `--ignore-scripts`?** El postinstall de node-llama-cpp intenta compilar llama.cpp desde fuente vía cmake — 30+ minutos en teléfono y falla por incompatibilidades de toolchain. Los binarios precompilados funcionan sin este paso.

---

## 🛠️ Detalles Técnicos

### Componentes Instalados

**Infraestructura Principal**

| Componente | Rol | Instalación |
|------------|-----|-------------|
| git | Control de versiones | `pkg install` |

**Dependencias Runtime (L2)**

| Componente | Rol | Instalación |
|------------|-----|-------------|
| [pacman](https://wiki.archlinux.org/title/Pacman) | Gestor paquetes glibc | `pkg install` |
| [glibc-runner](https://github.com/termux-pacman/glibc-packages) | Enlazador dinámico glibc | `pacman -Sy` |
| [Node.js](https://nodejs.org/) v22 LTS (linux-arm64) | Runtime JavaScript | nodejs.org |
| python, make, cmake, clang, binutils | Herramientas build nativos | `pkg install` |

**Plataforma OpenClaw**

| Componente | Rol | Instalación |
|------------|-----|-------------|
| [OpenClaw](https://github.com/openclaw/openclaw) | Plataforma agentes IA | `npm install -g` |
| [clawdhub](https://github.com/AidanPark/clawdhub) | Gestor de skills | `npm install -g` |
| [PyYAML](https://pyyaml.org/) | Parser YAML para `.skill` | `pip install` |

**Herramientas Opcionales**

| Componente | Rol | Instalación |
|------------|-----|-------------|
| [tmux](https://github.com/tmux/tmux) | Multiplexor terminal | `pkg install` |
| [ttyd](https://github.com/tsl0922/ttyd) | Terminal web | `pkg install` |
| [dufs](https://github.com/sigoden/dufs) | Servidor HTTP/WebDAV | `pkg install` |
| [android-tools](https://developer.android.com/tools/adb) | ADB | `pkg install` |
| [code-server](https://github.com/coder/code-server) | VS Code en navegador | GitHub |
| [OpenCode](https://opencode.ai/) | Asistente IA (TUI) | `bun install -g` |
| [Chromium](https://www.chromium.org/) | Automatización navegador (~400MB) | Script personalizado |
| [Playwright](https://playwright.dev/) | Librería automatización | Script personalizado |
| [Claude Code](https://github.com/anthropics/claude-code) | CLI IA Anthropic | `npm install -g` |
| [Gemini CLI](https://github.com/google-gemini/gemini-cli) | CLI IA Google | `npm install -g` |
| [Codex CLI](https://github.com/DioNanos/codex-termux) | CLI IA (fork Termux) | `npm install -g` |

### Estructura del Proyecto

```
openclaw-android/
├── bootstrap.sh                # Instalador one-liner curl | bash
├── install.sh                  # Instalador principal (punto de entrada)
├── oa.sh                       # CLI unificado ($PREFIX/bin/oa)
├── post-setup.sh               # Post-bootstrap App Claw (OTA)
├── update.sh                   # Wrapper → update-core.sh
├── update-core.sh              # Actualizador ligero
├── uninstall.sh                # Remoción limpia
├── patches/
│   ├── glibc-compat.js         # Parches runtime Node.js
│   ├── argon2-stub.js          # Stub argon2 (code-server)
│   ├── termux-compat.h         # Header C para builds Bionic
│   ├── spawn.h                 # Stub POSIX spawn
│   └── systemctl               # Stub systemd
├── scripts/
│   ├── lib.sh                  # Librería funciones compartidas
│   ├── check-env.sh            # Chequeo pre-vuelo
│   ├── install-infra-deps.sh   # Infraestructura L1
│   ├── install-glibc.sh        # glibc-runner (L2)
│   ├── install-nodejs.sh       # Node.js wrapper glibc (L2)
│   ├── install-build-tools.sh  # Herramientas build (L2)
│   ├── backup.sh               # Respaldo/restauración
│   ├── install-chromium.sh     # Chromium
│   ├── install-playwright.sh   # Playwright
│   ├── install-code-server.sh  # code-server
│   ├── install-opencode.sh     # OpenCode
│   ├── setup-env.sh            # Variables de entorno
│   └── setup-paths.sh          # Directorios y symlinks
├── platforms/
│   └── openclaw/
│       ├── config.env          # Metadatos y dependencias
│       ├── env.sh              # Variables de entorno
│       ├── install.sh          # Instalación plataforma
│       ├── update.sh           # Actualización plataforma
│       ├── uninstall.sh        # Remoción plataforma
│       ├── status.sh           # Estado plataforma
│       ├── verify.sh           # Verificación plataforma
│       └── patches/            # Parches específicos
├── tests/
│   └── verify-install.sh       # Verificación post-instalación
└── docs/
    ├── disable-phantom-process-killer.md
    ├── termux-ssh-guide.md
    ├── troubleshooting.md
    └── images/
```

### Arquitectura Plugin-Plataforma

```
Orquestadores (install.sh, update-core.sh, uninstall.sh)
  └── Agnóstica de plataforma. Lee config.env y delega.

Scripts Compartidos (scripts/)
  ├── L1: install-infra-deps.sh (siempre)
  ├── L2: install-glibc.sh, install-nodejs.sh,
  │       install-build-tools.sh (condicional config.env)
  └── L3: Herramientas opcionales (seleccionadas usuario)

Plugins Plataforma (platforms/<name>/)
  ├── config.env: declara dependencias (PLATFORM_NEEDS_*)
  └── install.sh / update.sh / uninstall.sh / ...
```

| Capa | Alcance | Ejemplos | Controlado por |
|------|---------|----------|----------------|
| L1 | Infraestructura (siempre) | git, `pkg update` | Orquestador |
| L2 | Runtime plataforma (condicional) | glibc, Node.js, build tools | Banderas `config.env` |
| L3 | Herramientas opcionales | tmux, code-server, CLIs IA | Prompts usuario |

### Flujo de Instalación — 8 Pasos

| Paso | Descripción |
|------|-------------|
| [1/8] Chequeo de Entorno | Termux, arquitectura CPU, espacio disco (mín. 1000MB), Phantom Process Killer |
| [2/8] Selección de Plataforma | Carga `config.env`. Actualmente fijo a `openclaw` |
| [3/8] Herramientas Opcionales | 11 prompts Y/n: tmux, ttyd, dufs, android-tools, Chromium, Playwright, code-server, OpenCode, Claude Code, Gemini CLI, Codex CLI |
| [4/8] Infraestructura L1 | `pkg update && pkg upgrade`, instala `git`, crea directorios base |
| [5/8] Dependencias Runtime L2 | glibc-runner, Node.js v22 LTS, herramientas build (condicional) |
| [6/8] Instalación Plataforma | `npm install -g openclaw@latest --ignore-scripts`, parches, clawdhub |
| [7/8] Herramientas Opcionales | Instala las herramientas seleccionadas en el paso 3 |
| [8/8] Verificación | Chequeos FAIL/WARN: Node.js >= 22, npm, OA_GLIBC, glibc ld.so, node wrapper |

### Flujo de Actualización — `oa --update`

| Paso | Descripción |
|------|-------------|
| [1/5] Pre-flight check | Valida Termux, curl, plataforma, arquitectura |
| [2/5] Descarga | Tarball GitHub → directorio temporal |
| [3/5] Infraestructura | Actualiza lib.sh, setup-env.sh, parches, CLI `oa` |
| [4/5] Plataforma | `openclaw@latest`, parches, clawdhub, sharp |
| [5/5] Herramientas | Actualiza solo las herramientas ya instaladas |

---

## 🎉 Únete a la Comunidad

[⭐ Danos una estrella](https://github.com/AidanPark/openclaw-android/stargazers) •
[🐛 Issues](https://github.com/AidanPark/openclaw-android/issues) •
[💬 Discusiones](https://github.com/AidanPark/openclaw-android/discussions)

---

## 📚 Licencia

MIT License. Ver [LICENSE](LICENSE) para detalles.
