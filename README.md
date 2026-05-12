# OpenClaw Android

OpenClaw Android ejecuta OpenClaw de forma **nativa dentro de Android**, sin depender de Termux o Proot para el flujo principal.

![OpenClaw en Android](docs/images/openclaw_android.jpg)

## Resumen rápido

- Android **7.0+**.
- Runtime Node.js + glibc empaquetado en assets.
- UI moderna en React/Vite dentro de WebView.
- Servicio foreground para el gateway (estable en segundo plano).
- Terminal integrada basada en librerías de Termux.

## Arquitectura (alto nivel)

1. **Instalación inicial**: la app extrae `payload-v2.tar.xz` en almacenamiento interno.
2. **Bridge nativo**: Kotlin expone `window.OpenClaw` para que React invoque capacidades Android.
3. **Gateway**: un proceso Node.js corre como servicio foreground.
4. **Dashboard**: WebView carga la app web local y controla estado/acciones.

## Estructura principal

- `android/app/`: app Android (Kotlin, servicios, bridge, instalador).
- `android/www/`: frontend React/Vite.
- `docs/`: guías operativas, troubleshooting y planes.
- `scripts/`: utilidades de instalación y mantenimiento.

## Desarrollo

### Requisitos

- Android Studio (recomendado).
- JDK 21.
- Node.js (solo para construir `android/www`).

### Build local

```bash
cd android
./gradlew assembleDebug
```

El build ejecuta automáticamente la compilación web y sincroniza assets hacia el APK.

## Documentación

### Núcleo técnico
- Español: [DOCUMENTACION_TECNICA.md](DOCUMENTACION_TECNICA.md)
- English: [DOCUMENTATION_TECHNICAL.md](DOCUMENTATION_TECHNICAL.md)

### Guías del proyecto
- Instalación: [INSTALLATION_GUIDE.md](INSTALLATION_GUIDE.md)
- Pruebas: [TESTING.md](TESTING.md)
- Contribución: [CONTRIBUTING.md](CONTRIBUTING.md)
- Seguridad: [SECURITY.md](SECURITY.md)
- Historial de cambios: [CHANGELOG.md](CHANGELOG.md)

## Estado del proyecto

Este repositorio prioriza estabilidad del runtime Android, observabilidad del gateway y mantenibilidad del bridge nativo.

---

Si quieres, en un siguiente paso puedo unificar **tono, formato y metadatos** del resto de archivos Markdown (`docs/*.md`, `android/docs/*.md`, traducciones `*.ko.md` y `*.zh.md`) para dejar toda la documentación con el mismo diseño moderno.
