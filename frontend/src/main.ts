/**
 * Vue 3 应用入口文件
 *
 * 注册插件顺序：Pinia（状态管理）→ Router（路由）→ 挂载 App
 * 全局 CSS 在此导入，确保 TailwindCSS 和自定义样式全局生效。
 */
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import router from './router'
import App from './App.vue'

// 全局样式（TailwindCSS + 自定义 CSS 变量 + AG Grid 覆盖）
import './assets/main.css'

const app = createApp(App)

// 注册 Pinia 状态管理
app.use(createPinia())

// 注册 Vue Router（History 模式）
app.use(router)

app.mount('#app')
