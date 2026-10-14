import { defineConfig } from 'vite';

export default defineConfig({
  base: process.env.PUBLIC_BASE_URL ? `${new URL(process.env.PUBLIC_BASE_URL).pathname.replace(/\/$/, '')}/` : '/',
  server: { proxy: { '/api': { target: 'http://127.0.0.1:3000', headers: { Origin: 'http://localhost:3000' } } } },
});
