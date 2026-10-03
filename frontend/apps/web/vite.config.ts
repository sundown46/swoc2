import { fileURLToPath } from 'node:url';

import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// Relative base so the built SPA works unmodified behind a reverse proxy on any sub-path
// (ARCHITECTURE §12, GEN-004). Runtime config (base path, feature flags) is read from
// `{base}/config.json` at startup, not baked in at build time.
export default defineConfig({
  base: './',
  plugins: [react()],
  build: {
    rollupOptions: {
      // Two entries: the app, and the standalone /diag page (GEN-010) that must load without
      // the app, its login or its state.
      input: {
        main: fileURLToPath(new URL('./index.html', import.meta.url)),
        diag: fileURLToPath(new URL('./diag.html', import.meta.url)),
      },
    },
  },
  server: {
    // `pnpm dev`: forward API, realtime and runtime config to a locally running backend.
    proxy: {
      '/api': { target: 'http://localhost:5080', ws: true },
      '/config.json': 'http://localhost:5080',
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/setupTests.ts'],
  },
});
