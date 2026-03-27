<script setup lang="ts">
/**
 * 报表预览页
 *
 * 只读展示报表数据，完整流程：
 * 1. 通过模板 ID 请求渲染 API，获取数据矩阵、样式矩阵和合并配置
 * 2. 构建 AG Grid 列定义（含合并、样式回调）
 * 3. 处理冻结行（pinnedTopRowData）
 * 4. 支持导出为 Excel 文件
 *
 * 页边留白：由模板内空白行列实现（设计器右键「插入页边留白」）；预览不依赖外层 padding。
 * 预览区网格线由 {@code gridMeta.renderShowGridLines} 控制，与设计器「默认网格线」无关。
 *
 * 使用 AG Grid 的 Vue 3 组件直接渲染，不依赖 useDesignerStore。
 *
 * 动态水印：优先使用渲染接口返回的 {@code watermark}。若模板配置了 {@code gridMeta.watermarkCallbackUrl}，
 * 由后端在渲染时请求业务系统获得，浏览器改 URI 中的 watermark 无效；未配置回调时仍可能通过 params 传入（不安全）。
 */
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { AgGridVue } from 'ag-grid-vue3'
import { AllCommunityModule, ModuleRegistry } from 'ag-grid-community'
import type {
  ColDef,
  GridApi,
  ColSpanParams,
  RowSpanParams,
  CellClassParams,
  RowHeightParams,
} from 'ag-grid-community'
import { renderReportPaged, exportReport, getTemplate } from '@/api/report'
import { BaseButton } from '@/components/base'
import { COL_LETTERS } from '@/stores'
import type { MergeRegion, CellStyle, DatasetDefinition } from '@/types'
import { messager } from '@/composables/useMessager'
import QRCode from 'qrcode'
import bwipjs from 'bwip-js'

// 注册 AG Grid 社区版所有模块
ModuleRegistry.registerModules([AllCommunityModule])

const route = useRoute()

/** 当前预览的模板 ID */
const templateId = computed(() => (route.params.id as string) || '')

/** 报表标题 */
const templateName = ref('')
/** 加载状态 */
const loading = ref(true)
/** 错误信息 */
const errorMsg = ref('')
/** 导出加载状态 */
const exportLoading = ref(false)
const paginating = ref(false)

/** 预览页是否展示列头（A/B/C…） */
const showPreviewColHeader = ref(false)
/** 预览内容对齐方式 */
const previewContentAlign = ref<'left' | 'center' | 'right'>('left')
/** 预览内容总宽度（像素），用于对齐布局 */
const previewContentWidth = ref<number | null>(null)
/** 固定水印（来自模板） */
const fixedWatermark = ref('')
/** 动态水印（来自渲染 API 返回，可覆盖固定水印） */
const dynamicWatermark = ref('')
/** 水印密度（平铺间距倍数） */
const watermarkDensity = ref(1)
/**
 * 预览/导出渲染是否在 AG Grid 上绘制主题网格线（与 design 器的 showGridLines 独立，来自 gridMeta.renderShowGridLines）
 */
const previewRenderShowGridLines = ref(false)
/** 页边留白区：预览中禁用点击与焦点，避免出现选格视觉 */
const previewEdgeMarginTopRow = ref(false)
const previewEdgeMarginLeftCol = ref(false)
const previewEdgeMarginRightCol = ref(false)
/**
 * 为 true 时禁止横向滚动（AG Grid 在「总列宽 ≤ 视口」时常仍留出水平滚动条位）。
 * 在 gridReady / firstDataRendered / resize 时按列宽与 .ag-body-viewport 宽度同步。
 */
const previewSuppressHorizontalScroll = ref(true)
/** 用于测量 body 视口宽度，见 {@link syncPreviewHorizontalScroll} */
const previewGridHost = ref<HTMLElement | null>(null)
/** 渲染结果中与每输出行对齐的行高（px），含数据集展开后的行 */
const previewRowHeightsPx = ref<number[]>([])
/** 无逐行高度时的回退，与模板 gridMeta.defaultRowHeight 一致 */
const defaultPreviewRowHeightForGrid = ref(25)
/** 分页是否开启（来自数据集配置） */
const paginationEnabled = ref(false)
/** 分页目标数据集 key */
const paginationDatasetKey = ref('')
/** 当前页（1-based） */
const currentPage = ref(1)
/** 每页条数 */
const pageSize = ref(20)
/** 总条数 */
const totalRows = ref(0)
/** 导出范围 */
const exportScope = ref<'current' | 'all'>('current')

const watermarkText = computed(() => dynamicWatermark.value || fixedWatermark.value || '')
const totalPages = computed(() => {
  if (!paginationEnabled.value) return 1
  const total = Math.max(0, Number(totalRows.value) || 0)
  const size = Math.max(1, Number(pageSize.value) || 20)
  return Math.max(1, Math.ceil(total / size))
})
const canPrevPage = computed(() => paginationEnabled.value && currentPage.value > 1)
const canNextPage = computed(() => paginationEnabled.value && currentPage.value < totalPages.value)

/** 是否有冻结顶行（有则用 normal 布局并给容器高度，避免 autoHeight + pinned 导致 body 不显示） */
const hasPinnedRows = computed(() => pinnedTopRowData.value.length > 0)

function buildWatermarkSvgDataUrl(text: string, density: number): string {
  const safe = (text || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  const d = Math.min(4, Math.max(0.5, density || 1))
  // density 越小越密集 → pattern 尺寸越小；density 越大越稀疏 → pattern 尺寸越大
  const w = Math.round(360 * d)
  const h = Math.round(240 * d)
  const cx = Math.round(w / 2)
  const cy = Math.round(h / 2)
  // 以 SVG 生成平铺水印，避免引入第三方库；rotate(-30) 接近常见水印角度
  const svg = `
<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}">
  <defs>
    <pattern id="p" patternUnits="userSpaceOnUse" width="${w}" height="${h}">
      <!-- 以平铺块中心为锚点旋转，避免文字被 tile 边缘裁切（表现为“第一个字被挡住/缺一块”） -->
      <text
        x="${cx}"
        y="${cy}"
        text-anchor="middle"
        dominant-baseline="middle"
        fill="rgba(17,24,39,0.12)"
        font-size="16"
        font-family="Microsoft YaHei, Arial"
        transform="rotate(-30 ${cx} ${cy})"
      >
        ${safe}
      </text>
    </pattern>
  </defs>
  <rect width="100%" height="100%" fill="url(#p)"/>
</svg>`
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`
}

const watermarkStyle = computed(() => {
  const t = watermarkText.value.trim()
  if (!t) return {}
  return {
    backgroundImage: `url("${buildWatermarkSvgDataUrl(t, watermarkDensity.value)}")`,
    backgroundRepeat: 'repeat',
  } as Record<string, string>
})

/** AG Grid 列定义 */
const columnDefs = ref<ColDef[]>([])
/** AG Grid 行数据 */
const rowData = ref<Record<string, unknown>[]>([])
/** 冻结顶部行数据 */
const pinnedTopRowData = ref<Record<string, unknown>[]>([])
/**
 * 单元格类型矩阵（从模板 cells 解析，row x col → type 字符串）。
 * 用于预览时识别 image 类型单元格并渲染 <img>，而非显示 URL 字符串。
 */
const cellTypesSnapshot = ref<(string | null)[][]>([])
/**
 * 模板原始单元格存在性矩阵（row x col -> 是否在模板 cells 中显式定义）。
 * 用于区分“模板原始格子”和“渲染阶段展开出来的新格子”。
 */
const templateCellExistsSnapshot = ref<boolean[][]>([])
/**
 * 列类型兜底（按模板列推断）：
 * 当渲染后的行号超出模板行号（如数据集展开新增行）导致拿不到精确 type 时，
 * 若该列在模板中出现过 barcode/qrcode/image，则按列继承该类型继续渲染。
 */
const columnTypeFallback = ref<Record<number, 'image' | 'barcode' | 'qrcode'>>({})
/** 冻结行数（用于 colSpan/rowSpan 中把 body 的 rowIndex 转为全局行号） */
const freezeHeaderRowsRef = ref(0)
/** 渲染使用的合并区域（传入列定义 colSpan/rowSpan 回调使用） */
let mergesSnapshot: MergeRegion[] = []

const gridApi = ref<GridApi | null>(null)
/** 图片大图预览弹窗显隐 */
const imagePreviewVisible = ref(false)
/** 大图预览地址 */
const imagePreviewUrl = ref('')

/**
 * 打开图片大图预览。
 *
 * @param url 图片地址
 */
function openImagePreview(url: string) {
  imagePreviewUrl.value = url
  imagePreviewVisible.value = true
}

/**
 * 关闭图片大图预览并清空 URL，避免保留上次内容。
 */
function closeImagePreview() {
  imagePreviewVisible.value = false
  imagePreviewUrl.value = ''
}

/**
 * 根据「列总宽」与表格 body 视口宽度决定是否允许横向滚动；仅在内容超出时出现横向滚动条。
 */
function syncPreviewHorizontalScroll() {
  void nextTick(() => {
    requestAnimationFrame(() => {
      const sum = previewContentWidth.value ?? 0
      const host = previewGridHost.value
      const bodyVp = host?.querySelector('.ag-body-viewport') as HTMLElement | null
      const vw = bodyVp?.clientWidth ?? 0
      // 数像素容差，避免舍入误差导致误判
      previewSuppressHorizontalScroll.value = sum > 0 && vw > 0 ? sum <= vw + 4 : true
    })
  })
}

function onPreviewResize() {
  syncPreviewHorizontalScroll()
}

function onGridReady(params: { api: GridApi }) {
  gridApi.value = params.api
  syncPreviewHorizontalScroll()
}

function onFirstDataRendered() {
  syncPreviewHorizontalScroll()
}

onMounted(() => {
  window.addEventListener('resize', onPreviewResize)
})
onUnmounted(() => {
  window.removeEventListener('resize', onPreviewResize)
})

/**
 * 判断字符串是否“看起来像图片 URL”。
 *
 * 说明：
 * - 解决数据行展开后只有首行保留 template type，后续行仅剩 URL 文本的问题；
 * - 当模板类型矩阵缺失时，允许通过 URL 后缀兜底识别为图片；
 * - 为避免误判，要求是 http/https/data:image 协议，且常见图片后缀。
 */
function isLikelyImageUrl(value: string): boolean {
  const v = value.trim()
  if (!v) return false
  if (v.startsWith('data:image/')) return true
  if (!/^https?:\/\//i.test(v)) return false
  return /\.(png|jpe?g|gif|webp|bmp|svg)(\?.*)?$/i.test(v)
}

/**
 * 生成二维码 data URL（前端 qrcode 库）。
 *
 * @param text 二维码文本
 * @returns data:image/png;base64,...
 */
async function buildQrcodeDataUrl(text: string): Promise<string> {
  const canvas = document.createElement('canvas')
  await QRCode.toCanvas(canvas, text, {
    errorCorrectionLevel: 'M',
    margin: 1,
    width: 180,
  })
  return canvas.toDataURL('image/png')
}

/**
 * 生成条形码 data URL（前端 bwip-js 库）。
 *
 * @param text 条形码文本
 * @returns data:image/png;base64,...
 */
async function buildBarcodeDataUrl(text: string): Promise<string> {
  const canvas = document.createElement('canvas')
  // bwip-js 在浏览器侧渲染到 canvas
  bwipjs.toCanvas(canvas, {
    bcid: 'code128',
    text,
    scale: 2,
    height: 12,
    includetext: false,
    paddingwidth: 2,
    paddingheight: 2,
  })
  return canvas.toDataURL('image/png')
}

// ============================================================
// 初始化：加载模板信息 + 渲染数据
// ============================================================

async function loadPreviewPage() {
  if (!templateId.value) {
    errorMsg.value = '缺少有效的模板 ID'
    loading.value = false
    return
  }

  try {
    // 运行时参数：透传 URL query 到渲染接口（数据集变量、业务筛选等）。
    // 若模板未配置 watermarkCallbackUrl，params.watermark 仍可影响动态水印；配置回调后仅服务端可信。
    const runtimeParams = route.query as unknown as Record<string, unknown>
    // 并行请求模板信息和渲染结果，减少等待时间
    const templateRes = await getTemplate(templateId.value)

    templateName.value = templateRes.data.name || '报表'

    // 从模板 content 解析 gridMeta：冻结行、列宽（与设计器一致，避免预览页 flex 均分整行）
    let freezeHeaderRows = 0
    /** 模板声明的列数（预览列数硬上限，防止超过设计器格子数） */
    let templateGridColCount = 0
    /** 是否在最右侧预留「单列」留白（见 gridMeta.edgeMarginRightCol） */
    let templateEdgeMarginRightCol = false
    /** 列字母 -> 像素宽度（与设计器 gridMeta.colWidths 一致） */
    let colWidthsFromMeta: Record<string, number> = {}
    /** 未单独设置列宽时的默认宽度 */
    let defaultColWidthFromMeta = 100
    let datasetsFromTemplate: DatasetDefinition[] = []
    if (templateRes.data.content) {
      try {
        const cfg = JSON.parse(templateRes.data.content) as {
          datasets?: DatasetDefinition[]
          gridMeta?: {
            rowCount?: number
            colCount?: number
            freezeHeaderRows?: number
            colWidths?: Record<string, number>
            defaultColWidth?: number
            defaultRowHeight?: number
            showPreviewColHeader?: boolean
            previewContentAlign?: 'left' | 'center' | 'right'
            watermark?: string
            watermarkDensity?: number
            renderShowGridLines?: boolean
            edgeMarginRightCol?: boolean
            edgeMarginTopRow?: boolean
            edgeMarginLeftCol?: boolean
          }
        }
        datasetsFromTemplate = Array.isArray(cfg.datasets) ? cfg.datasets : []
        const pagedDs = datasetsFromTemplate.find((d) => d.pagination?.enabled)
        if (pagedDs) {
          paginationEnabled.value = true
          paginationDatasetKey.value = pagedDs.key
          if (!paginating.value) {
            pageSize.value = Math.max(1, Number(pagedDs.pagination?.defaultPageSize) || 20)
          }
        } else {
          paginationEnabled.value = false
          paginationDatasetKey.value = ''
        }
        const gm = cfg.gridMeta
        if (gm) {
          if (typeof gm.colCount === 'number' && gm.colCount > 0) {
            templateGridColCount = gm.colCount
          }
          let topM = gm.edgeMarginTopRow === true
          let leftM = gm.edgeMarginLeftCol === true
          const rightM = gm.edgeMarginRightCol === true
          // 与 loadContent 一致：旧 JSON 仅存 edgeMarginRightCol 时补顶/左标记
          if (rightM && !topM && !leftM) {
            topM = true
            leftM = true
          }
          previewEdgeMarginTopRow.value = topM
          previewEdgeMarginLeftCol.value = leftM
          previewEdgeMarginRightCol.value = rightM
          templateEdgeMarginRightCol = rightM
          freezeHeaderRows = gm.freezeHeaderRows || 0
          freezeHeaderRowsRef.value = freezeHeaderRows
          colWidthsFromMeta = gm.colWidths && typeof gm.colWidths === 'object' ? gm.colWidths : {}
          if (typeof gm.defaultColWidth === 'number' && gm.defaultColWidth > 0) {
            defaultColWidthFromMeta = gm.defaultColWidth
          }
          if (typeof gm.defaultRowHeight === 'number' && gm.defaultRowHeight > 0) {
            defaultPreviewRowHeightForGrid.value = gm.defaultRowHeight
          }
          showPreviewColHeader.value = !!gm.showPreviewColHeader
          previewContentAlign.value = gm.previewContentAlign || 'left'
          fixedWatermark.value = (gm.watermark || '').trim()
          watermarkDensity.value = typeof gm.watermarkDensity === 'number' ? gm.watermarkDensity : 1
          previewRenderShowGridLines.value = gm.renderShowGridLines === true
        }
      } catch (_) {
        // 忽略解析失败
      }
    }

    const renderRes = await renderReportPaged(
      templateId.value,
      runtimeParams,
      paginationEnabled.value ? currentPage.value : 1,
      paginationEnabled.value ? pageSize.value : 20,
      paginationEnabled.value ? paginationDatasetKey.value : undefined,
    )

    const {
      cells,
      styles,
      merges,
      colCount: backendColCount,
      watermark,
      rowHeightsPx,
      paginationByDataset,
    } = renderRes.data
    if (paginationEnabled.value && paginationDatasetKey.value && paginationByDataset) {
      const m = paginationByDataset[paginationDatasetKey.value]
      if (m) {
        totalRows.value = Number(m.total ?? 0)
        if (m.currentPage && Number(m.currentPage) > 0) {
          currentPage.value = Number(m.currentPage)
        }
        if (m.pageSize && Number(m.pageSize) > 0) {
          pageSize.value = Number(m.pageSize)
        }
      }
    } else if (!paginationEnabled.value) {
      totalRows.value = allRowsCount(cells)
    }
    mergesSnapshot = merges || []
    dynamicWatermark.value = (watermark || '').trim()
    previewRowHeightsPx.value = Array.isArray(rowHeightsPx) ? rowHeightsPx : []

    // 从模板 cells 构建类型矩阵（用于预览时识别 image 单元格）
    if (templateRes.data.content) {
      try {
        const tplContent = JSON.parse(templateRes.data.content) as {
          gridMeta?: { rowCount?: number; colCount?: number }
          cells?: Record<string, { type?: string; value?: string }>
        }
        const tplCells = tplContent.cells || {}
        const tplRowCount = tplContent.gridMeta?.rowCount ?? cells.length
        const tplColCount = tplContent.gridMeta?.colCount ?? 26
        // 初始化类型矩阵为 null
        const typeMatrix: (string | null)[][] = Array.from({ length: tplRowCount }, () =>
          new Array<string | null>(tplColCount).fill(null),
        )
        const existsMatrix: boolean[][] = Array.from({ length: tplRowCount }, () =>
          new Array<boolean>(tplColCount).fill(false),
        )
        // 解析 "A1", "B2"... cellRef 并填充类型
        for (const [ref, data] of Object.entries(tplCells)) {
          // 解析列字母与行号（简单解析，仅支持单字母和双字母列）
          const match = ref.match(/^([A-Z]+)(\d+)$/)
          if (!match) continue
          const colLetterIdx = COL_LETTERS.indexOf(match[1])
          const rowIdx = parseInt(match[2], 10) - 1
          if (colLetterIdx < 0 || rowIdx < 0 || rowIdx >= tplRowCount || colLetterIdx >= tplColCount) continue
          existsMatrix[rowIdx][colLetterIdx] = true
          if (data?.type && data.type !== 'text') {
            typeMatrix[rowIdx][colLetterIdx] = data.type
          }
        }
        cellTypesSnapshot.value = typeMatrix
        templateCellExistsSnapshot.value = existsMatrix
        // 构建列级类型兜底映射（仅保留预览需要的三类）
        const colTypeMap: Record<number, 'image' | 'barcode' | 'qrcode'> = {}
        for (const row of typeMatrix) {
          for (let c = 0; c < row.length; c++) {
            const t = row[c]
            if (t === 'image' || t === 'barcode' || t === 'qrcode') {
              // 同列若存在多种类型，优先保留先出现的（通常模板同列语义固定）
              if (!colTypeMap[c]) colTypeMap[c] = t
            }
          }
        }
        columnTypeFallback.value = colTypeMap
      } catch (_) {
        // 忽略解析失败，继续使用默认文本渲染
      }
    }

    if (!cells || cells.length === 0) {
      errorMsg.value = '报表无数据'
      loading.value = false
      return
    }

    /**
     * 预览列数：以「单元格非空 + 合并右边界」推断为主；若模板勾了页边留白最右空列（edgeMarginRightCol），
     * 仅在推断结果上多留 **一列**，避免此前用整表 colCount/行长 把右侧所有未用列都画出来。
     */
    const inferredCols = inferEffectiveColCount(
      cells,
      mergesSnapshot,
      Number(backendColCount) || 0,
    )
    let colCount = inferredCols + (templateEdgeMarginRightCol ? 1 : 0)
    if (templateGridColCount > 0) {
      colCount = Math.min(colCount, templateGridColCount)
    }
    colCount = Math.max(1, colCount)
    // 与设计器相同：A、B…Z、AA…（仅用前 colCount 列）
    const colLetters = COL_LETTERS.slice(0, colCount)

    // 构建列定义（捕获 idx 到回调闭包中）
    columnDefs.value = colLetters.map((letter: string, idx: number) => {
      const colIdx = idx
      const w = colWidthsFromMeta[letter] ?? defaultColWidthFromMeta
      const colDef: ColDef = {
        headerName: letter,
        field: `col_${colIdx}`,
        // 使用设计器保存的像素宽度，不用 flex 均分视口
        width: Math.max(30, w),
        minWidth: 30,
        colSpan: (params: ColSpanParams) => {
          const actualRow = getActualRowIndex(params.node, freezeHeaderRowsRef.value)
          const m = findMergeAt(mergesSnapshot, actualRow, colIdx)
          return m ? m.colSpan : 1
        },
        rowSpan: (params: RowSpanParams) => {
          const actualRow = getActualRowIndex(params.node, freezeHeaderRowsRef.value)
          const m = findMergeAt(mergesSnapshot, actualRow, colIdx)
          return m ? m.rowSpan : 1
        },
        cellStyle: (params: CellClassParams) => {
          const rs = (params.data as { _styles?: (CellStyle | null)[] })._styles
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          return (rs && rs[colIdx]) ? (rs[colIdx] as any) : null
        },
        /**
         * 顶行 / 最左 / 最右留白格加统一 class，配合 CSS 禁用指针事件，避免预览出现「选中单元格」观感。
         */
        cellClass: (params: CellClassParams) => {
          const ar = getActualRowIndex(params.node, freezeHeaderRowsRef.value)
          const cls: string[] = []
          if (previewEdgeMarginTopRow.value && ar === 0) cls.push('er-preview-margin-gutter')
          if (previewEdgeMarginLeftCol.value && colIdx === 0) cls.push('er-preview-margin-gutter')
          if (previewEdgeMarginRightCol.value && colIdx === colLetters.length - 1) {
            cls.push('er-preview-margin-gutter')
          }
          return cls.length ? cls.join(' ') : ''
        },
        /**
         * 图片单元格渲染：检查模板类型矩阵，若为 image 且值为 URL，渲染 <img>；否则默认文本。
         */
        cellRenderer: (params: CellClassParams) => {
          const ar = getActualRowIndex(params.node, freezeHeaderRowsRef.value)
          const typeMatrix = cellTypesSnapshot.value
          const explicitType =
            typeMatrix && typeMatrix[ar] ? typeMatrix[ar][colIdx] : null
          const existsMatrix = templateCellExistsSnapshot.value
          // 仅对“模板未显式定义”的位置（通常是数据展开新增行）启用列级兜底
          const isTemplateOriginCell = !!(existsMatrix && existsMatrix[ar] && existsMatrix[ar][colIdx])
          const cellType = explicitType || (!isTemplateOriginCell ? columnTypeFallback.value[colIdx] : null) || null
          const val = params.value as string | null | undefined
          const shouldRenderImage = !!val && (cellType === 'image' || isLikelyImageUrl(val))
          if (shouldRenderImage) {
            const wrapper = document.createElement('div')
            wrapper.style.cssText =
              'display:flex;align-items:center;justify-content:center;width:100%;height:100%;overflow:hidden;'
            const img = document.createElement('img')
            img.src = val
            img.style.cssText = 'max-width:100%;max-height:100%;object-fit:contain;'
            img.alt = '图片'
            // 双击图片：页面内弹出大图预览
            img.ondblclick = (evt: MouseEvent) => {
              evt.preventDefault()
              evt.stopPropagation()
              openImagePreview(val)
            }
            img.onerror = () => {
              wrapper.textContent = '[图片加载失败]'
            }
            wrapper.appendChild(img)
            return wrapper
          }
          // 条形码/二维码：仅在预览渲染，不在设计器渲染
          if (!!val && (cellType === 'barcode' || cellType === 'qrcode')) {
            const wrapper = document.createElement('div')
            wrapper.style.cssText =
              'display:flex;align-items:center;justify-content:center;width:100%;height:100%;overflow:hidden;'
            const img = document.createElement('img')
            img.alt = cellType === 'barcode' ? '条形码' : '二维码'
            img.style.cssText = 'max-width:100%;max-height:100%;object-fit:contain;'
            wrapper.appendChild(img)

            const render = cellType === 'barcode' ? buildBarcodeDataUrl : buildQrcodeDataUrl
            void render(val).then((dataUrl) => {
              img.src = dataUrl
              // 双击码图同样支持大图预览
              img.ondblclick = (evt: MouseEvent) => {
                evt.preventDefault()
                evt.stopPropagation()
                openImagePreview(dataUrl)
              }
            }).catch(() => {
              wrapper.textContent = cellType === 'barcode' ? '[条形码渲染失败]' : '[二维码渲染失败]'
            })
            return wrapper
          }
          return val ?? ''
        },
      }
      return colDef
    })
    // 计算预览内容总宽度，用于左/中/右对齐布局（简单按列宽求和）
    previewContentWidth.value = columnDefs.value.reduce((sum, d) => sum + (typeof d.width === 'number' ? d.width + 3 : 0), 0)

    // 构建行数据（将二维数组转换为 AG Grid 行对象格式）
    const allRows: Record<string, unknown>[] = []
    for (let r = 0; r < cells.length; r++) {
      const row: Record<string, unknown> = {
        _styles: (styles[r] || []).slice(0, colCount),
      }
      for (let c = 0; c < colCount; c++) {
        const v = cells[r]?.[c]
        row[`col_${c}`] = v === undefined || v === null ? '' : v
      }
      allRows.push(row)
    }

    // 分离冻结行（顶部固定） 和 普通行
    const safeFreeze = Math.min(freezeHeaderRows, allRows.length)
    pinnedTopRowData.value = allRows.slice(0, safeFreeze)
    rowData.value = allRows.slice(safeFreeze)
  } catch (e) {
    errorMsg.value = '加载失败：' + (e as Error).message
  } finally {
    loading.value = false
    paginating.value = false
  }
}

onMounted(async () => {
  await loadPreviewPage()
})

// ============================================================
// 导出 Excel
// ============================================================

async function doExport() {
  exportLoading.value = true
  try {
    const res = await exportReport({
      templateId: templateId.value,
      queryParams: {
        ...(route.query as unknown as Record<string, unknown>),
        page: currentPage.value,
        pageSize: pageSize.value,
        datasetKey: paginationDatasetKey.value,
      },
      format: 'xlsx',
      exportScope: exportScope.value,
    })
    // 从响应中构造下载链接
    const blob = new Blob([res.data], {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${templateName.value || 'report'}.xlsx`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  } catch (e) {
    messager.danger('导出失败：' + (e as Error).message)
  } finally {
    exportLoading.value = false
  }
}

// ============================================================
// 导出 PDF
// ============================================================

async function doExportPdf() {
  exportLoading.value = true
  try {
    const res = await exportReport({
      templateId: templateId.value,
      queryParams: {
        ...(route.query as unknown as Record<string, unknown>),
        page: currentPage.value,
        pageSize: pageSize.value,
        datasetKey: paginationDatasetKey.value,
      },
      format: 'pdf',
      exportScope: exportScope.value,
    })
    const blob = new Blob([res.data], { type: 'application/pdf' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${templateName.value || 'report'}.pdf`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  } catch (e) {
    messager.danger('导出失败：' + (e as Error).message)
  } finally {
    exportLoading.value = false
  }
}

// ============================================================
// 工具函数
// ============================================================

/**
 * 将 AG Grid 的 node.rowIndex 转为全局行号（与 mergesSnapshot / 数据矩阵一致）。
 * 冻结顶行：rowPinned === 'top' 时 rowIndex 即全局行号 0..freeze-1；
 * 普通 body 行：rowIndex 从 0 起，全局行号 = freezeHeaderRows + rowIndex。
 */
function getActualRowIndex(
  node: { rowPinned?: string | null; rowIndex?: number | null } | null | undefined,
  freezeHeaderRows: number,
): number {
  if (!node) return 0
  const idx = node.rowIndex ?? 0
  if (node.rowPinned === 'top') return idx
  return freezeHeaderRows + idx
}

/** 预览表行高：与渲染矩阵、冻结行拆分一致 */
function previewGetRowHeight(params: RowHeightParams): number | undefined {
  const ar = getActualRowIndex(params.node, freezeHeaderRowsRef.value)
  const arr = previewRowHeightsPx.value
  if (arr.length > 0 && ar >= 0 && ar < arr.length) {
    const h = arr[ar]
    if (h != null && h > 0) return h
  }
  const d = defaultPreviewRowHeightForGrid.value
  return d > 0 ? d : undefined
}

/** 查找指定位置的合并区域（仅匹配左上角锚点） */
function findMergeAt(merges: MergeRegion[], row: number, col: number): MergeRegion | null {
  return merges.find((m) => m.row === row && m.col === col) || null
}

/**
 * 推断有效列数（仅根据单元格非空内容与合并右边界扫描）。
 *
 * 预览页最终会再与渲染接口返回的 colCount、模板 gridMeta.colCount、各行实际长度取最大值，
 * 以保留「无内容」的右侧留白列（否则与本函数结果冲突）。
 */
function inferEffectiveColCount(
  cells: (string | null)[][],
  merges: MergeRegion[],
  fallback: number,
): number {
  let lastUsed = -1

  for (const row of cells) {
    for (let c = (row?.length || 0) - 1; c >= 0; c--) {
      const v = row[c]
      if (v !== null && v !== undefined && String(v).trim() !== '') {
        if (c > lastUsed) lastUsed = c
        break
      }
    }
  }

  for (const m of merges) {
    const right = (m.col || 0) + (m.colSpan || 1) - 1
    if (right > lastUsed) lastUsed = right
  }

  if (lastUsed >= 0) return lastUsed + 1
  if (fallback > 0) return fallback
  return 1
}

function allRowsCount(cells: (string | null)[][]): number {
  return Array.isArray(cells) ? cells.length : 0
}

async function prevPage() {
  if (!canPrevPage.value) return
  currentPage.value -= 1
  loading.value = true
  paginating.value = true
  await loadPreviewPage()
}

async function nextPage() {
  if (!canNextPage.value) return
  currentPage.value += 1
  loading.value = true
  paginating.value = true
  await loadPreviewPage()
}

async function onPageSizeChange() {
  if (!paginationEnabled.value) return
  currentPage.value = 1
  loading.value = true
  paginating.value = true
  await loadPreviewPage()
}
</script>

<template>
  <div class="flex flex-col h-full bg-gray-50 overflow-hidden">
    <!-- 顶部工具栏 -->
    <header class="flex items-center gap-3 px-4 py-2.5 bg-white border-b border-gray-200 shrink-0 h-11">
      <!-- 报表标题 -->
      <h1 class="text-base font-semibold text-gray-800 truncate">
        {{ templateName || '报表预览' }}
      </h1>
      <div class="flex-1" />
      <div v-if="paginationEnabled" class="flex items-center gap-2 text-[12px] text-gray-700">
        <span>第 {{ currentPage }} / {{ totalPages }} 页</span>
        <span>共 {{ totalRows }} 条</span>
        <BaseButton variant="default" size="sm" :disabled="loading || !canPrevPage" @click="prevPage">
          上一页
        </BaseButton>
        <BaseButton variant="default" size="sm" :disabled="loading || !canNextPage" @click="nextPage">
          下一页
        </BaseButton>
        <label class="text-gray-500">每页</label>
        <select
          v-model.number="pageSize"
          class="border border-gray-300 rounded px-1.5 py-0.5 bg-white"
          :disabled="loading"
          @change="onPageSizeChange"
        >
          <option :value="10">10</option>
          <option :value="20">20</option>
          <option :value="50">50</option>
          <option :value="100">100</option>
        </select>
        <label class="text-gray-500">导出</label>
        <select v-model="exportScope" class="border border-gray-300 rounded px-1.5 py-0.5 bg-white">
          <option value="current">当前页</option>
          <option value="all">全部</option>
        </select>
      </div>
      <!-- 导出按钮 -->
      <BaseButton
        variant="primary"
        :loading="exportLoading"
        :disabled="loading || !!errorMsg"
        @click="doExport"
      >
        导出 Excel
      </BaseButton>
      <BaseButton
        variant="default"
        :loading="exportLoading"
        :disabled="loading || !!errorMsg"
        @click="doExportPdf"
      >
        导出 PDF
      </BaseButton>
    </header>

    <!-- 主内容区 -->
    <main class="flex-1 min-h-0 overflow-hidden">
      <!-- 加载中 -->
      <div
        v-if="loading"
        class="h-full flex items-center justify-center text-gray-400 text-sm"
      >
        加载中...
      </div>

      <!-- 错误状态 -->
      <div
        v-else-if="errorMsg"
        class="h-full flex items-center justify-center text-red-500 text-sm"
      >
        {{ errorMsg }}
      </div>

      <!-- AG Grid 预览（只读）；页边留白由模板内空白行列实现，见设计器右键「插入页边留白」 -->
      <div v-else class="relative w-full h-full overflow-y-auto overflow-x-hidden">
        <!-- 水印层（不影响交互） -->
        <div
          v-if="watermarkText"
          class="pointer-events-none absolute inset-0 z-10"
          :style="watermarkStyle"
        />

        <!-- 内容对齐容器：按列总宽度设置固定宽度，支持左/中/右对齐；有冻结行时占满高度 -->
        <div
          ref="previewGridHost"
          class="relative z-0 w-full min-h-0"
          :class="[
            previewContentAlign === 'center' ? 'mx-auto' : previewContentAlign === 'right' ? 'ml-auto' : '',
            hasPinnedRows ? 'h-full' : '',
          ]"
          :style="[previewContentWidth ? { width: previewContentWidth + 'px' } : {}]"
        >
          <AgGridVue
            class="ag-theme-alpine w-full er-preview-readonly"
            :class="{
              'er-preview-no-col-header': !showPreviewColHeader,
              'er-preview-grid-with-pinned': hasPinnedRows,
              'er-grid-lines': previewRenderShowGridLines,
              'er-grid-no-lines': !previewRenderShowGridLines,
            }"
            :columnDefs="columnDefs"
            :rowData="rowData"
            :pinnedTopRowData="pinnedTopRowData"
            :defaultColDef="{ resizable: true, sortable: true }"
            :suppressRowTransform="true"
            :getRowHeight="previewGetRowHeight"
            :suppressHorizontalScroll="previewSuppressHorizontalScroll"
            :headerHeight="showPreviewColHeader ? undefined : 0"
            :domLayout="
              hasPinnedRows
                ? undefined
                : rowData.length < 30 && previewRowHeightsPx.length === 0
                  ? 'autoHeight'
                  : undefined
            "
            @grid-ready="onGridReady"
            @first-data-rendered="onFirstDataRendered"
          />
        </div>
      </div>
    </main>

    <!-- 双击图片后的大图预览弹窗 -->
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
  </div>
</template>
