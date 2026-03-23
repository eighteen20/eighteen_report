/**
 * 数据源配置 API 封装
 *
 * 对应后端 ApiDataSourceController 的所有接口：
 * - 数据源 CRUD（创建、查询、更新、删除）
 * - 连接测试（验证 SQL/API 数据集配置是否可用）
 */
import http from './http'
import type {
  DataSourceConfig,
  DataSourceTestRequest,
  DataSourceTestResponse,
} from '@/types'

/**
 * 查询所有数据源列表
 */
export function getDataSourceList() {
  return http.get<DataSourceConfig[]>('/api/datasource/list')
}

/**
 * 根据 ID 查询数据源详情
 * @param id 数据源主键（UUID 字符串）
 */
export function getDataSource(id: string) {
  return http.get<DataSourceConfig>(`/api/datasource/${id}`)
}

/**
 * 创建数据源
 * @param dto 数据源配置（password 字段按需提交，后端不返回密码明文）
 */
export function createDataSource(dto: Partial<DataSourceConfig> & { password?: string }) {
  return http.post<DataSourceConfig>('/api/datasource', dto)
}

/**
 * 更新数据源
 * @param id 数据源主键（UUID 字符串）
 * @param dto 更新数据（password 为空时不覆盖旧密码）
 */
export function updateDataSource(id: string, dto: Partial<DataSourceConfig> & { password?: string }) {
  return http.put<DataSourceConfig>(`/api/datasource/${id}`, dto)
}

/**
 * 删除数据源
 * @param id 数据源主键（UUID 字符串）
 */
export function deleteDataSource(id: string) {
  return http.delete(`/api/datasource/${id}`)
}

/**
 * 测试数据集连接（SQL 或 API），获取字段列表和预览数据
 * @param req 测试请求体（包含类型、数据源ID、SQL 或 API 配置）
 */
export function testDataSource(req: DataSourceTestRequest) {
  return http.post<DataSourceTestResponse>('/api/datasource/test', req)
}
