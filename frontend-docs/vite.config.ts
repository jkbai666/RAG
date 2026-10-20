import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  return {
    plugins: [vue()],
    server: {
      host: '127.0.0.1',
      port: 5174,
      strictPort: true,
      proxy: {
        '/api': {
          target: process.env.VITE_API_TARGET || env.VITE_API_TARGET || 'http://127.0.0.1:8088',
          changeOrigin: true,
        },
      },
    },
    preview: { host: '127.0.0.1', port: 5174, strictPort: true },
  }
})