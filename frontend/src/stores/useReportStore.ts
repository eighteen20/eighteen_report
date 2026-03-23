/**
 * 报表模板 Pinia Store
 *
 * 负责报表模板的列表加载、当前模板状态管理和 CRUD 操作。
 * 组件通过此 store 获取和修改报表数据，避免组件间直接传值。
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { ReportTemplate, ReportTemplateDto } from '@/types'
import {
  getTemplateList,
  getTemplate,
  createTemplate,
  updateTemplate,
  deleteTemplate,
} from '@/api/report'

export const useReportStore = defineStore('report', () => {
  /** 报表模板列表 */
  const templates = ref<ReportTemplate[]>([])
  /** 列表加载状态 */
  const listLoading = ref(false)
  /** 当前正在编辑的模板总条数（分页用） */
  const total = ref(0)

  /**
   * 加载报表模板列表
   * @param page 页码（从 0 开始）
   * @param size 每页条数
   */
  async function fetchTemplates(page = 0, size = 50) {
    listLoading.value = true
    try {
      const res = await getTemplateList(page, size)
      templates.value = res.data.list || []
      total.value = res.data.total || 0
    } finally {
      listLoading.value = false
    }
  }

  /**
   * 根据 ID 加载单个模板详情
   * @param id 模板主键
   */
  async function fetchTemplate(id: string): Promise<ReportTemplate> {
    const res = await getTemplate(id)
    return res.data
  }

  /**
   * 保存模板（新建或更新）
   * @param id 为空时表示新建，有值时表示更新
   * @param dto 模板数据
   * @returns 保存后的模板对象（含服务端生成的 id）
   */
  async function saveTemplate(id: string | null, dto: ReportTemplateDto): Promise<ReportTemplate> {
    if (id) {
      const res = await updateTemplate(id, dto)
      return res.data
    } else {
      const res = await createTemplate(dto)
      return res.data
    }
  }

  /**
   * 删除模板
   * @param id 模板主键
   */
  async function removeTemplate(id: string) {
    await deleteTemplate(id)
    // 从列表中移除已删除项，避免重新请求
    templates.value = templates.value.filter((t: ReportTemplate) => t.id !== id)
    total.value = Math.max(0, total.value - 1)
  }

  return {
    templates,
    listLoading,
    total,
    fetchTemplates,
    fetchTemplate,
    saveTemplate,
    removeTemplate,
  }
})
