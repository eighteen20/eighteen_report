/**
 * 设计器核心状态 Pinia Store
 *
 * 管理报表设计器的所有运行时状态：
 * - 单元格数据（值、样式、类型）
 * - 合并区域
 * - 选区（矩形范围）
 * - 网格元数据（行高、列宽、冻结行等全局设置）
 * - 数据集定义
 *
 * 这是设计器最核心的状态，所有子组件通过此 store 共享状态。
 */
import { defineStore } from 'pinia'
import { ref, reactive, computed } from 'vue'
import type {
  CellData,
  CellStyle,
  MergeRegion,
  GridMeta,
  DatasetDefinition,
  ReportContent,
} from '@/types'

/** 最大支持列数（支持 A-AZ，共 52 列，保留插列扩展空间） */
const MAX_COL_COUNT = 52

/** 初始行数 */
const INITIAL_ROW_COUNT = 50

/** 初始列数 */
const INITIAL_COL_COUNT = 10

/**
 * 根据 0-based 列索引生成 Excel 列名（0→A, 25→Z, 26→AA）
 */
function toColLetter(idx: number): string {
  let n = idx + 1
  let s = ''
  while (n > 0) {
    const m = (n - 1) % 26
    s = String.fromCharCode(65 + m) + s
    n = Math.floor((n - 1) / 26)
  }
  return s
}

/** 生成完整列名列表（长度为 MAX_COL_COUNT） */
export const COL_LETTERS: string[] = Array.from({ length: MAX_COL_COUNT }, (_, i) => toColLetter(i))

export const useDesignerStore = defineStore('designer', () => {
  // ============================================================
  // 核心报表状态（序列化后保存到后端 content 字段）
  // ============================================================

  /** 单元格数据映射：key 为 "A1"/"B2" 格式，value 为单元格配置 */
  const cells = ref<Record<string, CellData>>({})
  /** 合并区域列表 */
  const merges = ref<MergeRegion[]>([])
  /** 数据集定义列表 */
  const datasets = ref<DatasetDefinition[]>([])
  /** 网格元数据（全局设置） */
  const gridMeta = reactive<GridMeta>({
    rowCount: INITIAL_ROW_COUNT,
    colCount: INITIAL_COL_COUNT,
    colWidths: {},
    rowHeights: {},
    columnSortable: false,
    showGridLines: false,
    /** 预览/导出渲染默认不画主题网格线，与设计器线稿分离 */
    renderShowGridLines: false,
    showPreviewColHeader: false,
    previewContentAlign: 'left',
    defaultRowHeight: 25,
    defaultColWidth: 100,
    freezeHeaderRows: 0,
    pageMargins: { top: 10, right: 10, bottom: 10, left: 10 },
    pagination: 'none',
    paginationRows: 30,
    watermark: '',
    watermarkDensity: 1,
    /** 动态水印由后端请求业务回调获取，避免预览 URL 参数伪造 */
    watermarkCallbackUrl: '',
    edgeMarginTopRow: false,
    edgeMarginLeftCol: false,
    edgeMarginRightCol: false,
    /** 模板级图片上传回调地址，空字符串表示未配置 */
    imageUploadCallbackUrl: '',
  })

  // ============================================================
  // 选区状态（不需要持久化）
  // ============================================================

  /**
   * 当前选区：r1/c1 为锚点（鼠标按下位置），r2/c2 为终点（拖拽终止位置）
   * 未选中时均为 -1
   */
  const sel = reactive({ r1: -1, c1: -1, r2: -1, c2: -1 })

  // ============================================================
  // 撤销栈（仅记录行列插删操作）
  // ============================================================

  interface UndoSnapshot {
    rowCount: number
    colCount: number
    cells: Record<string, CellData>
    merges: MergeRegion[]
    rowHeights: Record<string, number>
    colWidths: Record<string, number>
    freezeHeaderRows: number
  }

  const undoStack = ref<UndoSnapshot[]>([])

  // ============================================================
  // 计算属性
  // ============================================================

  /** 当前有效列数（实际显示的列） */
  const colCount = computed(() => gridMeta.colCount)

  /** 当前有效行数 */
  const rowCount = computed(() => gridMeta.rowCount)

  /** 选区标准化边界（minR/maxR/minC/maxC），未选中时为 null */
  const selBounds = computed(() => {
    if (sel.r1 < 0) return null
    return {
      minR: Math.min(sel.r1, sel.r2),
      maxR: Math.max(sel.r1, sel.r2),
      minC: Math.min(sel.c1, sel.c2),
      maxC: Math.max(sel.c1, sel.c2),
    }
  })

  /** 锚点（左上角）单元格引用，如 "A1" */
  const anchorRef = computed(() => {
    const b = selBounds.value
    if (!b) return null
    return COL_LETTERS[b.minC] + (b.minR + 1)
  })

  // ============================================================
  // 选区操作
  // ============================================================

  /** 设置选区（单击时 r1=r2, c1=c2） */
  function setSelection(r1: number, c1: number, r2: number, c2: number) {
    sel.r1 = r1; sel.c1 = c1; sel.r2 = r2; sel.c2 = c2
  }

  /** Shift+点击：扩展选区终点，保持锚点不变 */
  function extendSelection(r: number, c: number) {
    sel.r2 = r; sel.c2 = c
  }

  /** 整列选中 */
  function selectColumn(colIdx: number, extend: boolean) {
    if (extend && sel.r1 >= 0) {
      sel.r1 = 0; sel.r2 = gridMeta.rowCount - 1; sel.c2 = colIdx
    } else {
      setSelection(0, colIdx, gridMeta.rowCount - 1, colIdx)
    }
  }

  /** 整行选中 */
  function selectRow(rowIdx: number, extend: boolean) {
    if (extend && sel.r1 >= 0) {
      sel.c1 = 0; sel.c2 = gridMeta.colCount - 1; sel.r2 = rowIdx
    } else {
      setSelection(rowIdx, 0, rowIdx, gridMeta.colCount - 1)
    }
  }

  /**
   * 连续多行整行选中（用于行号列按下拖拽）
   *
   * @param fromRow 起始行索引（0-based）
   * @param toRow   结束行索引（0-based），可与 fromRow 颠倒
   */
  function selectRowRange(fromRow: number, toRow: number) {
    const lo = Math.min(fromRow, toRow)
    const hi = Math.max(fromRow, toRow)
    setSelection(lo, 0, hi, gridMeta.colCount - 1)
  }

  /** 判断指定单元格是否在选区内 */
  function isCellSelected(r: number, c: number): boolean {
    const b = selBounds.value
    return !!b && r >= b.minR && r <= b.maxR && c >= b.minC && c <= b.maxC
  }

  /** 获取选区内所有单元格引用 */
  function getSelRefs(): string[] {
    const b = selBounds.value
    if (!b) return []
    const refs: string[] = []
    for (let r = b.minR; r <= b.maxR; r++)
      for (let c = b.minC; c <= b.maxC; c++)
        refs.push(COL_LETTERS[c] + (r + 1))
    return refs
  }

  // ============================================================
  // 单元格操作
  // ============================================================

  /** 确保单元格存在并返回，不存在时初始化 */
  function ensureCell(ref: string): CellData {
    if (!cells.value[ref]) cells.value[ref] = {}
    if (!cells.value[ref].style) cells.value[ref].style = {}
    return cells.value[ref]
  }

  /** 清理空单元格（值为空且无样式时删除，减小序列化体积） */
  function cleanCell(ref: string) {
    const c = cells.value[ref]
    if (!c) return
    if (c.style && Object.keys(c.style).length === 0) delete c.style
    if ((!c.value || !c.value.trim()) && !c.style && !c.type) delete cells.value[ref]
  }

  /** 同步单元格值到 state */
  function syncCellValue(rowIdx: number, colLetter: string, value: string) {
    if (colLetter === '_row') return
    const ref = colLetter + (rowIdx + 1)
    if (value && value.trim()) {
      ensureCell(ref).value = value
    } else {
      if (cells.value[ref]) {
        cells.value[ref].value = ''
        cleanCell(ref)
      }
    }
  }

  /** 批量设置选区内所有单元格的样式属性 */
  function setSelectionStyle(prop: keyof CellStyle, val: string) {
    getSelRefs().forEach((ref) => {
      const c = ensureCell(ref)
      if (val) c.style![prop] = val
      else delete c.style![prop]
      cleanCell(ref)
    })
  }

  /** 切换选区内所有单元格的样式属性（toggle） */
  function toggleSelectionStyle(prop: keyof CellStyle, onVal: string) {
    const ar = anchorRef.value
    if (!ar) return
    const c = cells.value[ar]
    const cur = (c?.style?.[prop]) || ''
    setSelectionStyle(prop, cur === onVal ? '' : onVal)
  }

  /**
   * 按预设为选区设置边框（与旧版边框菜单一致）
   * @param preset all=四边 | outer=仅选区外框 | top/bottom/left/right=单边 | none=清除四边
   */
  function setSelectionBorder(preset: 'all' | 'outer' | 'top' | 'bottom' | 'left' | 'right' | 'none') {
    const b = selBounds.value!
    if (!b) return
    const style = '1px solid #374151'

    /**
     * 将“逻辑位置”的边框画到“可见单元格”上：
     * - 被合并覆盖的单元格（非锚点）不会渲染 cell DOM；
     *   若把右/下边框画在这些被覆盖单元格上，会出现“边框缺失”。
     * - 因此这里把边框落到覆盖该位置的合并块锚点（左上角）单元格上，保证可见。
     */
    function findCoveringMerge(row: number, col: number): MergeRegion | null {
      return merges.value.find(
        (m: MergeRegion) =>
          row >= m.row && row < m.row + m.rowSpan &&
          col >= m.col && col < m.col + m.colSpan,
      ) || null
    }

    function toVisibleRef(row: number, col: number): string {
      const m = findCoveringMerge(row, col)
      if (m) return COL_LETTERS[m.col] + (m.row + 1)
      return COL_LETTERS[col] + (row + 1)
    }

    /** 是否为“可见单元格”（合并块内部非锚点视为不可见） */
    function isVisibleCell(row: number, col: number): boolean {
      const m = findCoveringMerge(row, col)
      if (!m) return true
      return m.row === row && m.col === col
    }

    function setEdge(row: number, col: number, edge: 'top' | 'right' | 'bottom' | 'left', val: string | '') {
      const ref = toVisibleRef(row, col)
      const c = ensureCell(ref)
      if (edge === 'top') {
        if (val) c.style!.borderTop = val
        else delete c.style!.borderTop
      } else if (edge === 'right') {
        if (val) c.style!.borderRight = val
        else delete c.style!.borderRight
      } else if (edge === 'bottom') {
        if (val) c.style!.borderBottom = val
        else delete c.style!.borderBottom
      } else {
        if (val) c.style!.borderLeft = val
        else delete c.style!.borderLeft
      }
      cleanCell(ref)
    }

    if (preset === 'none') {
      for (let r = b.minR; r <= b.maxR; r++) {
        for (let c = b.minC; c <= b.maxC; c++) {
          if (!isVisibleCell(r, c)) continue
          const ref = toVisibleRef(r, c)
          const cell = ensureCell(ref)
          delete cell.style!.borderTop
          delete cell.style!.borderRight
          delete cell.style!.borderBottom
          delete cell.style!.borderLeft
          cleanCell(ref)
        }
      }
      return
    }

    if (preset === 'all') {
      // 所有边框：相邻单元格之间只保留一条线，避免叠线变粗，并兼容合并单元格
      // 规则：每个“可见单元格”画「上边 + 左边」；选区最后一行补下边，最后一列补右边。
      for (let r = b.minR; r <= b.maxR; r++) {
        for (let c = b.minC; c <= b.maxC; c++) {
          if (!isVisibleCell(r, c)) continue
          setEdge(r, c, 'top', style)
          setEdge(r, c, 'left', style)
        }
      }
      // 边界补齐：即使边界位置落在“被合并覆盖的单元格”，也要把边框画到合并锚点上
      for (let r = b.minR; r <= b.maxR; r++) setEdge(r, b.maxC, 'right', style)
      for (let c = b.minC; c <= b.maxC; c++) setEdge(b.maxR, c, 'bottom', style)
      return
    }

    if (preset === 'outer') {
      // 外侧边框：只画选区四条外边
      // 先清掉选区内可见单元格的四边，避免残留导致“外框+内部线”混在一起
      for (let r = b.minR; r <= b.maxR; r++) {
        for (let c = b.minC; c <= b.maxC; c++) {
          if (!isVisibleCell(r, c)) continue
          setEdge(r, c, 'top', '')
          setEdge(r, c, 'right', '')
          setEdge(r, c, 'bottom', '')
          setEdge(r, c, 'left', '')
        }
      }
      for (let c = b.minC; c <= b.maxC; c++) setEdge(b.minR, c, 'top', style)
      for (let c = b.minC; c <= b.maxC; c++) setEdge(b.maxR, c, 'bottom', style)
      for (let r = b.minR; r <= b.maxR; r++) setEdge(r, b.minC, 'left', style)
      for (let r = b.minR; r <= b.maxR; r++) setEdge(r, b.maxC, 'right', style)
      return
    }

    // 单边：只画选区外边界对应的一条边（并兼容合并单元格）
    if (preset === 'top') {
      for (let c = b.minC; c <= b.maxC; c++) setEdge(b.minR, c, 'top', style)
      return
    }
    if (preset === 'bottom') {
      for (let c = b.minC; c <= b.maxC; c++) setEdge(b.maxR, c, 'bottom', style)
      return
    }
    if (preset === 'left') {
      for (let r = b.minR; r <= b.maxR; r++) setEdge(r, b.minC, 'left', style)
      return
    }
    if (preset === 'right') {
      for (let r = b.minR; r <= b.maxR; r++) setEdge(r, b.maxC, 'right', style)
      return
    }
  }

  /** 切换文本装饰（underline / line-through 可并存） */
  function toggleTextDecoration(dec: string) {
    const ar = anchorRef.value
    if (!ar) return
    const cur = (cells.value[ar]?.style?.textDecoration) || ''
    const has = cur.includes(dec)
    getSelRefs().forEach((ref) => {
      const c = ensureCell(ref)
      const parts = (c.style!.textDecoration || '').split(/\s+/).filter(Boolean)
      const idx = parts.indexOf(dec)
      if (has) { if (idx >= 0) parts.splice(idx, 1) }
      else { if (idx < 0) parts.push(dec) }
      if (parts.length) c.style!.textDecoration = parts.join(' ')
      else delete c.style!.textDecoration
      cleanCell(ref)
    })
  }

  // ============================================================
  // 撤销操作（仅针对行列插删）
  // ============================================================

  function pushUndoSnapshot() {
    const snap: UndoSnapshot = {
      rowCount: gridMeta.rowCount,
      colCount: gridMeta.colCount,
      cells: JSON.parse(JSON.stringify(cells.value)),
      merges: JSON.parse(JSON.stringify(merges.value)),
      rowHeights: JSON.parse(JSON.stringify(gridMeta.rowHeights)),
      colWidths: JSON.parse(JSON.stringify(gridMeta.colWidths)),
      freezeHeaderRows: gridMeta.freezeHeaderRows,
    }
    undoStack.value.push(snap)
    // 最多保存 20 步历史，超出时丢弃最早的快照
    if (undoStack.value.length > 20) undoStack.value.shift()
  }

  function undoLastOperation(onRestore: () => void) {
    if (undoStack.value.length === 0) return
    const snap = undoStack.value.pop()!
    gridMeta.rowCount = snap.rowCount
    gridMeta.colCount = snap.colCount
    cells.value = snap.cells
    merges.value = snap.merges
    gridMeta.rowHeights = snap.rowHeights
    gridMeta.colWidths = snap.colWidths
    gridMeta.freezeHeaderRows = snap.freezeHeaderRows
    onRestore()
  }

  // ============================================================
  // 行列插删操作
  // ============================================================

  /** 解析单元格引用 "A1" 为 {col, row}（0-based） */
  function parseCellRef(ref: string): { col: number; row: number } | null {
    const m = ref.match(/^([A-Z]+)(\d+)$/)
    if (!m) return null
    let col = 0
    for (let i = 0; i < m[1].length; i++) col = col * 26 + (m[1].charCodeAt(i) - 64)
    return { col: col - 1, row: parseInt(m[2]) - 1 }
  }

  /** 在指定行上方插入 N 行
   *
   * @param rowIdx 在此行索引之前插入（0 表示最顶行之上）
   * @param count 插入行数
   * @param opts.skipUndo 为 true 时不单独入撤销栈（供批量操作外层统一 {@link pushUndoSnapshot}）
   */
  function insertRowsAbove(rowIdx: number, count = 1, opts?: { skipUndo?: boolean }) {
    count = Math.max(1, count)
    const target = Math.max(0, Math.min(gridMeta.rowCount, rowIdx))
    if (!opts?.skipUndo) pushUndoSnapshot()
    const next: Record<string, CellData> = {}
    Object.keys(cells.value).forEach((ref) => {
      const p = parseCellRef(ref)
      if (!p) return
      const nr = p.row >= target ? p.row + count : p.row
      next[COL_LETTERS[p.col] + (nr + 1)] = cells.value[ref]
    })
    cells.value = next
    merges.value = merges.value.map((m: MergeRegion) => {
      const end = m.row + m.rowSpan - 1
      if (m.row >= target) m = { ...m, row: m.row + count }
      else if (target <= end) m = { ...m, rowSpan: m.rowSpan + count }
      return m
    })
    shiftRowHeights(target, count)
    gridMeta.rowCount += count
  }

  /** 删除指定行 */
  function deleteRow(rowIdx: number, hasContent: boolean): boolean {
    if (gridMeta.rowCount <= 1) return false
    if (hasContent && !confirm('该行存在内容，确认删除整行吗？')) return false
    const target = Math.max(0, Math.min(gridMeta.rowCount - 1, rowIdx))
    // 若删除的是「页边留白」的顶行，需要同步清理标记，并避免冻结行数错位
    if (gridMeta.edgeMarginTopRow === true && target === 0) {
      gridMeta.edgeMarginTopRow = false
      // 顶部冻结区永远包含第 0 行，删除第 0 行时冻结行数也应同步 -1
      if (gridMeta.freezeHeaderRows > 0) {
        gridMeta.freezeHeaderRows -= 1
      }
    }
    pushUndoSnapshot()
    const next: Record<string, CellData> = {}
    Object.keys(cells.value).forEach((ref) => {
      const p = parseCellRef(ref)
      if (!p || p.row === target) return
      const nr = p.row > target ? p.row - 1 : p.row
      next[COL_LETTERS[p.col] + (nr + 1)] = cells.value[ref]
    })
    cells.value = next
    merges.value = merges.value
      .map((m: MergeRegion) => {
        const end = m.row + m.rowSpan - 1
        if (m.row > target) return { ...m, row: m.row - 1 }
        if (target >= m.row && target <= end) {
          if (m.rowSpan <= 1) return null
          const newRow = target === m.row ? m.row + 1 : m.row
          return { ...m, row: newRow, rowSpan: m.rowSpan - 1 }
        }
        return m
      })
      .filter((m: MergeRegion | null): m is MergeRegion => m !== null && (m.rowSpan > 1 || m.colSpan > 1))
    shiftRowHeights(target + 1, -1)
    gridMeta.rowCount -= 1
    if (gridMeta.freezeHeaderRows > gridMeta.rowCount) {
      gridMeta.freezeHeaderRows = gridMeta.rowCount
    }
    return true
  }

  /** 在指定列左侧插入 N 列（{@code colIdx === colCount} 时在表格最右侧追加右端空白列）
   *
   * @param colIdx 插入点列索引（0-based）；等于当前 {@link gridMeta.colCount} 时表示在末尾追加
   * @param count 插入列数
   * @param opts.skipUndo 为 true 时不单独入撤销栈（供批量操作统一撤销）
   */
  function insertColsLeft(colIdx: number, count = 1, opts?: { skipUndo?: boolean }) {
    count = Math.max(1, count)
    if (gridMeta.colCount + count > COL_LETTERS.length) {
      count = COL_LETTERS.length - gridMeta.colCount
    }
    if (count <= 0) return
    const target = Math.max(0, Math.min(gridMeta.colCount, colIdx))
    if (!opts?.skipUndo) pushUndoSnapshot()
    const next: Record<string, CellData> = {}
    Object.keys(cells.value).forEach((ref) => {
      const p = parseCellRef(ref)
      if (!p) return
      const nc = p.col >= target ? p.col + count : p.col
      if (nc < COL_LETTERS.length) next[COL_LETTERS[nc] + (p.row + 1)] = cells.value[ref]
    })
    cells.value = next
    merges.value = merges.value.map((m: MergeRegion) => {
      const end = m.col + m.colSpan - 1
      if (m.col >= target) return { ...m, col: m.col + count }
      if (target <= end) return { ...m, colSpan: m.colSpan + count }
      return m
    })
    shiftColWidths(target, count)
    gridMeta.colCount += count
  }

  /** 删除指定列 */
  function deleteCol(colIdx: number, hasContent: boolean): boolean {
    if (gridMeta.colCount <= 1) return false
    if (hasContent && !confirm('该列存在内容，确认删除整列吗？')) return false
    const target = Math.max(0, Math.min(gridMeta.colCount - 1, colIdx))
    const oldLastCol = gridMeta.colCount - 1
    // 若删除的是「页边留白」的左/右空列，需要同步清理标记
    if (gridMeta.edgeMarginLeftCol === true && target === 0) {
      gridMeta.edgeMarginLeftCol = false
    }
    if (gridMeta.edgeMarginRightCol === true && target === oldLastCol) {
      gridMeta.edgeMarginRightCol = false
    }
    pushUndoSnapshot()
    const next: Record<string, CellData> = {}
    Object.keys(cells.value).forEach((ref) => {
      const p = parseCellRef(ref)
      if (!p || p.col === target) return
      const nc = p.col > target ? p.col - 1 : p.col
      next[COL_LETTERS[nc] + (p.row + 1)] = cells.value[ref]
    })
    cells.value = next
    merges.value = merges.value
      .map((m: MergeRegion) => {
        const end = m.col + m.colSpan - 1
        if (m.col > target) return { ...m, col: m.col - 1 }
        if (target >= m.col && target <= end) {
          if (m.colSpan <= 1) return null
          const newCol = target === m.col ? m.col + 1 : m.col
          return { ...m, col: newCol, colSpan: m.colSpan - 1 }
        }
        return m
      })
      .filter((m: MergeRegion | null): m is MergeRegion => m !== null && (m.rowSpan > 1 || m.colSpan > 1))
    shiftColWidths(target + 1, -1)
    gridMeta.colCount -= 1
    return true
  }

  /**
   * 插入页边留白：顶部空白一行、最左与最右各一空白列。
   *
   * - 顶行高度、左右列宽取自当前 {@link gridMeta.pageMargins} 的 top / left / right（无效或缺省时用 16px，且不小于 4px）。
   * - 留白作为真实行列写入模板，预览与导出与设计器一致，不依赖外层 CSS padding。
   * - 若 {@link gridMeta.freezeHeaderRows} 大于 0，会将冻结行数 +1，使原先冻结的表头仍对应同一块内容（顶行空白一并冻结在顶部）。
   *
   * 单次撤销即可整步回退（内部插行插列不重复入撤销栈）。
   */
  function applyEdgeMarginGutters() {
    pushUndoSnapshot()
    insertRowsAbove(0, 1, { skipUndo: true })
    const pm = gridMeta.pageMargins || { top: 16, left: 16, right: 16, bottom: 10 }
    const topPx = Math.max(4, Number(pm.top) >= 0 ? Number(pm.top) : 16)
    gridMeta.rowHeights['0'] = topPx
    const fz = gridMeta.freezeHeaderRows || 0
    if (fz > 0) {
      gridMeta.freezeHeaderRows = fz + 1
    }
    insertColsLeft(0, 1, { skipUndo: true })
    const leftPx = Math.max(4, Number(pm.left) >= 0 ? Number(pm.left) : 16)
    gridMeta.colWidths[COL_LETTERS[0]] = leftPx
    insertColsLeft(gridMeta.colCount, 1, { skipUndo: true })
    const rightPx = Math.max(4, Number(pm.right) >= 0 ? Number(pm.right) : 16)
    gridMeta.colWidths[COL_LETTERS[gridMeta.colCount - 1]] = rightPx
    /** 预览据此在留白区禁用点击，并控制仅多渲染一列右留白 */
    gridMeta.edgeMarginTopRow = true
    gridMeta.edgeMarginLeftCol = true
    gridMeta.edgeMarginRightCol = true
  }

  function shiftRowHeights(fromRow: number, delta: number) {
    const old = gridMeta.rowHeights
    const next: Record<string, number> = {}
    Object.keys(old).forEach((k) => {
      const idx = parseInt(k, 10)
      if (isNaN(idx)) return
      if (idx >= fromRow) next[String(idx + delta)] = old[k]
      else next[k] = old[k]
    })
    gridMeta.rowHeights = next
  }

  function shiftColWidths(fromCol: number, delta: number) {
    const old = gridMeta.colWidths
    const next: Record<string, number> = {}
    Object.keys(old).forEach((colId) => {
      const idx = COL_LETTERS.indexOf(colId)
      if (idx < 0) return
      if (idx >= fromCol) {
        const ni = idx + delta
        if (ni >= 0 && ni < COL_LETTERS.length) next[COL_LETTERS[ni]] = old[colId]
      } else next[colId] = old[colId]
    })
    gridMeta.colWidths = next
  }

  // ============================================================
  // 合并单元格操作
  // ============================================================

  /**
   * 查找指定位置的合并区域（仅匹配左上角锚点）
   */
  function findMergeAt(row: number, col: number): MergeRegion | null {
    return merges.value.find((m) => m.row === row && m.col === col) || null
  }

  /**
   * 查找“覆盖指定单元格”的合并区域（不要求是左上角锚点）
   *
   * 设计器取消合并时常见交互是：点中合并块内部任意单元格 → 点击“取消合并”。
   * 因此需要能从任意 cell 定位到其所在的 merge region。
   *
   * @param row 行索引（0-based）
   * @param col 列索引（0-based）
   * @returns 覆盖该单元格的合并区域，若无则返回 null
   */
  function findMergeCovering(row: number, col: number): MergeRegion | null {
    return merges.value.find(
      (m: MergeRegion) =>
        row >= m.row && row < m.row + m.rowSpan &&
        col >= m.col && col < m.col + m.colSpan,
    ) || null
  }

  /**
   * 判断单元格是否被合并覆盖（非左上角，不可编辑）
   */
  function isCoveredByMerge(row: number, col: number): boolean {
    return merges.value.some(
      (m: MergeRegion) =>
        row >= m.row && row < m.row + m.rowSpan &&
        col >= m.col && col < m.col + m.colSpan &&
        (row !== m.row || col !== m.col),
    )
  }

  /**
   * 判断选区是否与现有合并区域重叠
   */
  function selectionOverlapsMerge(): boolean {
    const b = selBounds.value
    if (!b) return false
    return merges.value.some(
      (m: MergeRegion) =>
        b.minR <= m.row + m.rowSpan - 1 && b.maxR >= m.row &&
        b.minC <= m.col + m.colSpan - 1 && b.maxC >= m.col,
    )
  }

  /**
   * 判断当前选区是否已有合并
   */
  function selectionHasMerge(): boolean {
    const b = selBounds.value
    if (!b) return false
    return merges.value.some(
      (m: MergeRegion) => m.row === b.minR && m.col === b.minC &&
             m.rowSpan === b.maxR - b.minR + 1 && m.colSpan === b.maxC - b.minC + 1,
    )
  }

  // ============================================================
  // 状态加载与序列化
  // ============================================================

  /**
   * 将完整报表内容（从后端或本地）加载到 store
   * @param content 模板 content JSON 解析结果
   */
  function loadContent(content: ReportContent) {
    datasets.value = content.datasets || []
    // 兼容旧模板：补齐数据集分页默认配置，避免页面读取空字段时出现 undefined 分支判断复杂化
    datasets.value = datasets.value.map((ds) => ({
      ...ds,
      pagination: {
        enabled: ds.pagination?.enabled === true,
        defaultPageSize: ds.pagination?.defaultPageSize ?? 20,
        request: {
          pageParam: ds.pagination?.request?.pageParam ?? 'page',
          pageSizeParam: ds.pagination?.request?.pageSizeParam ?? 'pageSize',
          offsetParam: ds.pagination?.request?.offsetParam ?? 'offset',
          limitParam: ds.pagination?.request?.limitParam ?? 'limit',
        },
        response: {
          recordsPath: ds.pagination?.response?.recordsPath ?? '',
          totalPath: ds.pagination?.response?.totalPath ?? '',
          currentPagePath: ds.pagination?.response?.currentPagePath ?? '',
          pageSizePath: ds.pagination?.response?.pageSizePath ?? '',
          hasMorePath: ds.pagination?.response?.hasMorePath ?? '',
        },
      },
    }))
    cells.value = content.cells || {}
    merges.value = content.merges || []
    if (content.gridMeta) {
      Object.assign(gridMeta, content.gridMeta)
      // 清理旧模板中已废弃的纸张/方向字段，避免后续保存时再次写回 content。
      // 由于历史模板的 gridMeta 可能仍包含这些字段，因此这里需要显式删除。
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      delete (gridMeta as any).paperSize
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      delete (gridMeta as any).pageOrientation
      if (!gridMeta.colWidths) gridMeta.colWidths = {}
      if (!gridMeta.rowHeights) gridMeta.rowHeights = {}
      if (!gridMeta.pageMargins) gridMeta.pageMargins = { top: 10, right: 10, bottom: 10, left: 10 }
      if (gridMeta.watermarkCallbackUrl === undefined) {
        gridMeta.watermarkCallbackUrl = ''
      }
      if (gridMeta.renderShowGridLines === undefined) {
        gridMeta.renderShowGridLines = false
      }
      if (gridMeta.edgeMarginRightCol === undefined) {
        gridMeta.edgeMarginRightCol = false
      }
      if (gridMeta.edgeMarginTopRow === undefined) {
        gridMeta.edgeMarginTopRow = false
      }
      if (gridMeta.edgeMarginLeftCol === undefined) {
        gridMeta.edgeMarginLeftCol = false
      }
      // 旧模板仅有 edgeMarginRightCol：顶/左留白与右键功能同时插入，行为上补全标记便于预览禁用交互
      if (gridMeta.edgeMarginRightCol === true && !gridMeta.edgeMarginTopRow && !gridMeta.edgeMarginLeftCol) {
        gridMeta.edgeMarginTopRow = true
        gridMeta.edgeMarginLeftCol = true
      }
      // 旧模板没有图片上传回调地址字段，补空字符串兜底
      if (gridMeta.imageUploadCallbackUrl === undefined) {
        gridMeta.imageUploadCallbackUrl = ''
      }
    }
  }

  /**
   * 将当前 store 状态序列化为 content JSON 字符串（用于保存到后端）
   */
  function serializeContent(): string {
    return JSON.stringify({
      datasets: datasets.value,
      cells: cells.value,
      merges: merges.value,
      gridMeta: { ...gridMeta },
    })
  }

  /** 重置设计器状态（新建报表时调用） */
  function reset() {
    cells.value = {}
    merges.value = []
    datasets.value = []
    undoStack.value = []
    sel.r1 = -1; sel.c1 = -1; sel.r2 = -1; sel.c2 = -1
    Object.assign(gridMeta, {
      rowCount: INITIAL_ROW_COUNT,
      colCount: INITIAL_COL_COUNT,
      colWidths: {},
      rowHeights: {},
      columnSortable: false,
      showGridLines: false,
      renderShowGridLines: false,
      showPreviewColHeader: false,
      previewContentAlign: 'left',
      defaultRowHeight: 25,
      defaultColWidth: 100,
      freezeHeaderRows: 0,
      pageMargins: { top: 10, right: 10, bottom: 10, left: 10 },
      pagination: 'none',
      paginationRows: 30,
      watermark: '',
      watermarkDensity: 1,
      watermarkCallbackUrl: '',
      edgeMarginTopRow: false,
      edgeMarginLeftCol: false,
      edgeMarginRightCol: false,
      imageUploadCallbackUrl: '',
    })
  }

  return {
    // 状态
    cells,
    merges,
    datasets,
    gridMeta,
    sel,
    undoStack,
    // 计算
    colCount,
    rowCount,
    selBounds,
    anchorRef,
    // 选区操作
    setSelection,
    extendSelection,
    selectColumn,
    selectRow,
    selectRowRange,
    isCellSelected,
    getSelRefs,
    // 单元格操作
    ensureCell,
    cleanCell,
    syncCellValue,
    setSelectionStyle,
    toggleSelectionStyle,
    setSelectionBorder,
    toggleTextDecoration,
    // 合并操作
    findMergeAt,
    findMergeCovering,
    isCoveredByMerge,
    selectionOverlapsMerge,
    selectionHasMerge,
    // 行列操作
    insertRowsAbove,
    deleteRow,
    insertColsLeft,
    deleteCol,
    applyEdgeMarginGutters,
    undoLastOperation,
    parseCellRef,
    // 状态管理
    loadContent,
    serializeContent,
    reset,
  }
})
