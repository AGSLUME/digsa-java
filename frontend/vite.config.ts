/// <reference types="vitest" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// En desarrollo la API va al backend local (mismo origen gracias al proxy); en producción la SPA se sirve desde el backend.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: { '/api': 'http://127.0.0.1:8080' },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    chunkSizeWarningLimit: 900,
  },
  test: {
    environment: 'jsdom',
    globals: false,
    // Los tokens de lume.css se leen en la prueba del tema (comprueba que el tema de Mantine repite los mismos valores)
    css: { include: [/lume\.css/] },
  },
});
