[![Android 7.0+](https://img.shields.io/badge/Android-7.0%2B-brightgreen?logo=android)](https://developer.android.com)
[![Termux](https://img.shields.io/badge/Termux-Requerido-orange?logo=terminal)](https://f-droid.org/packages/com.termux/)
[![Sin proot](https://img.shields.io/badge/proot--distro-No%20Requerido-blue)](https://proot-me.github.io/)
[![Licencia MIT](https://img.shields.io/github/license/AidanPark/openclaw-android)](https://github.com/AidanPark/openclaw-android/blob/main/LICENSE)
[![Estrellas](https://img.shields.io/github/stars/AidanPark/openclaw-android?style=social)](https://github.com/AidanPark/openclaw-android)

<div align="center">

<img src="docs/images/openclaw_android.jpg" alt="OpenClaw en Android">

**🌟 OpenClaw en Android: Shell AI Nativo (200MB sin Distro Linux)**

[![Descargar APK](https://img.shields.io/badge/Descargar-APK-brightgreen?logo=android&logoColor=white)](https://github.com/AidanPark/openclaw-android/releases/latest)
[![Instalar Termux](https://img.shields.io/badge/Instalar-Termux-blue?logo=f-droid&logoColor=white)](https://f-droid.org/packages/com.termux/)

</div>

## Sin Instalación Linux Requerida

Enfoque estándar: proot-distro + Linux (1GB). **OpenClaw Android**: Solo glibc ld.so.

| | Estándar | **Este Proyecto** |
|---|----------|-------------------|
| Almacenamiento | 1-2GB | **~200MB** |
| Tiempo Setup | 20-30min | **3-10min** |
| Rendimiento | Lento (proot) | **Nativo** |

```mermaid
graph TB
    A[Kernel Android] --> B[Termux Bionic]
    B --> C[glibc ld.so]
    C --> D[Node.js]
    D --> E[OpenClaw AI]
```

## App Claw Standalone

APK único (sin Termux):
- Setup con un toque.
- Dashboard integrado.

[Descargar APK](https://github.com/AidanPark/openclaw-android/releases)

## Requisitos

- Android **7.0+** (10+ recomendado)
- **1GB** libre
- Wi-Fi

## Setup Paso a Paso

<details><summary>📱 APK (Más Fácil)</summary>

1. [APK Releases](https://github.com/AidanPark/openclaw-android/releases)
2. Instala.
3. Abre app → Setup automático.

</details>

<details><summary>💻 Termux</summary>

```bash
pkg update -y && pkg install -y curl
curl -sL myopenclawhub.com/install | bash && source ~/.bashrc
openclaw onboard
openclaw gateway  # Nueva pestaña
```

</details>

![Onboard](docs/images/openclaw-onboard.png)
![Gateway](docs/images/termux_tab_1.png)

## Comandos CLI

| Comando | Acción |
|---------|--------|
| `oa --update` | Actualizar |
| `oa --status` | Estado |
| `oa --backup` | Backup |
| `openclaw gateway` | AI Live |

## 🔒 Seguridad

**✅ Seguro**: Sin root. Sandbox Android.

Permisos: INTERNET, WAKE_LOCK (estándar).

## Troubleshooting

<details><summary>¿Gateway con PID bloqueado?</summary>

```bash
rm -rf $PREFIX/tmp/openclaw-*
pkill openclaw
```

</details>

[Guía Completa](docs/troubleshooting.md)

## Idiomas

| EN | **ES** | KO | ZH |
|----|--------|----|----|
| [README.in.md](README.in.md) | README.es.md | [KO](README.ko.md) | [ZH](README.zh.md) |

<div align="center">
⭐ <b>¡Estrella el repo!</b><br>
<a href="https://github.com/AidanPark/openclaw-android/releases">Releases</a> | <a href="https://github.com/AidanPark/openclaw-android/issues">Issues</a>
</div>
