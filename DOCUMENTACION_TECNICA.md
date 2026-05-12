# Documentación Técnica — OpenClaw Android

> Última revisión: 2026-05-12

## 1) Objetivo

Describir cómo OpenClaw se ejecuta de forma autónoma en Android: instalación del runtime, bridge Kotlin↔Web, servicio gateway y pipeline de build.

## 2) Componentes clave

### 2.1 Runtime nativo

- `libnode.so`: motor Node.js.
- `libbusybox.so`: utilidades shell base.
- `libldlinux.so` + glibc empaquetado: compatibilidad de binarios Linux en sandbox Android.

### 2.2 Instalación de payload

- El payload se distribuye en assets (`payload-v2.tar.xz`).
- Se extrae en almacenamiento interno privado de la app.
- Se generan wrappers (`node`, `npm`, `openclaw`) para estandarizar ejecución.

### 2.3 Bridge `window.OpenClaw`

Capa de interoperabilidad entre React y Kotlin para:

- estado de instalación,
- control del gateway,
- ejecución de comandos,
- picker de archivos,
- info del sistema,
- gestión de herramientas/plataformas.

## 3) Gateway y ciclo de vida

- El gateway se ejecuta como **Foreground Service**.
- Se monitorea salud del proceso (supervisión + reinicio automático).
- Se exponen logs con redacción de tokens sensibles.
- Se mantiene métrica de uptime para UI y soporte.

## 4) Frontend y WebView

- Frontend en React/Vite (`android/www`).
- WebView consume assets locales y eventos del bridge.
- Se usa espera activa de `health` antes de cargar dashboard para evitar errores de arranque temprano.

## 5) Seguridad

- Datos y configuración viven en almacenamiento interno privado.
- Sin dependencia obligatoria de permisos amplios de almacenamiento externo.
- Tokens efímeros y sanitización de logs.

## 6) Build y automatización

Al ejecutar Gradle:

1. Compila frontend (`npm run build` en `android/www`).
2. Sincroniza `www/dist` a assets del módulo app.
3. Copia scripts auxiliares requeridos en runtime.
4. Genera APK con UI y runtime alineados.

## 7) Operación recomendada

- Mantener sincronía entre bridge Kotlin y tipos TypeScript.
- Versionar cambios de runtime en `CHANGELOG.md`.
- Validar smoke tests de gateway tras cambios de instalación.

## 8) Referencias internas

- Visión general: `README.md`
- Guía de pruebas: `TESTING.md`
- Contribución: `CONTRIBUTING.md`
- Seguridad: `SECURITY.md`
