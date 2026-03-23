<script setup lang="ts">
/**
 * 基础下拉选择组件
 *
 * 支持 v-model 绑定、选项数组或 slot 内容。
 * 当不传入 options 时，可以直接在 slot 中放 <option> 元素。
 */
const model = defineModel<string | number>()

withDefaults(defineProps<{
  /** 选项数组（key + label 结构） */
  options?: { value: string | number; label: string }[]
  /** 是否禁用 */
  disabled?: boolean
  /** 占位选项文字 */
  placeholder?: string
}>(), {
  disabled: false,
})
</script>

<template>
  <select
    v-model="model"
    :disabled="disabled"
    class="border border-gray-300 rounded px-2 py-1 text-[13px] outline-none
           focus:border-blue-500 focus:ring-1 focus:ring-blue-500 w-full
           disabled:bg-gray-100 disabled:cursor-not-allowed bg-white transition-colors"
    v-bind="$attrs"
  >
    <!-- 占位选项（值为空时显示） -->
    <option v-if="placeholder" value="">{{ placeholder }}</option>
    <!-- 通过 options prop 渲染 -->
    <option
      v-for="opt in options"
      :key="opt.value"
      :value="opt.value"
    >
      {{ opt.label }}
    </option>
    <!-- 允许使用 slot 插入自定义 option -->
    <slot />
  </select>
</template>
