/// <reference types="vitest/config" />
import { defineConfig, type Plugin } from 'vite';
import react from '@vitejs/plugin-react';
import fs from 'node:fs';
import path from 'node:path';

const GATEWAY = process.env.GATEWAY_URL ?? 'http://localhost:8080';
const IDENTITY = process.env.IDENTITY_URL ?? 'http://localhost:8081';
const STORAGE = process.env.STORAGE_URL ?? 'http://localhost:9000';
const AUTH_DIRECT = process.env.PARKSMART_AUTH_DIRECT === '1';

/**
 * LOCAL DEV ONLY. Serves GET /__dev/codes: the one-time codes that Notification "sends" by logging
 * (logs/notification.log, "DEV ONLY body"). Only registered on the dev server (never in a build),
 * only reads that one log file, and the dev server listens on localhost.
 */
function devCodes(): Plugin {
  const logFile = path.resolve(__dirname, '../logs/notification.log');
  const re =
    /^(\S+)\s.*?\b(EMAIL|SMS) to (\S+?)(?::|\s)\s*(?:.*?\|\s*)?DEV ONLY body: Use (\S+) to (verify your email|verify your phone|reset your password)\./;
  return {
    name: 'parksmart-dev-codes',
    apply: 'serve',
    configureServer(server) {
      server.middlewares.use('/__dev/codes', (_req, res) => {
        res.setHeader('Content-Type', 'application/json');
        res.setHeader('Cache-Control', 'no-store');
        try {
          const stat = fs.statSync(logFile);
          const size = Math.min(stat.size, 4 * 1024 * 1024);
          const fd = fs.openSync(logFile, 'r');
          const buf = Buffer.alloc(size);
          fs.readSync(fd, buf, 0, size, stat.size - size);
          fs.closeSync(fd);
          const items = buf
            .toString('utf8')
            .split(/\r?\n/)
            .map((line) => re.exec(line))
            .filter((m): m is RegExpExecArray => m !== null)
            .map((m) => ({
              at: m[1],
              channel: m[2],
              to: m[3],
              purpose: m[5].replace('verify your ', '').replace('reset your password', 'password reset'),
              code: m[4],
            }))
            .reverse()
            .slice(0, 30);
          res.end(JSON.stringify({ available: true, file: 'logs/notification.log', items }));
        } catch (e) {
          res.end(JSON.stringify({ available: false, file: 'logs/notification.log', items: [], error: String(e) }));
        }
      });
    },
  };
}

export default defineConfig({
  plugins: [react(), devCodes()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      // Auth endpoints straight to Identity (skips the gateway's per-IP login/register limits).
      ...(AUTH_DIRECT
        ? {
            '/api/v1/auth': { target: IDENTITY, changeOrigin: true },
          }
        : {}),
      '/api': { target: GATEWAY, changeOrigin: true },
      '/admin': { target: GATEWAY, changeOrigin: true },
      '/.well-known': { target: GATEWAY, changeOrigin: true },
      // Presigned upload URLs point at object storage (localhost:9000), which has no CORS for
      // the dev origin. The client rewrites them to /_storage/..., changeOrigin keeps the signed Host.
      '/_storage': {
        target: STORAGE,
        changeOrigin: true,
        rewrite: (p) => p.replace(/^\/_storage/, ''),
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
});
