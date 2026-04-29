import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  base: './',
  build: {
    target: 'chrome67',
    outDir: 'dist',
    assetsDir: 'assets',
    sourcemap: false,
    minify: 'esbuild',
    rollupOptions: {
      output: {
        // Keep a single chunk — WebView loads from file://, no HTTP/2 multiplexing benefit
        // Splitting would require multiple file:// requests with no performance gain
        manualChunks: undefined,
      },
    },
  },
})
