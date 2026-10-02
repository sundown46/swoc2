import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// Relative base so the built SPA works unmodified behind a reverse proxy on any sub-path
// (ARCHITECTURE §12, GEN-004). Runtime config (base path, feature flags) is read from
// `{base}/config.json` at startup, not baked in at build time.
export default defineConfig({
  base: './',
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/setupTests.ts'],
  },
});
