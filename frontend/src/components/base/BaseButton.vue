<script setup lang="ts">
/**
 * 基础按钮组件
 *
 * 封装统一的按钮样式，支持多种变体（primary、danger、default）
 * 和尺寸（normal、sm），避免各页面重复定义按钮样式。
 */
withDefaults(defineProps<{
  /** 按钮变体：主色/危险/默认 */
  variant?: 'primary' | 'danger' | 'default'
  /** 按钮尺寸 */
  size?: 'sm' | 'md'
  /** 是否禁用 */
  disabled?: boolean
  /** 是否处于加载状态 */
  loading?: boolean
  /** 按钮类型（用于 form 提交） */
  type?: 'button' | 'submit' | 'reset'
}>(), {
  variant: 'default',
  size: 'md',
  disabled: false,
  loading: false,
  type: 'button',
})

const emit = defineEmits<{
  click: [event: MouseEvent]
}>()
</script>

<template>
  <button
    :type="type"
    :disabled="disabled || loading"
    class="inline-flex items-center justify-center gap-1 rounded border cursor-pointer
           font-normal transition-colors select-none"
    @click="emit('click', $event)"
    :class="[
      /* 尺寸 */
      size === 'sm'
        ? 'px-2.5 py-0.5 text-xs h-6'
        : 'px-4 py-1.5 text-[13px] h-8',
      /* 变体样式 */
      variant === 'primary'
        ? 'bg-blue-500 text-white border-blue-500 hover:bg-blue-400 hover:border-blue-400 active:bg-blue-600'
        : variant === 'danger'
          ? 'bg-red-500 text-white border-red-500 hover:bg-red-400 active:bg-red-600'
          : 'bg-white text-gray-700 border-gray-300 hover:bg-gray-50 active:bg-gray-100',
      /* 禁用态 */
      (disabled || loading) ? 'opacity-50 cursor-not-allowed' : '',
    ]"
    v-bind="$attrs"
  >
    <!-- 加载动画 -->
    <span
      v-if="loading"
      class="inline-block w-3 h-3 border-2 border-current border-t-transparent rounded-full animate-spin"
    />
    <slot />
  </button>
</template>
