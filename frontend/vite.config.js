import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  base: '/',
  build: { outDir: '../target/generated-resources/static', emptyOutDir: true },
  server: { port: 5173, proxy: { '/api': 'http://127.0.0.1:8080' } },
  test: { environment: 'jsdom' }
})
