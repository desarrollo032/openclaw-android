# Documentación técnica — OpenClaw Android

> Última revisión: 2026-05-15

Referencia técnica del runtime, bridge, gateway y pipeline de build de la app.

---

## Índice

- [Objetivo](#objetivo)
- [Componentes clave](#componentes-clave)
- [Gateway y ciclo de vida](#gateway-y-ciclo-de-vida)
- [Frontend y WebView](#frontend-y-webview)
- [Seguridad](#seguridad)
- [Build y automatización](#build-y-automatización)
- [Prácticas operativas recomendadas](#prácticas-operativas-recomendadas)
- [Referencias internas](#referencias-internas)

---

## Objetivo

Describir cómo **OpenClaw** se ejecuta de forma autónoma en Android:

- Instalación del runtime Node.js + glibc.
- Bridge **Kotlin ↔ WebView** (`window.OpenClaw`).
- Servicio gateway en foreground.
- Pipeline de build (Gradle + Vite).

---

## Componentes clave

### Runtime nativo

| Librería | Función |
| --- | --- |
| `libnode.so` | Motor Node.js. |
| `libbusybox.so` | Utilidades shell base. |
| `libldlinux.so` + glibc empaquetado | Compatibilidad de binarios Linux dentro del sandbox de Android. |

### Instalación del payload

- El payload se distribuye como asset (`payload-v2.tar.xz`).
- Se extrae en almacenamiento **privado interno** de la app.
- Se generan wrappers (`node`, `npm`, `openclaw`) para estandarizar ejecución.
- Soporta **override local**: el usuario puede sustituir el payload o la migración con archivos seleccionados desde el dispositivo.

### Bridge `window.OpenClaw`

Capa de interoperabilidad entre **React** y **Kotlin**. Expone:

- Estado de instalación y onboarding.
- Control del gateway.
- Ejecución de comandos (síncronos y asíncronos).
- Picker de archivos para reemplazo de payload/migración.
- Información del sistema y de la app.
- Gestión de herramientas y plataformas.

Detalle completo en [android/docs/BRIDGE.md](android/docs/BRIDGE.md).

---

## Gateway y ciclo de vida

- El gateway corre como **Foreground Service** en `127.0.0.1:18789`.
- Supervisión del proceso con **reinicio automático** ante caídas.
- **Health-check** cada 10 s contra `/health`.
- **Redacción de tokens** sensibles en los logs.
- **Uptime tracking** expuesto a la UI y a soporte.

Más detalles en [android/docs/GATEWAY.md](android/docs/GATEWAY.md).

---

## Frontend y WebView

- Frontend en **React 19 + Vite 7 + Tailwind 4** en `android/www/`.
- El WebView usa `WebViewAssetLoader` para servir los assets locales bajo un dominio virtual y evitar problemas de CORS.
- La UI espera el `/health` antes de cargar el dashboard para evitar carreras de arranque.

---

## Seguridad

- Datos y configuración viven en **almacenamiento interno privado**.
- Sin dependencia obligatoria de permisos amplios de almacenamiento externo.
- **Tokens efímeros** (UUID + timestamp) y **sanitización de logs**.
- El token de dashboard **nunca** se persiste a disco.

---

## Build y automatización

Al ejecutar `./gradlew assembleDebug` (o `assembleRelease`):

1. Compila el frontend (`npm run build` en `android/www`).
2. Sincroniza `www/dist` a `android/app/src/main/assets/www`.
3. Copia los scripts de mantenimiento (`oa.sh`, `bootstrap.sh`, etc.) a `assets/scripts`.
4. Genera el APK con UI y runtime alineados.

> El build de Gradle ejecuta automáticamente `buildWebUI` + `copyWebUIAssets` antes del merge de assets.

---

## Prácticas operativas recomendadas

- Mantener **sincronía** entre el bridge Kotlin (`AndroidBridge.kt`) y los tipos TypeScript (`android/www/src/lib/bridge.ts`, `types/global.d.ts`).
- Registrar cambios de runtime en [`CHANGELOG.md`](CHANGELOG.md).
- Ejecutar **smoke tests** del gateway tras cambios de instalación.
- Respetar las [reglas críticas de desarrollo](android/docs/REGLAS_CRITICAS.md).

---

## Referencias internas

- Visión general: [`README.md`](README.md)
- Guía de pruebas: [`TESTING.md`](TESTING.md)
- Contribución: [`CONTRIBUTING.md`](CONTRIBUTING.md)
- Seguridad: [`SECURITY.md`](SECURITY.md)
- Documentación del módulo Android: [`android/docs/`](android/docs/)
