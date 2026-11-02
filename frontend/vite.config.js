import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  base: '/',
  // 某些受管环境不允许清空 outDir；设置 VITE_KEEP_OUTDIR=1 可跳过清理，直接覆盖产物
  build: { outDir: '../target/generated-resources/static', emptyOutDir: process.env.VITE_KEEP_OUTDIR !== '1' },
  server: { port: 5173, proxy: { '/api': 'http://127.0.0.1:8080' } },
  test: { environment: 'jsdom' }
})
