# Frontend React

La UI se construye con **React 19 + Vite 7 + TypeScript + Tailwind 4** y se comunica con Android mediante el bridge `window.OpenClaw`.

---

## Índice

- [Estructura de `www/src/`](#estructura-de-wwwsrc)
- [Compilación y carga](#compilación-y-carga)
- [Uso del bridge](#uso-del-bridge)
- [Desarrollo local (sin Android)](#desarrollo-local-sin-android)
- [Temas y estilos](#temas-y-estilos)

---

## Estructura de `www/src/`

| Carpeta | Contenido |
| --- | --- |
| `components/` | Elementos reutilizables (botones, cards, layouts). |
| `screens/` | Páginas principales (`Setup`, `Dashboard`, `Chat`, `Memory`, `Settings`, `Skills`, `Logs`). |
| `hooks/` | Lógica de estado y suscripción a eventos de Android. |
| `lib/` | Wrappers tipados del bridge, router y API HTTP. |
| `i18n/` | Cadenas y selector de locale. |
| `utils/` | Utilidades varias. |
| `test/` | Tests de Vitest. |

---

## Compilación y carga

1. **Build:** `npm run build` (o `pnpm build`) genera `dist/`.
2. **Sincronización:** la task Gradle `copyWebUIAssets` copia `dist/` a `android/app/src/main/assets/www/`.
3. **Carga en WebView:** se usa **`WebViewAssetLoader`** para interceptar peticiones y servir los archivos desde los assets locales como si fueran un dominio real (`https://appassets.androidplatform.net/assets/www/index.html`). Esto evita problemas de CORS y permite que React Router funcione.

---

## Uso del bridge

Se recomienda concentrar el acceso al bridge en `lib/bridge.ts`:

```typescript
import { bridge } from '../lib/bridge'

// Llamada simple
bridge.call('startGateway')

// Llamada que devuelve JSON
const info = bridge.callJson<{ versionName: string }>('getAppInfo')

// Escuchar eventos nativos
const h = bridge.on('onInstallProgress', (p) => {
  console.log('progreso', p)
})
// ... más tarde
bridge.off('onInstallProgress', h)
```

El bridge está sincronizado al 100% con los métodos expuestos por `AndroidBridge.kt`. Ver [BRIDGE.md](./BRIDGE.md) para la lista completa.

---

## Desarrollo local (sin Android)

Puedes probar la UI en un navegador de escritorio:

1. `npm run dev` en `android/www/`.
2. El frontend detecta que `window.OpenClaw` no existe y activa **Mock Mode**.
3. Las respuestas del bridge se simulan para iterar sobre la UI sin necesidad de un dispositivo.

---

## Temas y estilos

- Tailwind 4 con **CSS variables** para soporte de modo oscuro/claro.
- El selector de tema se sincroniza con los recursos nativos de Android (`colors.xml`) para mantener consistencia visual.
- El tema inicial se intenta leer del sistema vía `bridge.call('getSystemTheme')` con fallback a `prefers-color-scheme`.
