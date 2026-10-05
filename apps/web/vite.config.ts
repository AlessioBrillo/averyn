import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  // `npm run dev`: same-origin /v1 like the Caddy container in Compose (AVERYN_BACKEND to point elsewhere).
  server: { proxy: { '/v1': process.env.AVERYN_BACKEND ?? 'http://localhost:8080' } },
})
