<script setup lang="ts">
/**
 * 基础输入框组件
 *
 * 支持 v-model 双向绑定、占位符、禁用态。
 * 使用 defineModel（Vue 3.4+）简化 props/emit 写法。
 */
const model = defineModel<string | number>()

withDefaults(defineProps<{
  /** 输入框类型 */
  type?: 'text' | 'number' | 'password'
  /** 占位符 */
  placeholder?: string
  /** 是否禁用 */
  disabled?: boolean
  /** 最小值（number 类型时有效） */
  min?: number
  /** 最大值（number 类型时有效） */
  max?: number
  /** 额外 CSS 类名 */
  class?: string
}>(), {
  type: 'text',
  disabled: false,
})
</script>

<template>
  <input
    v-model="model"
    :type="type"
    :placeholder="placeholder"
    :disabled="disabled"
    :min="min"
    :max="max"
    class="border border-gray-300 rounded px-2 py-1 text-[13px] outline-none
           focus:border-blue-500 focus:ring-1 focus:ring-blue-500 w-full
           disabled:bg-gray-100 disabled:cursor-not-allowed transition-colors"
    :class="$props.class"
    v-bind="$attrs"
  />
</template>
