<script setup lang="ts">
/**
 * 右键菜单组件
 *
 * 提供两类自定义右键菜单：
 * 1. 列菜单（右键列头或整列选区）：设置列宽、左侧插入列、删除列、报表留白与渲染网格线
 * 2. 行菜单（右键行号列）：设置行高、上方插入行、删除行、报表留白与渲染网格线
 *
 * 使用自定义 HTML 替代浏览器原生右键菜单。
 * 通过 defineExpose 暴露 mountOn 方法，供父组件在 grid 容器上绑定事件。
 */
import { ref, onUnmounted } from 'vue'
import { useDesignerStore, COL_LETTERS } from '@/stores'
import type { GridApi } from 'ag-grid-community'
import { messager } from '@/composables/useMessager'

const props = defineProps<{
  /** AG Grid API，用于获取列实际宽度 */
  gridApi: GridApi | null
}>()

const emit = defineEmits<{
  /** 请求刷新 grid（行列插删后） */
  loadStateToGrid: []
}>()

const designerStore = useDesignerStore()

// ============================================================
// 菜单位置和目标状态
// ============================================================

/** 列菜单是否可见 */
const colMenuVisible = ref(false)
/** 行菜单是否可见 */
const rowMenuVisible = ref(false)

/** 菜单显示位置 */
const menuPos = ref({ x: 0, y: 0 })

/** 当前右键命中的列 ID */
const targetColId = ref('')
/** 当前右键命中的行下标 */
const targetRowIdx = ref(-1)

/** 插入列数量输入 */
const insertColCount = ref(1)
/** 插入行数量输入 */
const insertRowCount = ref(1)

// ============================================================
// 自定义输入弹窗（替代 prompt）
// ============================================================
const inputModalVisible = ref(false)
const inputModalTitle = ref('')
const inputModalLabel = ref('')
const inputModalValue = ref(0)
const inputModalMin = ref(1)
const inputModalHint = ref('')
let inputModalCallback: ((v: number) => void) | null = null

function openInputModal(title: string, label: string, defaultVal: number, minVal: number, cb: (v: number) => void) {
  inputModalTitle.value = title
  inputModalLabel.value = label
  inputModalValue.value = defaultVal
  inputModalMin.value = minVal
  inputModalHint.value = ''
  inputModalCallback = cb
  inputModalVisible.value = true
}

function confirmInputModal() {
  const v = inputModalValue.value
  if (isNaN(v) || v < inputModalMin.value) {
    inputModalHint.value = `请输入有效数值（最小 ${inputModalMin.value}px）`
    return
  }
  inputModalCallback?.(v)
  inputModalVisible.value = false
}

// ============================================================
// 菜单控制
// ============================================================

function hideAll() {
  colMenuVisible.value = false
  rowMenuVisible.value = false
}

function showMenu(type: 'col' | 'row', x: number, y: number) {
  menuPos.value = { x, y }
  if (type === 'col') { colMenuVisible.value = true; rowMenuVisible.value = false }
  else { rowMenuVisible.value = true; colMenuVisible.value = false }
}

// ============================================================
// 事件处理（绑定到 grid 容器）
// ============================================================

/**
 * 将右键菜单事件绑定到指定 DOM 元素（grid 容器）
 * 由父组件在 onMounted 后调用
 */
function mountOn(el: HTMLElement) {
  el.addEventListener('contextmenu', handleContextMenu)
  document.addEventListener('click', handleDocClick)
  document.addEventListener('contextmenu', handleDocContextMenu)
}

function unmountFrom(el: HTMLElement) {
  el.removeEventListener('contextmenu', handleContextMenu)
  document.removeEventListener('click', handleDocClick)
  document.removeEventListener('contextmenu', handleDocContextMenu)
}

function handleContextMenu(e: MouseEvent) {
  hideAll()

  const headerCell = (e.target as Element).closest('.ag-header-cell')
  if (headerCell) {
    const colId = headerCell.getAttribute('col-id')
    if (colId && colId !== '_row') {
      e.preventDefault()
      targetColId.value = colId
      showMenu('col', e.clientX, e.clientY)
      return
    }
  }

  const agCell = (e.target as Element).closest('.ag-cell')
  if (agCell) {
    const cellColId = agCell.getAttribute('col-id')
    if (cellColId === '_row') {
      e.preventDefault()
      const rowEl = agCell.closest('.ag-row')
      if (rowEl) {
        targetRowIdx.value = parseInt(rowEl.getAttribute('row-index') || '', 10)
        showMenu('row', e.clientX, e.clientY)
      }
      return
    }
    // 整列选区内也允许打开列菜单
    const b = designerStore.selBounds
    if (b && cellColId) {
      const ci = COL_LETTERS.indexOf(cellColId)
      if (
        ci >= 0 && b &&
        b.minC === b.maxC && ci === b.minC &&
        b.minR === 0 && b.maxR === designerStore.gridMeta.rowCount - 1
      ) {
        e.preventDefault()
        targetColId.value = cellColId
        showMenu('col', e.clientX, e.clientY)
        return
      }
    }
  }
}

function handleDocClick(e: MouseEvent) {
  const colMenuEl = document.getElementById('er-col-menu')
  const rowMenuEl = document.getElementById('er-row-menu')
  if (colMenuEl?.contains(e.target as Node)) return
  if (rowMenuEl?.contains(e.target as Node)) return
  hideAll()
}

function handleDocContextMenu(e: MouseEvent) {
  const gridWrapper = (e.target as Element).closest('.er-grid-wrapper')
  if (!gridWrapper) hideAll()
}

// ============================================================
// 列操作
// ============================================================

function setColWidth() {
  hideAll()
  if (!targetColId.value) return
  const col = props.gridApi?.getColumn(targetColId.value)
  const cur = col ? Math.round(col.getActualWidth()) : 100
  openInputModal('设置列宽', '列宽（像素）', cur, 30, (val) => {
    designerStore.gridMeta.colWidths[targetColId.value] = val
    props.gridApi?.setColumnWidths([{ key: targetColId.value, newWidth: val }])
  })
}

/**
 * 将菜单里的「插入数量」转为安全正整数（避免 v-model.number 为空/非法时得到 NaN，导致 Math.max(1, NaN) 仍为 NaN）
 */
function normalizeInsertCount(v: unknown): number {
  const n = typeof v === 'number' ? v : Number(v)
  if (!Number.isFinite(n) || n < 1) return 1
  return Math.floor(n)
}

function insertColLeft() {
  hideAll()
  if (!targetColId.value) return
  const ci = COL_LETTERS.indexOf(targetColId.value)
  if (ci < 0) return
  const count = normalizeInsertCount(insertColCount.value)
  designerStore.insertColsLeft(ci, count)
  emit('loadStateToGrid')
}

function deleteCol() {
  hideAll()
  if (!targetColId.value) return
  const ci = COL_LETTERS.indexOf(targetColId.value)
  if (ci < 0) return
  const hasContent = Object.keys(designerStore.cells).some((ref) => {
    const p = designerStore.parseCellRef(ref)
    if (!p || p.col !== ci) return false
    const c = designerStore.cells[ref]
    return !!(c?.value?.trim() || c?.style || c?.type)
  })
  if (designerStore.deleteCol(ci, hasContent)) emit('loadStateToGrid')
}

/**
 * 插入顶行 + 左右空白列作为页边留白（宽高来自报表设置「页边距」的上/左/右）。
 */
function insertPageMarginGutters() {
  hideAll()
  designerStore.applyEdgeMarginGutters()
  emit('loadStateToGrid')
  messager.success('已插入顶行与左右空白列，尺寸来自当前页边距（上/左/右）')
}

// ============================================================
// 行操作
// ============================================================

/**
 * 计算「设置行高」应作用于哪些行（0-based 行索引列表）。
 *
 * 当用户通过行号列拖拽/Shift 得到「整行、多行」选区（minC=0 且 maxC=最后一列，且 minR≠maxR），
 * 且在选区内某行的行号上右键时，应对该选区内所有行统一设置行高。
 * 若仅为单行选区、或选区不是整行块状（例如只选了某一列的几行），则只处理右键所在行。
 *
 * @param clickedRowIdx 右键菜单打开时命中的行号列所在行索引
 */
function getRowIndicesForRowHeightAction(clickedRowIdx: number): number[] {
  if (clickedRowIdx < 0) return []
  const b = designerStore.selBounds
  if (!b) return [clickedRowIdx]
  const lastCol = designerStore.gridMeta.colCount - 1
  const isFullWidthMultiRow =
    b.minC === 0 &&
    b.maxC === lastCol &&
    b.minR !== b.maxR
  if (
    isFullWidthMultiRow &&
    clickedRowIdx >= b.minR &&
    clickedRowIdx <= b.maxR
  ) {
    const rows: number[] = []
    for (let r = b.minR; r <= b.maxR; r++) rows.push(r)
    return rows
  }
  return [clickedRowIdx]
}

function setRowHeight() {
  hideAll()
  const rowIdx = targetRowIdx.value
  const rowIndices = getRowIndicesForRowHeightAction(rowIdx)
  if (rowIndices.length === 0) return
  const defH = designerStore.gridMeta.defaultRowHeight || 25
  const cur = designerStore.gridMeta.rowHeights[String(rowIdx)] ?? defH
  const title =
    rowIndices.length > 1
      ? `设置行高（已选 ${rowIndices.length} 行）`
      : '设置行高'
  openInputModal(title, '行高（像素）', cur, 10, (val) => {
    for (const r of rowIndices) {
      designerStore.gridMeta.rowHeights[String(r)] = val
    }
    props.gridApi?.resetRowHeights()
  })
}

function insertRowAbove() {
  hideAll()
  if (targetRowIdx.value < 0) return
  const count = normalizeInsertCount(insertRowCount.value)
  designerStore.insertRowsAbove(targetRowIdx.value, count)
  emit('loadStateToGrid')
}

function deleteRow() {
  hideAll()
  if (targetRowIdx.value < 0) return
  const ri = targetRowIdx.value
  const hasContent = Object.keys(designerStore.cells).some((ref) => {
    const p = designerStore.parseCellRef(ref)
    if (!p || p.row !== ri) return false
    const c = designerStore.cells[ref]
    return !!(c?.value?.trim() || c?.style || c?.type)
  })
  if (designerStore.deleteRow(ri, hasContent)) emit('loadStateToGrid')
}

onUnmounted(() => {
  document.removeEventListener('click', handleDocClick)
  document.removeEventListener('contextmenu', handleDocContextMenu)
})

defineExpose({ mountOn, unmountFrom })
</script>

<template>
  <!-- 列右键菜单 -->
  <Teleport to="body">
    <div
      v-if="colMenuVisible"
      id="er-col-menu"
      class="fixed z-50 bg-white border border-gray-200 rounded shadow-lg py-1 text-[12px] w-44"
      :style="{ left: menuPos.x + 'px', top: menuPos.y + 'px' }"
      @click.stop
    >
      <div class="px-3 py-1.5 text-gray-400 font-medium text-[11px] uppercase tracking-wide border-b border-gray-100 mb-1">
        列操作
      </div>
      <button class="ctx-item" @click="setColWidth">设置列宽...</button>
      <div class="flex items-center gap-1 px-3 py-1">
        <span class="text-gray-500 text-[11px] whitespace-nowrap">插入列数</span>
        <input
          v-model.number="insertColCount"
          type="number"
          min="1"
          class="border rounded px-1 w-12 text-[12px] text-center"
          @keydown.enter.prevent.stop="insertColLeft"
        />
      </div>
      <button class="ctx-item" @click="insertColLeft">在左侧插入列</button>
      <button class="ctx-item text-red-500 hover:bg-red-50" @click="deleteCol">删除列</button>

      <div class="border-t border-gray-100 mt-1 pt-1" />
      <div class="px-3 py-1 text-gray-400 text-[11px]">报表</div>
      <button class="ctx-item" @click="insertPageMarginGutters">
        插入页边留白（顶行+左右列）…
      </button>
      <label class="flex items-center gap-2 px-3 py-1.5 text-gray-700 cursor-pointer hover:bg-gray-50 text-[12px]">
        <input v-model="designerStore.gridMeta.renderShowGridLines" type="checkbox" class="rounded border-gray-300" />
        预览渲染时显示网格线
      </label>
    </div>

    <!-- 行右键菜单 -->
    <div
      v-if="rowMenuVisible"
      id="er-row-menu"
      class="fixed z-50 bg-white border border-gray-200 rounded shadow-lg py-1 text-[12px] w-44"
      :style="{ left: menuPos.x + 'px', top: menuPos.y + 'px' }"
      @click.stop
    >
      <div class="px-3 py-1.5 text-gray-400 font-medium text-[11px] uppercase tracking-wide border-b border-gray-100 mb-1">
        行操作
      </div>
      <button class="ctx-item" @click="setRowHeight">设置行高...</button>
      <div class="flex items-center gap-1 px-3 py-1">
        <span class="text-gray-500 text-[11px] whitespace-nowrap">插入行数</span>
        <input
          v-model.number="insertRowCount"
          type="number"
          min="1"
          class="border rounded px-1 w-12 text-[12px] text-center"
          @keydown.enter.prevent.stop="insertRowAbove"
        />
      </div>
      <button class="ctx-item" @click="insertRowAbove">在上方插入行</button>
      <button class="ctx-item text-red-500 hover:bg-red-50" @click="deleteRow">删除行</button>

      <div class="border-t border-gray-100 mt-1 pt-1" />
      <div class="px-3 py-1 text-gray-400 text-[11px]">报表</div>
      <button class="ctx-item" @click="insertPageMarginGutters">
        插入页边留白（顶行+左右列）…
      </button>
      <label class="flex items-center gap-2 px-3 py-1.5 text-gray-700 cursor-pointer hover:bg-gray-50 text-[12px]">
        <input v-model="designerStore.gridMeta.renderShowGridLines" type="checkbox" class="rounded border-gray-300" />
        预览渲染时显示网格线
      </label>
    </div>

    <!-- 自定义输入弹窗（替代浏览器 prompt） -->
    <div
      v-if="inputModalVisible"
      class="fixed inset-0 z-[60] flex items-center justify-center"
    >
      <div class="absolute inset-0 bg-black/30" @click="inputModalVisible = false" />
      <div class="relative bg-white rounded-lg shadow-xl p-5 w-72" @click.stop>
        <h3 class="text-sm font-semibold text-gray-800 mb-3">{{ inputModalTitle }}</h3>
        <label class="block text-[12px] text-gray-600 mb-1">{{ inputModalLabel }}</label>
        <input
          v-model.number="inputModalValue"
          type="number"
          :min="inputModalMin"
          class="w-full border rounded px-2 py-1.5 text-[13px] outline-none focus:border-blue-500"
          @keydown.enter="confirmInputModal"
          @keydown.escape="inputModalVisible = false"
        />
        <p v-if="inputModalHint" class="text-[11px] text-red-500 mt-1">{{ inputModalHint }}</p>
        <div class="flex justify-end gap-2 mt-4">
          <button
            class="px-4 py-1.5 text-[13px] border rounded text-gray-600 hover:bg-gray-50"
            @click="inputModalVisible = false"
          >
            取消
          </button>
          <button
            class="px-4 py-1.5 text-[13px] bg-blue-500 text-white rounded hover:bg-blue-400"
            @click="confirmInputModal"
          >
            确认
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
/* TailwindCSS v4 在 scoped style 中需要 @reference 才能使用 @apply */
@reference "../../assets/main.css";

.ctx-item {
  @apply block w-full text-left px-3 py-1.5 text-gray-700 hover:bg-gray-50 transition-colors cursor-pointer;
}
</style>
