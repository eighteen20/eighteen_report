<script setup lang="ts">
/**
 * 基础弹窗组件
 *
 * 通用模态框容器，支持：
 * - v-model 控制显隐（布尔值）
 * - 标题 slot 和内容 slot
 * - 底部操作按钮区 slot
 * - 点击遮罩/ESC 关闭
 * - 过渡动画
 */
import { watch } from 'vue'

const model = defineModel<boolean>()

const props = withDefaults(defineProps<{
  /** 弹窗标题 */
  title?: string
  /** 弹窗宽度（CSS 值，默认 480px） */
  width?: string
  /** 点击遮罩是否关闭 */
  maskClosable?: boolean
}>(), {
  width: '480px',
  maskClosable: true,
})

function close() {
  model.value = false
}

function onMaskClick() {
  if (props.maskClosable) close()
}

/** ESC 键关闭弹窗 */
function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') close()
}

watch(
  () => model.value,
  (visible) => {
    if (visible) {
      document.addEventListener('keydown', onKeydown)
    } else {
      document.removeEventListener('keydown', onKeydown)
    }
  },
)
</script>

<template>
  <!-- 使用 Teleport 将弹窗渲染到 body，避免被父元素 overflow:hidden 裁剪 -->
  <Teleport to="body">
    <Transition name="modal">
      <div
        v-if="model"
        class="fixed inset-0 z-50 flex items-center justify-center"
      >
        <!-- 遮罩层 -->
        <div
          class="absolute inset-0 bg-black/40"
          @click="onMaskClick"
        />
        <!-- 弹窗内容 -->
        <div
          class="relative bg-white rounded-lg shadow-xl flex flex-col overflow-hidden"
          :style="{ width: width, maxHeight: '90vh' }"
          @click.stop
        >
          <!-- 标题栏 -->
          <div class="flex items-center justify-between px-5 py-3 border-b border-gray-200 shrink-0">
            <h3 class="text-sm font-semibold text-gray-800">
              <slot name="title">{{ title }}</slot>
            </h3>
            <!-- 关闭按钮 -->
            <button
              class="w-6 h-6 flex items-center justify-center rounded text-gray-400
                     hover:text-gray-600 hover:bg-gray-100 transition-colors"
              @click="close"
            >
              ✕
            </button>
          </div>

          <!-- 内容区（可滚动） -->
          <div class="flex-1 overflow-y-auto px-5 py-4 min-h-0">
            <slot />
          </div>

          <!-- 底部按钮区（可选） -->
          <div
            v-if="$slots.footer"
            class="flex items-center justify-end gap-2 px-5 py-3 border-t border-gray-200 shrink-0"
          >
            <slot name="footer" />
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
/* 弹窗打开/关闭过渡动画 */
.modal-enter-active,
.modal-leave-active {
  transition: opacity 0.15s ease;
}

.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}

.modal-enter-active .relative,
.modal-leave-active .relative {
  transition: transform 0.15s ease;
}

.modal-enter-from .relative {
  transform: scale(0.95) translateY(-8px);
}

.modal-leave-to .relative {
  transform: scale(0.95) translateY(-8px);
}
</style>
