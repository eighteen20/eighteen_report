<script setup lang="ts">
/**
 * 根组件 App.vue
 *
 * 提供路由视图出口（<RouterView>），并挂载全局浮动消息（Messager）。
 * 页面级布局由各个视图组件（ReportList/ReportDesign/ReportPreview）自行管理。
 */
import GlobalMessager from '@/components/global/GlobalMessager.vue'
</script>

<template>
  <!-- RouterView 撑满全屏，各页面自行控制内部布局 -->
  <RouterView class="h-screen w-screen overflow-hidden" />
  <!-- 全局浮动消息：与 messager API 配合，仅挂载一次 -->
  <GlobalMessager />
</template>
