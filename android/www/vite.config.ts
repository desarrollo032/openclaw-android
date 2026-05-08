import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  base: './',

  build: {
    target:     'chrome67',          // Chrome WebView API 31+ compat
    outDir:     '../app/src/main/assets/www',  // Output directo al APK
    assetsDir:  'assets',
    sourcemap:  false,
    minify:     'esbuild',
    emptyOutDir: true,
    rollupOptions: {
      output: {
        // Chunks predecibles para WebView cache
        manualChunks: {
          react: ['react', 'react-dom'],
        },
      },
    },
  },

  // Proxy para desarrollo local (npm run dev)
  // Permite que el frontend de desarrollo hable con el gateway real
  server: {
    port: 5173,
    proxy: {
      '/health': {
        target: 'http://127.0.0.1:18789',
        changeOrigin: true,
      },
      '/api': {
        target: 'http://127.0.0.1:18789',
        changeOrigin: true,
      },
      '/terminal': {
        target: 'ws://127.0.0.1:18789',
        ws: true,
        changeOrigin: true,
      },
    },
  },
})
