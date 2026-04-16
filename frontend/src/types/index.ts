/**
 * 全局 TypeScript 类型定义
 *
 * 集中定义前后端共用的数据结构，
 * 与后端 DTO 保持字段一致，便于接口对接和维护。
 */

/* =========================================================
 * 报表模板相关类型
 * ========================================================= */

/** 报表模板简要信息（列表展示用） */
export interface ReportTemplate {
  id: string
  name: string
  description: string
  content?: string
  createdAt?: string
  updatedAt?: string
}

/** 报表模板列表分页响应 */
export interface ReportTemplatePageResponse {
  list: ReportTemplate[]
  total: number
  page: number
  size: number
}

/** 创建/更新报表模板请求体 */
export interface ReportTemplateDto {
  name: string
  description: string
  content: string
}

/* =========================================================
 * 数据源相关类型
 * ========================================================= */

/** 数据源配置 */
export interface DataSourceConfig {
  /** 数据源主键，后端使用 UUID 字符串 */
  id: string
  name: string
  type: string
  url?: string
  username?: string
  driverClass?: string
  /** 密码字段：后端列表/详情接口不返回此字段；仅在新建/更新时按需提交 */
  password?: string
}

/** 数据源测试请求 */
export interface DataSourceTestRequest {
  type: 'SQL' | 'API'
  /** 数据源主键，后端使用 UUID 字符串 */
  dataSourceId?: string
  sql?: string
  apiUrl?: string
  apiMethod?: 'GET' | 'POST'
  /** API 返回中业务数据列表字段路径（点路径），如 data.records */
  apiRecordsPath?: string
  params?: Record<string, unknown>
}

/** 数据源测试响应 */
export interface DataSourceTestResponse {
  fields: string[]
  previewRows?: unknown[][]
}

/* =========================================================
 * 设计器内部状态类型
 * ========================================================= */

/** 单元格样式（与 CSS 属性对应） */
export interface CellStyle {
  fontWeight?: string
  fontStyle?: string
  textDecoration?: string
  fontFamily?: string
  fontSize?: string
  color?: string
  backgroundColor?: string
  textAlign?: string
  borderTop?: string
  borderRight?: string
  borderBottom?: string
  borderLeft?: string
  [key: string]: string | undefined
}

/** 单元格数据 */
export interface CellData {
  value?: string
  style?: CellStyle
  type?: 'text' | 'number' | 'image' | 'barcode' | 'qrcode'
  /**
   * 仅对 type=image 有意义。与后端 Excel 导出时 POI {@code ClientAnchor} 行为对应。
   * true（默认）：{@code AnchorType.MOVE_AND_RESIZE}，图片随单元格移动并随单元格缩放；
   * false：{@code AnchorType.MOVE_DONT_RESIZE}，随单元格移动但不随单元格缩放。
   */
  embedImageInCell?: boolean
}

/** 合并区域定义 */
export interface MergeRegion {
  row: number
  col: number
  rowSpan: number
  colSpan: number
}

/** 网格元数据（报表全局设置） */
export interface GridMeta {
  rowCount: number
  colCount: number
  colWidths: Record<string, number>
  rowHeights: Record<string, number>
  columnSortable: boolean
  /** 设计器编辑区是否显示 AG Grid 主题网格线（方便对齐线稿） */
  showGridLines: boolean
  /**
   * 预览页、导出等渲染结果是否绘制网格线。
   * false 时预览表格仅保留单元格自有边框，适合出稿样式；与设计器 {@link showGridLines} 独立。
   */
  renderShowGridLines?: boolean
  /** 预览页是否展示列头（A/B/C…这一行）；默认不展示 */
  showPreviewColHeader?: boolean
  /** 预览页内容对齐方式（按内容总宽对齐到容器内） */
  previewContentAlign?: 'left' | 'center' | 'right'
  /**
   * 水印密度（平铺间距倍数）
   *
   * - 值越大：间距越大，水印越稀疏
   * - 值越小：间距越小，水印越密集
   */
  watermarkDensity?: number
  defaultRowHeight: number
  defaultColWidth: number
  freezeHeaderRows: number
  pageMargins: { top: number; right: number; bottom: number; left: number }
  pagination: 'none' | 'byRows'
  paginationRows: number
  watermark: string
  /**
   * 动态水印服务端回调地址（GET）。
   * 预览/导出渲染时由报表后端请求该 URL，将 templateId 与运行时 params（不含 watermark）作为 query 透传；
   * 业务返回 JSON：`{"watermark":"文案"}`。配置后浏览器无法通过 URI 伪造水印。
   */
  watermarkCallbackUrl?: string
  /**
   * 是否通过「插入页边留白」在表格最右侧增加了专用于留白的空白列。
   * 预览列数在「内容+合并推断」基础上仅多保留这一列，避免把模板里其余未用列全部拉出来渲染。
   */
  edgeMarginRightCol?: boolean
  /** 页边留白：顶行空白（与 insert 页边留白一并写入） */
  edgeMarginTopRow?: boolean
  /** 页边留白：最左空白列 */
  edgeMarginLeftCol?: boolean
  /**
   * 模板级图片上传回调地址。
   *
   * 上传图片时，报表工具后端将文件流以 multipart 形式 POST 到该地址（参数名为 imgFile），
   * 业务方返回图片 URL（纯字符串或 JSON：{url: '...'}）。
   * 未配置时，设计器中点击上传图片会弹窗提示先配置此地址。
   */
  imageUploadCallbackUrl?: string
}

/** 数据集定义（设计器中配置，用于拖拽生成变量） */
export interface DatasetDefinition {
  key: string
  type: 'SQL' | 'API'
  fields: string[]
  /** SQL 类型：数据源 ID（UUID 字符串，与后端一致） */
  dataSourceId?: string
  /** SQL 类型：查询语句 */
  sql?: string
  /** API 类型：请求地址 */
  url?: string
  /** API 类型：请求方法 */
  method?: 'GET' | 'POST'
  /** 数据集分页配置（按数据集独立控制） */
  pagination?: DatasetPaginationConfig
}

/** 数据集分页配置：兼容 SQL/API 两类数据源 */
export interface DatasetPaginationConfig {
  /** 是否开启分页 */
  enabled: boolean
  /** 默认每页条数（预览页初始值，默认 20） */
  defaultPageSize?: number
  /** 请求参数映射（传给 SQL/API） */
  request?: {
    /** 页码参数名（1-based） */
    pageParam?: string
    /** 每页条数参数名 */
    pageSizeParam?: string
    /** 偏移量参数名（0-based） */
    offsetParam?: string
    /** 限制条数参数名 */
    limitParam?: string
  }
  /** API 响应字段路径映射（点路径，如 data.records） */
  response?: {
    /** 数据列表字段路径 */
    recordsPath?: string
    /** 总条数字段路径 */
    totalPath?: string
    /** 当前页字段路径 */
    currentPagePath?: string
    /** 每页条数字段路径 */
    pageSizePath?: string
    /** 是否还有下一页字段路径（可选） */
    hasMorePath?: string
  }
}

/** 完整的报表模板内容（保存到 content 字段的 JSON 结构） */
export interface ReportContent {
  datasets: DatasetDefinition[]
  cells: Record<string, CellData>
  merges: MergeRegion[]
  gridMeta: GridMeta
}

/* =========================================================
 * 预览/渲染相关类型
 * ========================================================= */

/** 渲染 API 响应（后端解析变量后的结果） */
export interface ReportRenderResponse {
  cells: (string | null)[][]
  styles: (CellStyle | null)[][]
  merges: MergeRegion[]
  colCount: number
  /**
   * 与 cells 每一输出行对齐的行高（px）；变量模板行展开出的多行共用该模板行在行号菜单里配置的高度。
   */
  rowHeightsPx?: number[]
  freezeHeaderRows?: number
  /** 动态水印文本：后端可在渲染时返回，用于覆盖模板固定水印 */
  watermark?: string
  /** 渲染阶段回传的数据集分页元信息（key=datasetKey） */
  paginationByDataset?: Record<string, DatasetPageMeta>
}

/** 数据集分页元信息 */
export interface DatasetPageMeta {
  total?: number
  currentPage?: number
  pageSize?: number
  hasMore?: boolean
}

/** 导出请求 */
export interface ReportExportRequest {
  templateId: string
  queryParams?: Record<string, unknown>
  page?: number
  pageSize?: number
  datasetKey?: string
  format?: 'xlsx' | 'pdf'
  /** 导出范围：当前页 / 全部 */
  exportScope?: 'current' | 'all'
}

/* =========================================================
 * 通用 API 响应类型
 * ========================================================= */

/** 通用成功/失败响应包装 */
export interface ApiResponse<T = unknown> {
  code?: number
  message?: string
  data?: T
}
