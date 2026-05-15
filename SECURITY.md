# Política de seguridad

---

## Índice

- [Versiones con soporte](#versiones-con-soporte)
- [Reporte de vulnerabilidades](#reporte-de-vulnerabilidades)
- [Tiempos de respuesta](#tiempos-de-respuesta)
- [Arquitectura de seguridad](#arquitectura-de-seguridad)
- [Qué protege OpenClaw](#qué-protege-openclaw)
- [Fuera de alcance](#fuera-de-alcance)

---

## Versiones con soporte

| Versión | ¿Soportada? |
| --- | --- |
| `1.0.x` | ✅ |

---

## Reporte de vulnerabilidades

> **No abras un issue público en GitHub** para reportar vulnerabilidades de seguridad.

Reporta de forma responsable a través de **[GitHub Security Advisories](https://github.com/desarrollo032/openclaw-android/security/advisories/new)**.

### Qué incluir

- Descripción clara de la vulnerabilidad.
- Pasos de reproducción.
- Evaluación de impacto.
- Sugerencia de corrección (si la tienes).

---

## Tiempos de respuesta

| Etapa | Plazo |
| --- | --- |
| **Acuse de recibo** | 48 horas |
| **Evaluación** | 1 semana |
| **Corrección** | 2 semanas (para problemas críticos) |

---

## Arquitectura de seguridad

OpenClaw en Android ejecuta binarios Linux estándar sin `proot-distro`. El modelo de seguridad se basa en este entorno particular:

```
┌─────────────────────────────────────────────┐
│ Kernel Android (SELinux aplicado)            │
│ ┌─────────────────────────────────────────┐ │
│ │ Sandbox de Termux (/data/data/...)      │ │
│ │ ┌─────────────────────────────────────┐ │ │
│ │ │ glibc-runner (ld.so en userspace)   │ │ │
│ │ │ Node.js → OpenClaw                  │ │ │
│ │ └─────────────────────────────────────┘ │ │
│ └─────────────────────────────────────────┘ │
└─────────────────────────────────────────────┘
```

### Capas de aislamiento

1. **Sandbox de la app Android** — Termux corre en su propio namespace Linux; no accede a datos de otras apps.
2. **SELinux** — el control de acceso obligatorio (MAC) de Android aplica a todos los procesos de Termux.
3. **Sin root** — todo el stack corre como usuario sin privilegios.
4. **Sin proot** — sin capa de traducción del filesystem; `glibc-runner` provee solo el linker dinámico.
5. **Conversión de rutas** — las rutas estándar Linux (`/tmp`, `/bin/sh`) se mapean a equivalentes Termux en tiempo de **instalación**, no de runtime.

---

## Qué protege OpenClaw

- **Acceso no autorizado** al sistema Android o a datos de otras apps (Android sandbox).
- **Ejecución de código** fuera del sandbox de Termux (SELinux + app sandbox).
- **Path traversal** desde Termux hacia rutas del sistema (aislamiento por prefijo Termux).

---

## Fuera de alcance

| Componente | Dónde reportar |
| --- | --- |
| Vulnerabilidades del core OpenClaw | [OpenClaw upstream](https://github.com/openclaw/openclaw) |
| Vulnerabilidades de Termux | [termux-app](https://github.com/termux/termux-app) |
| Vulnerabilidades de `glibc-runner` | [termux-pacman](https://github.com/desarrollo032/openclaw-android) |
| Seguridad a nivel del dispositivo (dispositivos rooteados, bootloaders desbloqueados) | No aplica |
