/**
 * Vue Router 4 路由配置
 *
 * 使用 HTML5 History 模式（createWebHistory），URL 更简洁美观。
 * 注意：History 模式需要后端配合，Spring Boot 中的 SpaController
 * 会将所有非 /api 路由 forward 到 index.html，交由前端路由处理。
 *
 * 路由规划：
 *   /              → 重定向到 /report
 *   /report        → 报表列表页
 *   /report/design → 新建报表设计器
 *   /report/design/:id → 编辑已有报表
 *   /report/preview/:id → 报表预览（只读）
 */
import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'

/** 路由表定义（使用懒加载减少首屏 bundle 体积） */
const routes: RouteRecordRaw[] = [
  {
    path: '/',
    // 根路径重定向到报表列表
    redirect: '/report',
  },
  {
    path: '/report',
    name: 'ReportList',
    component: () => import('@/views/ReportList.vue'),
    meta: { title: '报表列表' },
  },
  {
    path: '/report/design',
    name: 'ReportDesignNew',
    component: () => import('@/views/ReportDesign.vue'),
    meta: { title: '新建报表' },
  },
  {
    path: '/report/design/:id',
    name: 'ReportDesignEdit',
    component: () => import('@/views/ReportDesign.vue'),
    meta: { title: '编辑报表' },
  },
  {
    path: '/report/preview/:id',
    name: 'ReportPreview',
    component: () => import('@/views/ReportPreview.vue'),
    meta: { title: '报表预览' },
  },
  {
    // 兜底路由：未匹配时也跳转到报表列表，避免空白页
    path: '/:pathMatch(.*)*',
    redirect: '/report',
  },
]

const router = createRouter({
  history: createWebHistory('/'),
  routes,
})

/**
 * 全局导航守卫：根据路由 meta.title 更新页面标题
 */
router.afterEach((to) => {
  const title = (to.meta?.title as string) || '报表工具'
  document.title = `${title} - 报表工具`
})

export default router
