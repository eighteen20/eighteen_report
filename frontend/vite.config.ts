import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'
import { resolve } from 'path'

/**
 * Vite 构建配置
 *
 * 关键配置说明：
 * - build.outDir：将编译产物直接输出到 Spring Boot 的静态资源目录，
 *   从而在运行 JAR 时可直接访问前端页面，无需部署分离。
 * - server.proxy：开发环境将 /api 请求转发到后端服务，
 *   避免跨域问题，生产环境由 Spring Boot 直接处理。
 * - base: '/'：所有静态资源引用使用根路径，兼容 Spring Boot 静态资源服务。
 */
export default defineConfig({
  plugins: [
    vue(),
    tailwindcss(),
  ],

  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
  },

  base: '/',

  build: {
    // 输出到 Spring Boot 静态资源目录，构建后可直接随 JAR 部署
    outDir: '../eighteen_report/src/main/resources/static',
    // 清空输出目录，确保旧的 JS/CSS 文件不残留
    emptyOutDir: true,
    rollupOptions: {
      output: {
        // 按文件类型分组到子目录，并附加 hash 保证缓存失效正确
        assetFileNames: 'assets/[name]-[hash][extname]',
        chunkFileNames: 'assets/[name]-[hash].js',
        entryFileNames: 'assets/[name]-[hash].js',
      },
    },
  },

  server: {
    port: 5173,
    proxy: {
      // 开发时将所有 /api 请求代理到后端，生产环境由 Spring Boot 直接处理
      '/api': {
        target: 'http://localhost:9876',
        changeOrigin: true,
      },
    },
  },
})
