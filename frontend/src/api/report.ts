/**
 * 报表模板 API 封装
 *
 * 对应后端 ApiReportController 的所有接口：
 * - 模板 CRUD（创建、读取、更新、删除）
 * - 数据集查询
 * - 报表渲染（变量替换 + 行展开）
 * - 报表导出（Excel）
 */
import http from './http'
import type {
  ReportTemplate,
  ReportTemplatePageResponse,
  ReportTemplateDto,
  ReportRenderResponse,
  ReportExportRequest,
} from '@/types'

/**
 * 分页查询报表模板列表
 * @param page 页码（从 0 开始）
 * @param size 每页条数
 */
export function getTemplateList(page = 0, size = 50) {
  return http.get<ReportTemplatePageResponse>('/api/report/templates', {
    params: { page, size },
  })
}

/**
 * 根据 ID 查询报表模板详情（含 content 字段）
 * @param id 模板主键
 */
export function getTemplate(id: string) {
  return http.get<ReportTemplate>(`/api/report/template/${id}`)
}

/**
 * 创建报表模板
 * @param dto 模板数据（名称、描述、内容 JSON）
 */
export function createTemplate(dto: ReportTemplateDto) {
  return http.post<ReportTemplate>('/api/report/template', dto)
}

/**
 * 更新报表模板
 * @param id 模板主键
 * @param dto 更新数据
 */
export function updateTemplate(id: string, dto: ReportTemplateDto) {
  return http.put<ReportTemplate>(`/api/report/template/${id}`, dto)
}

/**
 * 删除报表模板
 * @param id 模板主键
 */
export function deleteTemplate(id: string) {
  return http.delete(`/api/report/template/${id}`)
}

/**
 * 渲染报表（后端执行数据集查询、变量替换、行展开）
 * @param templateId 模板主键
 * @param params 运行时参数（可传入 URL 参数等）
 */
export function renderReport(templateId: string, params: Record<string, unknown> = {}) {
  return http.post<ReportRenderResponse>('/api/report/render', { templateId, params })
}

/**
 * 导出报表为 Excel 文件（返回 Blob）
 * @param req 导出请求体
 *
 * 说明：
 * - 默认 http 实例超时为 30s（见 http.ts），大数据量导出（如 10 万行）容易在前端先超时；
 * - 这里单独放宽导出接口超时，避免影响其它普通 API 的快速失败策略。
 */
export function exportReport(req: ReportExportRequest) {
  return http.post('/api/report/export', req, {
    responseType: 'blob',
    // 10 分钟；如需更大可继续上调，或改为 0（不超时）
    timeout: 10 * 60 * 1000,
  })
}

/**
 * 上传本地图片（由报表工具后端转发到业务方 imgFile 回调）
 *
 * @param templateId 模板主键（用于查询模板中配置的回调地址）
 * @param file       用户选择的本地文件
 * @returns          业务方回调返回的图片 URL 字符串
 */
export function uploadLocalImage(templateId: string, file: File): Promise<string> {
  const form = new FormData()
  form.append('file', file)
  return http
    .post<string>(`/api/report/${templateId}/image/upload/local`, form, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
    .then((res) => res.data as string)
}

/**
 * 上传网络图片（由报表工具后端拉取 URL 后转发到业务方 imgFile 回调）
 *
 * @param templateId 模板主键
 * @param imageUrl   网络图片地址
 * @returns          业务方回调返回的图片 URL 字符串
 */
export function uploadRemoteImage(templateId: string, imageUrl: string): Promise<string> {
  return http
    .post<string>(`/api/report/${templateId}/image/upload/remote`, { imageUrl })
    .then((res) => res.data as string)
}
