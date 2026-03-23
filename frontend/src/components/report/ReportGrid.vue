<script setup lang="ts">
/**
 * 报表设计器 AG Grid 核心组件
 *
 * 封装 AG Grid 的初始化与状态管理，实现 Excel 风格的表格编辑器：
 * - 动态列定义（含合并跨度、样式回调、选区高亮）
 * - 行号列（_row 字段，点击可整行选中）
 * - 拖拽扩展选区（按住左键拖动）
 * - Ctrl+Z 撤销行列插删操作
 * - 列宽调整持久化
 * - 从 store 加载/同步数据
 *
 * 通过 expose() 对外暴露 gridApi 和核心方法，供父组件调用。
 */
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { AgGridVue } from 'ag-grid-vue3'
import type {
  GridApi,
  ColDef,
  GridOptions,
  CellClassParams,
  ColSpanParams,
  RowSpanParams,
  RowClassParams,
  RowHeightParams,
  EditableCallbackParams,
  CellClickedEvent,
} from 'ag-grid-community'
import { AllCommunityModule, ModuleRegistry } from 'ag-grid-community'
import { useDesignerStore, COL_LETTERS } from '@/stores'

// 注册 AG Grid 社区版所有模块
ModuleRegistry.registerModules([AllCommunityModule])

const props = withDefaults(defineProps<{
  /** 是否为只读预览模式（预览页使用） */
  readonly?: boolean
}>(), {
  readonly: false,
})

const emit = defineEmits<{
  /** 选区变化时触发，通知父组件更新工具栏和设置面板 */
  selectionChange: []
  /**
   * Grid 就绪时向父组件抛出 API（供右键菜单、设置面板等使用）
   * 若不在此转发，父级 gridApi 会一直为 null，导致 resetRowHeights 等调用失效
   */
  gridReady: [api: GridApi]
}>()

const designerStore = useDesignerStore()

/** AG Grid API 实例 */
const gridApi = ref<GridApi | null>(null)
/** 设计器内大图预览弹窗显隐 */
const imagePreviewVisible = ref(false)
/** 设计器内大图预览地址 */
const imagePreviewUrl = ref('')

/**
 * 打开设计器图片大图预览。
 *
 * @param url 图片地址
 */
function openImagePreview(url: string) {
  imagePreviewUrl.value = url
  imagePreviewVisible.value = true
}

/**
 * 关闭设计器图片大图预览并清空缓存地址。
 */
function closeImagePreview() {
  imagePreviewVisible.value = false
  imagePreviewUrl.value = ''
}

/** 是否正在拖拽框选（数据区） */
const dragSelecting = ref(false)

/** 是否正在行号列拖拽多选整行 */
const dragRowSelecting = ref(false)
/** 行号拖拽起始行索引 */
const rowDragAnchor = ref<number | null>(null)
/** 行号拖拽过程中是否发生过移动（用于区分点击与拖拽，避免拖拽结束后 click 覆盖选区） */
const rowDragMoved = ref(false)
/** 忽略下一次行号单元格的 cell-clicked（拖拽刚结束） */
const suppressNextRowCellClick = ref(false)

/** 网格容器 DOM 引用（备用，当前由父组件管理挂载） */
// const gridWrapperRef = ref<HTMLDivElement>()

// ============================================================
// 构建列定义
// ============================================================

/** 从 store 构建 AG Grid 的 columnDefs */
const columnDefs = computed<ColDef[]>(() => {
  const defs: ColDef[] = []

  // 行号列（固定在左侧，不可编辑）
  defs.push({
    headerName: '',
    field: '_row',
    width: 50,
    minWidth: 50,
    editable: false,
    pinned: 'left',
    resizable: false,
    suppressMovable: true,
    cellStyle: {
      background: '#fafafa',
      color: '#9ca3af',
      textAlign: 'center',
      fontWeight: '600',
      fontSize: '12px',
    },
    cellClassRules: {
      'cell-frozen-row': (params: CellClassParams) =>
        (params.node.rowIndex ?? 0) < (designerStore.gridMeta.freezeHeaderRows || 0),
      // 顶部页边留白行：给行号列也加斜线样式
      'er-design-edge-gutter': (params: CellClassParams) =>
        designerStore.gridMeta.edgeMarginTopRow === true && (params.node.rowIndex ?? 0) === 0,
    },
  })

  // 业务数据列（A/B/C...）
  for (let colIdx = 0; colIdx < COL_LETTERS.length; colIdx++) {
    const letter = COL_LETTERS[colIdx]
    const isVisible = colIdx < designerStore.gridMeta.colCount

    defs.push({
      headerName: letter,
      field: letter,
      hide: !isVisible,
      editable: props.readonly
        ? false
        : (params: EditableCallbackParams) =>
              // 顶部/左右页边留白区：禁止写入内容
              ((designerStore.gridMeta.edgeMarginTopRow === true && (params.node.rowIndex ?? 0) === 0) ||
               (designerStore.gridMeta.edgeMarginLeftCol === true && colIdx === 0) ||
               (designerStore.gridMeta.edgeMarginRightCol === true &&
                 colIdx === (designerStore.gridMeta.colCount - 1))) ? false
                : !designerStore.isCoveredByMerge(params.node.rowIndex ?? 0, colIdx),
      minWidth: 60,
      sortable: !!designerStore.gridMeta.columnSortable,
      width:
        designerStore.gridMeta.colWidths[letter] ||
        designerStore.gridMeta.defaultColWidth ||
        100,
      colSpan: (params: ColSpanParams) => {
        const m = designerStore.findMergeAt(params.node?.rowIndex ?? 0, colIdx)
        return m ? m.colSpan : 1
      },
      rowSpan: (params: RowSpanParams) => {
        const m = designerStore.findMergeAt(params.node?.rowIndex ?? 0, colIdx)
        return m ? m.rowSpan : 1
      },
      cellStyle: (params: CellClassParams) => {
        const cellRef = letter + ((params.node.rowIndex ?? 0) + 1)
        const c = designerStore.cells[cellRef]
        // 关键：AG Grid 对 cellStyle 的“缺失字段”不会自动清理已设置过的 inline style。
        // 因此这里需要显式返回 border* 字段（为空字符串时表示清除），避免「点无边框仍显示」。
        const s = c?.style
        if (!s) {
          return {
            borderTop: '',
            borderRight: '',
            borderBottom: '',
            borderLeft: '',
          } as any
        }
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        return ({
          ...s,
          borderTop: s.borderTop || '',
          borderRight: s.borderRight || '',
          borderBottom: s.borderBottom || '',
          borderLeft: s.borderLeft || '',
        } as any)
      },
      cellClassRules: {
        'ag-cell-var': (params: CellClassParams) =>
          typeof params.value === 'string' && (params.value as string).includes('${'),
        'cell-selected': (params: CellClassParams) =>
          designerStore.isCellSelected(params.node.rowIndex ?? 0, colIdx),
        'cell-frozen-row': (params: CellClassParams) =>
          (params.node.rowIndex ?? 0) < (designerStore.gridMeta.freezeHeaderRows || 0),
        // 顶部/左右页边留白区：视觉斜线 + 禁止写入已由 editable 控制
        'er-design-edge-gutter': (params: CellClassParams) =>
          (designerStore.gridMeta.edgeMarginTopRow === true && (params.node.rowIndex ?? 0) === 0) ||
          (designerStore.gridMeta.edgeMarginLeftCol === true && colIdx === 0) ||
          (designerStore.gridMeta.edgeMarginRightCol === true &&
            colIdx === (designerStore.gridMeta.colCount - 1)),
      },
      // 当单元格类型为 image 时渲染 <img>，否则显示文本（不定义 cellRenderer 时 AG Grid 默认文本渲染）
      cellRenderer: (params: CellClassParams) => {
        const cellRef = letter + ((params.node.rowIndex ?? 0) + 1)
        const cell = designerStore.cells[cellRef]
        if (cell?.type === 'image' && cell.value) {
          // 构造图片容器：居中显示，失败时显示占位文本
          const wrapper = document.createElement('div')
          wrapper.style.cssText = 'display:flex;align-items:center;justify-content:center;width:100%;height:100%;overflow:hidden;'
          const img = document.createElement('img')
          img.src = cell.value
          img.style.cssText = 'max-width:100%;max-height:100%;object-fit:contain;'
          img.alt = '图片'
          // 双击图片：在当前页面弹出大图预览，不跳新窗口
          img.ondblclick = (evt: MouseEvent) => {
            evt.preventDefault()
            evt.stopPropagation()
            openImagePreview(cell.value as string)
          }
          img.onerror = () => {
            wrapper.textContent = '[图片加载失败]'
          }
          wrapper.appendChild(img)
          return wrapper
        }
        // 非图片：以文本渲染（直接返回字符串，AG Grid 作为 textContent 处理）
        return params.value ?? ''
      },
    })
  }

  return defs
})

/** 构建完整行数据（每行所有列的值） */
function buildRowData(): Record<string, unknown>[] {
  const rows: Record<string, unknown>[] = []
  for (let r = 0; r < designerStore.gridMeta.rowCount; r++) {
    const row: Record<string, unknown> = { _row: r + 1 }
    COL_LETTERS.forEach((letter: string) => {
      const cellRef = letter + (r + 1)
      row[letter] = designerStore.cells[cellRef]?.value || ''
    })
    rows.push(row)
  }
  return rows
}

/** 行数据：由 store 的 rowCount/cells 推导，保证与 store 同步，避免 :rowData="[]" 覆盖 */
const rowData = computed(() => buildRowData())

// ============================================================
// AG Grid 事件处理
// ============================================================

function onCellValueChanged(e: { node: { rowIndex: number }; colDef: { field: string }; newValue: string }) {
  designerStore.syncCellValue(e.node.rowIndex, e.colDef.field, e.newValue)
}

function onCellClicked(e: CellClickedEvent) {
  if (!e.colDef) return

  const rowIndex = e.rowIndex ?? e.node?.rowIndex
  if (rowIndex == null || rowIndex < 0) return

  const mouse = e.event && 'shiftKey' in e.event ? (e.event as MouseEvent) : null
  const shiftKey = mouse?.shiftKey ?? false

  if (e.colDef.field === '_row') {
    if (suppressNextRowCellClick.value) {
      suppressNextRowCellClick.value = false
      return
    }
    // 点击行号：整行选中；Shift+点击另一行号：与锚点行之间全部整行选中（与 Excel 行为一致）
    designerStore.selectRow(rowIndex, shiftKey)
    gridApi.value?.stopEditing(true)
    gridApi.value?.refreshCells({ force: true })
    emit('selectionChange')
    return
  }

  const ci = COL_LETTERS.indexOf(e.colDef.field as string)
  if (ci < 0) return

  if (shiftKey && designerStore.sel.r1 >= 0) {
    // Shift+点击：扩展选区
    designerStore.extendSelection(rowIndex, ci)
    gridApi.value?.stopEditing(true)
  } else {
    // 普通点击：单格选中
    designerStore.setSelection(rowIndex, ci, rowIndex, ci)
  }

  gridApi.value?.refreshCells({ force: true })
  emit('selectionChange')
}

function onColumnHeaderClicked(e: { column: { getColId: () => string }; event?: { shiftKey?: boolean } }) {
  const colId = e.column.getColId()
  if (colId === '_row') return
  const ci = COL_LETTERS.indexOf(colId)
  if (ci < 0) return
  designerStore.selectColumn(ci, e.event?.shiftKey ?? false)
  gridApi.value?.stopEditing(true)
  gridApi.value?.refreshCells({ force: true })
  emit('selectionChange')
}

function onColumnResized(e: { finished: boolean; column: { getColId: () => string; getActualWidth: () => number } | null }) {
  if (!e.finished || !e.column) return
  const colId = e.column.getColId()
  if (colId === '_row') return
  // 持久化到 store
  designerStore.gridMeta.colWidths[colId] = e.column.getActualWidth()
}

function onGridReady(params: { api: GridApi }) {
  gridApi.value = params.api
  emit('gridReady', params.api)
  // 初次加载时刷新数据
  loadStateToGrid()
}

// ============================================================
// 公共方法（暴露给父组件调用）
// ============================================================

/** 从 store 重建并刷新表格数据（全量刷新） */
function loadStateToGrid() {
  if (!gridApi.value) return
  gridApi.value.setGridOption('rowData', buildRowData())
  gridApi.value.redrawRows()
  updateVisibleColumns()
}

/** 根据 store 的有效列数更新列的显隐 */
function updateVisibleColumns() {
  if (!gridApi.value) return
  const show: string[] = []
  const hide: string[] = []
  COL_LETTERS.forEach((l, i) => {
    if (i < designerStore.gridMeta.colCount) show.push(l)
    else hide.push(l)
  })
  if (show.length) gridApi.value.setColumnsVisible(show, true)
  if (hide.length) gridApi.value.setColumnsVisible(hide, false)
}

/** 批量应用保存的列宽（模板加载后调用） */
function applyColWidths() {
  if (!gridApi.value) return
  const widths = designerStore.gridMeta.colWidths
  const updates = Object.keys(widths).map((colId: string) => ({
    key: colId,
    newWidth: widths[colId],
  }))
  if (updates.length) gridApi.value.setColumnWidths(updates)
}

/** 设置指定行高 */
function setRowHeight(rowIdx: number, height: number) {
  designerStore.gridMeta.rowHeights[String(rowIdx)] = height
  gridApi.value?.resetRowHeights()
}

/** 设置指定列宽 */
function setColWidth(colId: string, width: number) {
  designerStore.gridMeta.colWidths[colId] = width
  gridApi.value?.setColumnWidths([{ key: colId, newWidth: width }])
}

/** 设置是否可排序 */
function setSortable(enabled: boolean) {
  if (!gridApi.value) return
  designerStore.gridMeta.columnSortable = enabled
  const defs = gridApi.value.getColumnDefs() || []
  defs.forEach((d: ColDef) => {
    if (d.field && d.field !== '_row') d.sortable = enabled
  })
  gridApi.value.setGridOption('columnDefs', defs)
  gridApi.value.refreshHeader()
}

/** 设置默认行高 */
function setDefaultRowHeight(height: number) {
  designerStore.gridMeta.defaultRowHeight = height
  gridApi.value?.resetRowHeights()
}

/** 设置默认列宽 */
function setDefaultColWidth(width: number) {
  designerStore.gridMeta.defaultColWidth = width
  const updates = COL_LETTERS.filter(
    (colId: string) => !designerStore.gridMeta.colWidths[colId],
  ).map((colId: string) => ({ key: colId, newWidth: width }))
  if (updates.length) gridApi.value?.setColumnWidths(updates)
}

/** 设置冻结行数 */
function setFreezeHeaderRows(rows: number) {
  designerStore.gridMeta.freezeHeaderRows = Math.max(0, rows || 0)
  gridApi.value?.refreshCells({ force: true })
  gridApi.value?.redrawRows()
}

/** 刷新单元格样式（选区或格式变化后调用） */
function refreshCells() {
  gridApi.value?.refreshCells({ force: true })
}

// ============================================================
// 鼠标拖拽框选事件
// ============================================================

function readCellPosFromTarget(target: EventTarget | null): {
  rowIdx: number; colId: string; colIdx: number
} | null {
  const el = target as Element
  if (!el?.closest) return null
  const cellEl = el.closest('.ag-cell')
  if (!cellEl) return null
  const rowEl = cellEl.closest('.ag-row')
  if (!rowEl) return null
  const colId = cellEl.getAttribute('col-id')
  if (!colId) return null
  const rowIdx = parseInt(rowEl.getAttribute('row-index') || '', 10)
  if (isNaN(rowIdx)) return null
  return { rowIdx, colId, colIdx: COL_LETTERS.indexOf(colId) }
}

/**
 * 从任意 DOM 目标解析所在行的 row-index（行号拖拽时鼠标可能落在间隙，不一定命中 .ag-cell）
 */
function readRowIndexFromTarget(target: EventTarget | null): number | null {
  const el = target as Element
  if (!el?.closest) return null
  const rowEl = el.closest('.ag-row')
  if (!rowEl) return null
  const rowIdx = parseInt(rowEl.getAttribute('row-index') || '', 10)
  return isNaN(rowIdx) ? null : rowIdx
}

function onMousedown(evt: MouseEvent) {
  if (evt.button !== 0) return
  const pos = readCellPosFromTarget(evt.target)
  if (!pos) return

  // 行号列：左键拖拽连续多选整行（与 Excel 行号拖拽一致）
  if (pos.colId === '_row') {
    dragRowSelecting.value = true
    rowDragAnchor.value = pos.rowIdx
    rowDragMoved.value = false
    designerStore.selectRow(pos.rowIdx, false)
    gridApi.value?.stopEditing(true)
    gridApi.value?.refreshCells({ force: true })
    emit('selectionChange')
    evt.preventDefault()
    return
  }

  if (pos.colIdx < 0) return
  dragSelecting.value = true
  designerStore.setSelection(pos.rowIdx, pos.colIdx, pos.rowIdx, pos.colIdx)
  gridApi.value?.stopEditing(true)
  gridApi.value?.refreshCells({ force: true })
  emit('selectionChange')
}

function onMousemove(evt: MouseEvent) {
  // 行号列拖拽：从锚点行到当前指针所在行整段选中
  if (dragRowSelecting.value && rowDragAnchor.value != null) {
    if (!(evt.buttons & 1)) {
      dragRowSelecting.value = false
      rowDragAnchor.value = null
      return
    }
    const pos = readCellPosFromTarget(evt.target)
    const rowIdx = pos?.rowIdx ?? readRowIndexFromTarget(evt.target)
    if (rowIdx == null) return
    rowDragMoved.value = true
    designerStore.selectRowRange(rowDragAnchor.value, rowIdx)
    gridApi.value?.refreshCells({ force: true })
    emit('selectionChange')
    return
  }

  if (!dragSelecting.value) return
  if (!(evt.buttons & 1)) {
    dragSelecting.value = false
    return
  }
  const pos = readCellPosFromTarget(evt.target)
  if (!pos || pos.colId === '_row' || pos.colIdx < 0) return
  designerStore.extendSelection(pos.rowIdx, pos.colIdx)
  gridApi.value?.refreshCells({ force: true })
  emit('selectionChange')
}

function onMouseup() {
  if (dragRowSelecting.value && rowDragMoved.value) {
    suppressNextRowCellClick.value = true
  }
  dragSelecting.value = false
  dragRowSelecting.value = false
  rowDragAnchor.value = null
  rowDragMoved.value = false
}

// ============================================================
// 键盘快捷键（Ctrl+Z 撤销）
// ============================================================

function onKeydown(evt: KeyboardEvent) {
  const key = evt.key?.toLowerCase()
  if (!(evt.ctrlKey || evt.metaKey) || key !== 'z') return
  const tag = (evt.target as HTMLElement)?.tagName?.toUpperCase()
  if (tag === 'INPUT' || tag === 'TEXTAREA' || (evt.target as HTMLElement)?.isContentEditable) return
  evt.preventDefault()
  designerStore.undoLastOperation(() => {
    loadStateToGrid()
    gridApi.value?.refreshHeader()
  })
}

// ============================================================
// 生命周期
// ============================================================

onMounted(() => {
  document.addEventListener('mouseup', onMouseup)
  document.addEventListener('keydown', onKeydown)
})

onUnmounted(() => {
  document.removeEventListener('mouseup', onMouseup)
  document.removeEventListener('keydown', onKeydown)
})

// ============================================================
// 对外暴露接口
// ============================================================

defineExpose({
  gridApi,
  loadStateToGrid,
  applyColWidths,
  setRowHeight,
  setColWidth,
  setSortable,
  setDefaultRowHeight,
  setDefaultColWidth,
  setFreezeHeaderRows,
  refreshCells,
  updateVisibleColumns,
})

// ============================================================
// AG Grid 配置
// ============================================================

/** getRowHeight 回调（动态行高支持） */
function getRowHeight(params: RowHeightParams): number | undefined {
  const h = designerStore.gridMeta.rowHeights[String(params.node.rowIndex ?? 0)]
  return h || designerStore.gridMeta.defaultRowHeight || undefined
}

/** 整个 grid 的 options */
const gridOptions: GridOptions = {
  suppressRowTransform: true,
  singleClickEdit: false,
  stopEditingWhenCellsLoseFocus: true,
  rowClassRules: {
    'row-frozen-boundary': (params: RowClassParams) => {
      const freeze = designerStore.gridMeta.freezeHeaderRows || 0
      return freeze > 0 && (params.node.rowIndex ?? 0) === freeze - 1
    },
  },
}
</script>

<template>
  <!-- 网格容器：高度撑满父元素 -->
  <div
    ref="gridWrapperRef"
    class="ag-theme-alpine w-full h-full"
    :class="{
      'er-grid-no-lines': !designerStore.gridMeta.showGridLines,
      'er-grid-lines': designerStore.gridMeta.showGridLines,
    }"
    @mousedown="onMousedown"
    @mousemove="onMousemove"
  >
    <AgGridVue
      class="w-full h-full"
      :columnDefs="columnDefs"
      :rowData="rowData"
      :gridOptions="gridOptions"
      :getRowHeight="getRowHeight"
      @cell-value-changed="onCellValueChanged"
      @cell-clicked="onCellClicked"
      @column-header-clicked="onColumnHeaderClicked"
      @column-resized="onColumnResized"
      @grid-ready="onGridReady"
    />
  </div>

  <!-- 设计器图片大图预览弹窗 -->
  <div
    v-if="imagePreviewVisible"
    class="fixed inset-0 z-[300] bg-black/60 flex items-center justify-center p-6"
    @click.self="closeImagePreview"
  >
    <div class="relative max-w-[90vw] max-h-[90vh]">
      <button
        class="absolute -top-3 -right-3 w-8 h-8 rounded-full bg-white text-gray-700 shadow hover:bg-gray-100"
        title="关闭"
        @click="closeImagePreview"
      >
        ×
      </button>
      <img
        :src="imagePreviewUrl"
        alt="图片预览"
        class="max-w-[90vw] max-h-[90vh] object-contain rounded shadow-2xl bg-white"
      >
    </div>
  </div>
</template>
