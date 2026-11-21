/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    // In development the API runs on :8080; proxying keeps everything same-origin, like production behind one domain.
    proxy: {
      '/api': 'http://localhost:8080',
      '/ws': { target: 'ws://localhost:8080', ws: true },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    // The engine test starts the real Stockfish build in Node and needs more time than a unit test.
    testTimeout: 30_000,
    coverage: {
      provider: 'v8',
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/main.tsx', 'src/test/**', 'src/**/*.test.{ts,tsx}', 'src/vite-env.d.ts', 'src/lib/types.ts'],
      reporter: ['text-summary', 'text', 'html'],
      // CI fails if coverage drops below these. Raise them as coverage grows; never lower them silently.
      thresholds: { lines: 93, statements: 90, functions: 85, branches: 85 },
    },
  },
});
