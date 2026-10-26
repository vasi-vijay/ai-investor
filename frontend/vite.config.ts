import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Dev server proxies the API and websocket so the browser only ever talks to one origin
// (session cookie + CSRF cookie are same-origin; no tokens are stored in browser storage).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: false },
      '/ws': { target: 'ws://localhost:8080', ws: true },
    },
  },
});
