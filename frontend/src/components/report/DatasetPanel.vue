<script setup lang="ts">
/**
 * 左侧数据集面板组件
 *
 * 展示当前报表的所有数据集，支持：
 * - 展示数据集 key、类型和字段列表
 * - 字段拖拽（拖到 AG Grid 单元格，生成 ${dsKey.field} 变量表达式）
 * - 添加/编辑/删除数据集（通过 DatasetModal）
 */
import { ref } from 'vue'
import { useDesignerStore } from '@/stores'
import { BaseButton } from '@/components/base'
import DatasetModal from './DatasetModal.vue'
import type { DatasetDefinition } from '@/types'

const designerStore = useDesignerStore()

/** 弹窗显隐 */
const showModal = ref(false)
/** 当前编辑的数据集（null 表示新增） */
const editingDs = ref<DatasetDefinition | null>(null)

/** 打开新增弹窗 */
function openAdd() {
  editingDs.value = null
  showModal.value = true
}

/** 打开编辑弹窗 */
function openEdit(ds: DatasetDefinition) {
  editingDs.value = ds
  showModal.value = true
}

/** 删除数据集 */
function removeDs(key: string) {
  const idx = designerStore.datasets.findIndex((d: DatasetDefinition) => d.key === key)
  if (idx >= 0) designerStore.datasets.splice(idx, 1)
}
</script>

<template>
  <aside class="flex flex-col w-[220px] shrink-0 border-r border-gray-200 bg-white h-full">
    <!-- 面板标题 -->
    <div class="flex items-center justify-between px-3 py-2 border-b border-gray-100 shrink-0">
      <span class="text-[12px] font-semibold text-gray-600 uppercase tracking-wide">数据集</span>
      <BaseButton size="sm" variant="primary" @click="openAdd">+ 添加</BaseButton>
    </div>

    <!-- 数据集列表（可滚动） -->
    <div class="flex-1 overflow-y-auto p-2 space-y-2">
      <!-- 空状态提示 -->
      <div
        v-if="!designerStore.datasets.length"
        class="text-[12px] text-gray-400 text-center pt-6"
      >
        暂无数据集<br />点击「添加」创建
      </div>

      <!-- 数据集卡片 -->
      <div
        v-for="ds in designerStore.datasets"
        :key="ds.key"
        class="border border-gray-200 rounded overflow-hidden"
      >
        <!-- 卡片头部：key + 类型 + 操作按钮 -->
        <div class="flex items-center gap-1 px-2 py-1.5 bg-gray-50">
          <span class="font-mono text-[12px] text-blue-600 font-semibold truncate flex-1">
            {{ ds.key }}
          </span>
          <span class="text-[10px] text-gray-400 bg-gray-200 rounded px-1">{{ ds.type }}</span>
          <button
            class="text-[11px] text-gray-500 hover:text-blue-500 transition-colors px-1"
            @click="openEdit(ds)"
          >
            编辑
          </button>
          <button
            class="text-[11px] text-gray-500 hover:text-red-500 transition-colors px-1"
            @click="removeDs(ds.key)"
          >
            删除
          </button>
        </div>

        <!-- 字段列表（可拖拽到表格） -->
        <ul class="py-1">
          <li
            v-for="field in ds.fields"
            :key="field"
            class="flex items-center gap-1 px-2 py-0.5 text-[12px] text-gray-700
                   hover:bg-blue-50 hover:text-blue-600 cursor-grab transition-colors
                   active:cursor-grabbing"
            draggable="true"
            :data-var="`\${${ds.key}.${field}}`"
            @dragstart="(e) => {
              e.dataTransfer?.setData('text/plain', `\${${ds.key}.${field}}`)
              if (e.dataTransfer) e.dataTransfer.effectAllowed = 'copy'
            }"
          >
            <!-- 拖拽指示图标 -->
            <span class="text-gray-300 text-[10px]">⋮⋮</span>
            {{ field }}
          </li>
          <li
            v-if="!ds.fields.length"
            class="px-2 py-1 text-[11px] text-gray-400 italic"
          >
            （无字段，请先测试连接）
          </li>
        </ul>
      </div>
    </div>

    <!-- 数据集配置弹窗 -->
    <DatasetModal v-model="showModal" :editing-dataset="editingDs" />
  </aside>
</template>
