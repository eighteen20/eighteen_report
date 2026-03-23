<script setup lang="ts">
/**
 * 全局浮动消息容器
 *
 * 与 {@link useMessager} / {@link messager} 配合使用，在 body 层 Teleport 渲染多条短时提示。
 * 行为参考 ZUI Messager：位置、类型、自动消失、可关闭。
 */
import { computed } from 'vue'
import { messager, useMessagerItems, type MessagerItem, type MessagerPlacement, type MessagerType } from '@/composables/useMessager'

const { items } = useMessagerItems()

/** 按 placement 分组，同位置多条纵向堆叠 */
const byPlacement = computed(() => {
  const map = new Map<MessagerPlacement, MessagerItem[]>()
  for (const m of items.value) {
    const list = map.get(m.placement) ?? []
    list.push(m)
    map.set(m.placement, list)
  }
  return map
})

const placementEntries = computed(() => [...byPlacement.value.entries()])

/**
 * 外层定位容器 class（每个 placement 一块区域，内部纵向 gap）
 */
function placementWrapperClass(p: MessagerPlacement): string {
  const base = 'pointer-events-none fixed z-[10000] flex flex-col gap-2 p-2 max-w-[min(100vw-2rem,28rem)]'
  const map: Record<MessagerPlacement, string> = {
    top: `${base} top-4 left-1/2 -translate-x-1/2 items-stretch`,
    'top-left': `${base} top-4 left-4 items-start`,
    'top-right': `${base} top-4 right-4 items-end`,
    bottom: `${base} bottom-4 left-1/2 -translate-x-1/2 items-stretch`,
    'bottom-left': `${base} bottom-4 left-4 items-start`,
    'bottom-right': `${base} bottom-4 right-4 items-end`,
    center: `${base} left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 items-stretch`,
    left: `${base} left-4 top-1/2 -translate-y-1/2 items-start`,
    right: `${base} right-4 top-1/2 -translate-y-1/2 items-end`,
  }
  return map[p] || map.top
}

/**
 * 单条消息卡片样式（按 type）
 */
function typeBoxClass(t: MessagerType): string {
  const ring =
    'pointer-events-auto rounded-lg border shadow-lg px-4 py-3 text-sm leading-snug flex items-start gap-2 transition-opacity duration-200'
  const map: Record<MessagerType, string> = {
    primary: `${ring} border-blue-200 bg-blue-50 text-blue-900`,
    secondary: `${ring} border-slate-200 bg-white text-slate-800`,
    success: `${ring} border-emerald-200 bg-emerald-50 text-emerald-900`,
    danger: `${ring} border-red-200 bg-red-50 text-red-900`,
    warning: `${ring} border-amber-200 bg-amber-50 text-amber-950`,
    gray: `${ring} border-gray-200 bg-gray-50 text-gray-800`,
    important: `${ring} border-violet-200 bg-violet-50 text-violet-900`,
  }
  return map[t] || map.secondary
}

function onClose(id: number) {
  messager.remove(id)
}
</script>

<template>
  <Teleport to="body">
    <template v-for="[placement, list] in placementEntries" :key="placement">
      <div :class="placementWrapperClass(placement)" aria-live="polite">
        <div
          v-for="m in list"
          :key="m.id"
          :class="typeBoxClass(m.type)"
          role="status"
        >
          <span class="flex-1 break-words">{{ m.content }}</span>
          <button
            v-if="m.close"
            type="button"
            class="shrink-0 rounded p-0.5 text-current opacity-60 hover:opacity-100 focus:outline-none focus:ring-2 focus:ring-offset-1 focus:ring-slate-400"
            aria-label="关闭"
            @click="onClose(m.id)"
          >
            <svg class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M18 6L6 18M6 6l12 12" />
            </svg>
          </button>
        </div>
      </div>
    </template>
  </Teleport>
</template>
