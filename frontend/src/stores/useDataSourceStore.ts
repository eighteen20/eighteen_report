/**
 * 数据源 Pinia Store
 *
 * 缓存数据源列表，避免每次打开数据集弹窗都重新请求。
 * 数据集弹窗（DatasetModal）通过此 store 获取可选数据源列表。
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { DataSourceConfig } from '@/types'
import { getDataSourceList } from '@/api/datasource'

export const useDataSourceStore = defineStore('dataSource', () => {
  /** 数据源配置列表 */
  const dataSources = ref<DataSourceConfig[]>([])
  /** 加载状态 */
  const loading = ref(false)
  /** 是否已加载过（避免重复请求） */
  const loaded = ref(false)

  /**
   * 加载数据源列表（如果已加载则跳过）
   * @param force 强制刷新（默认 false）
   */
  async function fetchDataSources(force = false) {
    if (loaded.value && !force) return
    loading.value = true
    try {
      const res = await getDataSourceList()
      dataSources.value = res.data || []
      loaded.value = true
    } finally {
      loading.value = false
    }
  }

  return {
    dataSources,
    loading,
    loaded,
    fetchDataSources,
  }
})
