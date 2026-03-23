<script setup lang="ts">
/**
 * 基础颜色选择器组件
 *
 * 封装原生 <input type="color">，添加：
 * - 颜色预览条（与 Excel 工具栏风格一致）
 * - 支持 v-model 绑定颜色值（#rrggbb 格式）
 * - 点击图标文字区域触发 color picker 弹窗
 */
import { ref } from 'vue'

const model = defineModel<string>({ default: '#000000' })

const colorInputRef = ref<HTMLInputElement>()

/** 点击图标区域时，触发隐藏的 color input 弹窗 */
function triggerPicker() {
  colorInputRef.value?.click()
}
</script>

<template>
  <div
    class="relative inline-flex flex-col items-center cursor-pointer"
    @click="triggerPicker"
  >
    <!-- 图标 slot（传入按钮内容，如"A"或背景色图标） -->
    <slot />
    <!-- 颜色预览条 -->
    <div
      class="w-full h-1 rounded-full mt-0.5"
      :style="{ background: model }"
    />
    <!-- 隐藏的原生 color input，由代码触发打开 -->
    <input
      ref="colorInputRef"
      v-model="model"
      type="color"
      class="absolute opacity-0 w-0 h-0 pointer-events-none"
      @click.stop
    />
  </div>
</template>
