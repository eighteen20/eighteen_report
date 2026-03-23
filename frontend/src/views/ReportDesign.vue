<script setup lang="ts">
/**
 * 报表设计器主页
 *
 * 组合所有设计器子组件，实现完整的报表设计功能：
 * - 顶部工具栏（返回、报表名称、保存、预览）
 * - 格式工具栏（字体、颜色、对齐等）
 * - 左侧数据集面板
 * - 中央 AG Grid 编辑器
 * - 右侧设置面板
 * - 右键菜单
 *
 * 数据拖拽：字段列表拖到 grid 单元格，自动生成 ${dsKey.field} 变量表达式。
 */
import { ref, onMounted, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useDesignerStore, useReportStore } from '@/stores'
import { BaseButton } from '@/components/base'
import {
  ReportGrid,
  FormatToolbar,
  DatasetPanel,
  SettingsPanel,
  ContextMenu,
} from '@/components/report'
import type { GridApi } from 'ag-grid-community'
import { messager } from '@/composables/useMessager'

const route = useRoute()
const router = useRouter()
const designerStore = useDesignerStore()
const reportStore = useReportStore()

/** 报表名称（顶部输入框双向绑定） */
const templateName = ref('')
/** 保存加载状态 */
const saveLoading = ref(false)
/** 当前模板 ID（编辑模式下有值，新建模式为 null） */
const templateId = ref<string | null>(null)

/** AG Grid 组件实例（用于调用暴露的方法） */
const reportGridRef = ref<InstanceType<typeof ReportGrid>>()
/** 右键菜单组件实例 */
const contextMenuRef = ref<InstanceType<typeof ContextMenu>>()
/** grid 容器 DOM（用于绑定拖拽事件和右键菜单） */
const gridWrapperRef = ref<HTMLDivElement>()

/** AG Grid API（从 grid 组件获取） */
const gridApi = ref<GridApi | null>(null)

// ============================================================
// 初始化
// ============================================================

onMounted(async () => {
  const id = route.params.id as string | undefined
  if (id) {
    templateId.value = id
    await loadTemplate(id)
  } else {
    // 新建模式：重置状态（rowCount/colCount 等恢复默认）
    designerStore.reset()
    templateName.value = ''
  }

  // 等待 grid 渲染完成后：刷新行数据（新建时 reset 后需同步到表格）、绑定右键与拖拽
  await nextTick()
  reportGridRef.value?.loadStateToGrid()
  reportGridRef.value?.applyColWidths()
  if (gridWrapperRef.value) {
    contextMenuRef.value?.mountOn(gridWrapperRef.value)
    initDragDrop(gridWrapperRef.value)
  }
})

/** 加载已有模板内容 */
async function loadTemplate(id: string) {
  try {
    const t = await reportStore.fetchTemplate(id)
    templateName.value = t.name || ''
    if (t.content) {
      const cfg = JSON.parse(t.content)
      designerStore.loadContent(cfg)
    }
    // 等待 grid 初始化完成后刷新数据
    await nextTick()
    reportGridRef.value?.loadStateToGrid()
    reportGridRef.value?.applyColWidths()
    reportGridRef.value?.setSortable(!!designerStore.gridMeta.columnSortable)
    reportGridRef.value?.setDefaultRowHeight(designerStore.gridMeta.defaultRowHeight || 25)
    reportGridRef.value?.setDefaultColWidth(designerStore.gridMeta.defaultColWidth || 100)
    reportGridRef.value?.setFreezeHeaderRows(designerStore.gridMeta.freezeHeaderRows || 0)
  } catch (e) {
    messager.danger('加载模板失败：' + (e as Error).message)
  }
}

// ============================================================
// 保存
// ============================================================

async function doSave() {
  const name = templateName.value.trim()
  if (!name) {
    messager.warning('请输入报表名称')
    return
  }

  saveLoading.value = true
  try {
    const content = designerStore.serializeContent()
    const saved = await reportStore.saveTemplate(templateId.value, {
      name,
      description: '',
      content,
    })
    if (!templateId.value) {
      // 新建成功后跳转到编辑 URL（保留当前状态）
      templateId.value = saved.id
      router.replace(`/report/design/${saved.id}`)
    }
    messager.success('保存成功')
  } catch (e) {
    messager.danger('保存失败：' + (e as Error).message)
  } finally {
    saveLoading.value = false
  }
}

// ============================================================
// 预览
// ============================================================

function goPreview() {
  if (!templateId.value) {
    messager.warning('请先保存报表')
    return
  }
  window.open(`/report/preview/${templateId.value}`, '_blank')
}

// ============================================================
// 格式工具栏事件
// ============================================================

function onRefreshCells() {
  reportGridRef.value?.refreshCells()
}

function onToggleMerge() {
  const b = designerStore.selBounds
  if (!b) {
    messager.warning('请先选中单元格')
    return
  }

  if (designerStore.selectionHasMerge()) {
    // 取消合并：移除命中的合并块
    designerStore.merges = designerStore.merges.filter(
      (m: { row: number; col: number; rowSpan: number; colSpan: number }) =>
        !(m.row === b.minR && m.col === b.minC),
    )
  } else {
    // 交互优化：若当前选区“落在某个合并块内部”，则认为用户要取消该合并（不要求选中左上角锚点）
    if (designerStore.selectionOverlapsMerge()) {
      const m = designerStore.merges.find((mm) =>
        b.minR >= mm.row && b.minR < mm.row + mm.rowSpan &&
        b.minC >= mm.col && b.minC < mm.col + mm.colSpan,
      ) || null
      // 仅当选区完全处于同一个合并块内时才自动取消，避免误删多个合并块
      if (
        m &&
        b.maxR <= m.row + m.rowSpan - 1 &&
        b.maxC <= m.col + m.colSpan - 1
      ) {
        designerStore.merges = designerStore.merges.filter(
          (mm: { row: number; col: number; rowSpan: number; colSpan: number }) =>
            !(mm.row === m.row && mm.col === m.col),
        )
      } else {
        messager.warning('选区与已有合并区域重叠，请先取消合并')
        return
      }
    } else {
      // 合并：无重叠时才允许创建新的合并块
      designerStore.merges.push({
        row: b.minR,
        col: b.minC,
        rowSpan: b.maxR - b.minR + 1,
        colSpan: b.maxC - b.minC + 1,
      })
    }
  }

  reportGridRef.value?.loadStateToGrid()
}

function onToggleFreeze() {
  const b = designerStore.selBounds
  if (!b) {
    messager.warning('请先选中单元格')
    return
  }
  const freezeRows = b.minR + 1
  const current = designerStore.gridMeta.freezeHeaderRows || 0
  // 再次点击同一位置时取消冻结
  reportGridRef.value?.setFreezeHeaderRows(current === freezeRows ? 0 : freezeRows)
}

// ============================================================
// 设置面板事件
// ============================================================

function onResetRowHeights() {
  // 触发 grid 重新计算行高
  reportGridRef.value?.loadStateToGrid()
}

function onResetColWidths() {
  reportGridRef.value?.applyColWidths()
}

function onSetSortable(enabled: boolean) {
  reportGridRef.value?.setSortable(enabled)
}

// ============================================================
// Grid 选区变化
// ============================================================

function onSelectionChange() {
  // 选区变化时设置面板会自动响应（通过 computed 读取 store）
}

function onGridReady(api: GridApi) {
  gridApi.value = api
}

// ============================================================
// 字段拖拽到单元格（数据集面板 → 表格）
// ============================================================

function initDragDrop(el: HTMLElement) {
  el.addEventListener('dragover', (e) => {
    e.preventDefault()
    if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy'
  })

  el.addEventListener('drop', (e) => {
    e.preventDefault()
    const varExpr = e.dataTransfer?.getData('text/plain')
    if (!varExpr || !varExpr.includes('${')) return

    const cellEl = (e.target as Element).closest('.ag-cell')
    if (!cellEl) return
    const colId = cellEl.getAttribute('col-id')
    if (!colId || colId === '_row') return
    const rowEl = cellEl.closest('.ag-row')
    if (!rowEl) return
    const rowIdx = parseInt(rowEl.getAttribute('row-index') || '', 10)
    if (isNaN(rowIdx)) return

    // 写入 store
    designerStore.syncCellValue(rowIdx, colId, varExpr)
    // 更新 grid 显示
    const rowNode = gridApi.value?.getDisplayedRowAtIndex(rowIdx)
    rowNode?.setDataValue(colId, varExpr)
  })
}
</script>

<template>
  <div class="flex flex-col h-full overflow-hidden bg-gray-50">
    <!-- ===== 顶部工具栏 ===== -->
    <header class="flex items-center gap-2 px-3 py-2 bg-white border-b border-gray-200 shrink-0 h-11">
      <!-- 返回按钮 -->
      <BaseButton size="sm" @click="router.push('/report')">← 返回</BaseButton>

      <!-- 报表名称输入 -->
      <input
        v-model="templateName"
        type="text"
        placeholder="请输入报表名称"
        class="flex-1 border border-gray-300 rounded px-3 py-1 text-[13px] outline-none
               focus:border-blue-500 focus:ring-1 focus:ring-blue-500 max-w-xs transition-colors"
      />

      <div class="flex-1" />

      <!-- 保存按钮 -->
      <BaseButton variant="primary" :loading="saveLoading" @click="doSave">
        保存
      </BaseButton>
      <!-- 预览按钮 -->
      <BaseButton @click="goPreview">预览</BaseButton>
    </header>

    <!-- ===== 格式工具栏 ===== -->
    <FormatToolbar
      :template-id="templateId || undefined"
      @refresh-cells="onRefreshCells"
      @toggle-merge="onToggleMerge"
      @toggle-freeze="onToggleFreeze"
    />

    <!-- ===== 主工作区（左面板 + 表格 + 右面板） ===== -->
    <div class="flex flex-1 min-h-0 overflow-hidden">
      <!-- 左侧数据集面板 -->
      <DatasetPanel />

      <!-- 中央表格区域 -->
      <div
        ref="gridWrapperRef"
        class="er-grid-wrapper flex-1 min-w-0 overflow-hidden"
      >
        <ReportGrid
          ref="reportGridRef"
          class="w-full h-full"
          @selection-change="onSelectionChange"
          @grid-ready="onGridReady"
        />
      </div>

      <!-- 右侧设置面板 -->
      <SettingsPanel
        :grid-api="gridApi"
        @reset-row-heights="onResetRowHeights"
        @reset-col-widths="onResetColWidths"
        @set-sortable="onSetSortable"
        @refresh-cells="onRefreshCells"
      />
    </div>

    <!-- 右键菜单（通过 mountOn 挂载到 grid 容器） -->
    <ContextMenu
      ref="contextMenuRef"
      :grid-api="gridApi"
      @load-state-to-grid="reportGridRef?.loadStateToGrid()"
    />
  </div>
</template>
