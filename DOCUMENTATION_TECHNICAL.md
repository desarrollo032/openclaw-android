# Documentación técnica (versión espejo) — OpenClaw Android

> Última revisión: 2026-05-15

Este documento existe como **versión espejo** de [`DOCUMENTACION_TECNICA.md`](DOCUMENTACION_TECNICA.md) por razones históricas (antes contenía la versión en inglés). Todo el contenido del proyecto está ahora **unificado en español** en el archivo principal.

---

## Índice

- [Aviso](#aviso)
- [Referencia principal](#referencia-principal)
- [Resumen](#resumen)

---

## Aviso

A partir de la revisión `2026-05-15`, la documentación técnica vive en un único archivo en español. Consulta **[DOCUMENTACION_TECNICA.md](DOCUMENTACION_TECNICA.md)** para la versión completa y actualizada.

---

## Referencia principal

→ [**DOCUMENTACION_TECNICA.md**](DOCUMENTACION_TECNICA.md)

---

## Resumen

Cubre los siguientes temas:

- Runtime nativo (Node.js + glibc).
- Instalación del payload y override local.
- Bridge `window.OpenClaw` (Kotlin ↔ React).
- Ciclo de vida del gateway (Foreground Service, health-check, uptime).
- Frontend React + Vite y carga vía `WebViewAssetLoader`.
- Modelo de seguridad y manejo de tokens.
- Pipeline de build (Gradle + Vite + sync de assets).
