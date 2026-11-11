import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

const proxyTarget = process.env.NOX_SHELL_PROXY_TARGET || process.env.WALISSH_PROXY_TARGET || 'http://127.0.0.1:8091'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
  ],
  base: './',
  clearScreen: false,
  build: {
    rollupOptions: {
      output: {
        manualChunks: {
          // Monaco Editor 体积大，独立拆分
          'monaco-editor': ['@monaco-editor/react'],
          // xterm 终端相关依赖
          'xterm': [
            '@xterm/xterm',
            '@xterm/addon-fit',
            '@xterm/addon-web-links',
            '@xterm/addon-webgl',
          ],
          // React 运行时
          'react-vendor': ['react', 'react-dom'],
        },
      },
    },
  },
  server: {
    port: 5173,
    strictPort: true,
    watch: {
      ignored: ["**/src-tauri/**"],
    },
    proxy: {
      '/api': {
        target: proxyTarget,
        changeOrigin: true,
        secure: false,
        // SSE 流式请求需要长超时
        timeout: 3 * 10 * 60 * 1000, // 30 分钟
      },
    },
  },
})
