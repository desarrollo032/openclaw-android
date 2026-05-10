# Frontend React

La interfaz de usuario está construida con **React + Vite** y se comunica con Android para gestionar el gateway y mostrar el estado del sistema.

## 📂 Estructura de `www/src/`

* `/components`: Elementos reutilizables (Botones, Cards, Layouts).
* `/screens`: Páginas principales (Dashboard, Setup, Settings, Terminal).
* `/hooks`: Lógica de estado y suscripción a eventos de Android.
* `/services`: Wrappers para llamar al Bridge y realizar peticiones HTTP al Gateway local.

## 🏗️ Compilación y Carga

### Paso 1: Build
Al ejecutar `npm run build`, Vite genera una carpeta `dist/`.

### Paso 2: Assets de Android
El script de Gradle copia `dist/*` a `src/main/assets/www/`.

### Paso 3: Carga en WebView
Usamos `WebViewAssetLoader` para interceptar las peticiones y cargar los archivos desde los assets locales como si fuera un dominio real (`https://appassets.androidplatform.net/assets/www/index.html`). Esto es vital para evitar problemas de CORS y para que las rutas de React Router funcionen bien.

## 🌉 Uso del Bridge desde React

Se recomienda usar un service centralizado para interactuar con Android:

```typescript
// services/android.service.ts
export const startGateway = () => {
    if (window.OpenClaw) {
        window.OpenClaw.startGateway();
    }
}
```

## 🧪 Desarrollo Local

Puedes probar la interfaz en un navegador de escritorio sin necesidad de un dispositivo Android:

1. Ejecuta `npm run dev` en la carpeta `www`.
2. El frontend detectará que `window.OpenClaw` no existe y activará "Mock Mode".
3. Puedes simular las respuestas de Android para probar la lógica de la UI.

## 🎨 Temas y Estilos

* Se utilizan **CSS Variables** para el soporte de modo oscuro/claro.
* Los colores se sincronizan con los recursos nativos de Android (`colors.xml`) para mantener una apariencia consistente.
