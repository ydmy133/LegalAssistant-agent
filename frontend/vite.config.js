import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 本地开发默认 localhost；Docker 开发 compose 通过环境变量指向 backend 服务
const apiProxy = process.env.VITE_API_PROXY || 'http://localhost:8080'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 3000,
    // Cloudflare Quick Tunnel 每次域名不同，需放行 *.trycloudflare.com
    allowedHosts: ['.trycloudflare.com', 'localhost', '127.0.0.1'],
    proxy: {
      '/api': {
        target: apiProxy,
        changeOrigin: true,
      },
    },
  },
})
