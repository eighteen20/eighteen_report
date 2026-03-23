<script setup lang="ts">
/**
 * 数据源管理弹窗
 *
 * 提供数据源列表的增删改查（CRUD）功能：
 * - 列表展示：名称、类型、URL（脱敏）、用户名、操作
 * - 新增：打开 DataSourceFormModal（新增模式）
 * - 编辑：打开 DataSourceFormModal（编辑模式），回填字段（不含密码）
 * - 删除：二次确认后删除，成功后刷新列表
 *
 * 安全约束：列表和详情接口均不返回密码明文（由后端 toDto 保证）；
 * 此组件也不渲染 password 字段。
 */
import { ref, watch } from 'vue'
import { useDataSourceStore } from '@/stores'
import { deleteDataSource } from '@/api/datasource'
import { BaseButton, BaseModal } from '@/components/base'
import { messager } from '@/composables/useMessager'
import DataSourceFormModal from './DataSourceFormModal.vue'
import type { DataSourceConfig } from '@/types'

/** 控制本弹窗显隐（v-model） */
const model = defineModel<boolean>()

const dataSourceStore = useDataSourceStore()

/** 是否显示新增/编辑表单弹窗 */
const showFormModal = ref(false)
/** 当前正在编辑的数据源（null 表示新增模式） */
const editingItem = ref<DataSourceConfig | null>(null)

/** 待删除的数据源，用于确认弹窗 */
const deletingItem = ref<DataSourceConfig | null>(null)
/** 删除操作加载状态 */
const deleteLoading = ref(false)

/** 弹窗打开时强制刷新数据源列表 */
watch(
  () => model.value,
  (visible) => {
    if (visible) {
      dataSourceStore.fetchDataSources(true)
    }
  },
)

/** 打开新增表单 */
function openCreate() {
  editingItem.value = null
  showFormModal.value = true
}

/** 打开编辑表单，回填当前行数据（不含 password） */
function openEdit(item: DataSourceConfig) {
  // 克隆对象，避免直接修改 store 中的引用
  editingItem.value = { ...item }
  showFormModal.value = true
}

/** 表单保存成功回调：强制刷新列表 */
function onFormSaved() {
  dataSourceStore.fetchDataSources(true)
}

/** 弹出删除确认 */
function confirmDelete(item: DataSourceConfig) {
  deletingItem.value = item
}

/** 执行删除 */
async function doDelete() {
  if (!deletingItem.value) return
  deleteLoading.value = true
  try {
    await deleteDataSource(deletingItem.value.id)
    messager.success(`数据源「${deletingItem.value.name}」已删除`)
    deletingItem.value = null
    // 删除后强制刷新列表
    dataSourceStore.fetchDataSources(true)
  } catch (e) {
    messager.danger('删除失败：' + (e as Error).message)
  } finally {
    deleteLoading.value = false
  }
}

/**
 * 截断过长的 URL 以便在表格中展示
 * @param url 原始 JDBC URL
 */
function shortUrl(url?: string): string {
  if (!url) return '-'
  // 仅截取前 60 字符，并在截断处显示省略号
  return url.length > 60 ? url.slice(0, 60) + '…' : url
}
</script>

<template>
  <!-- 数据源管理主弹窗 -->
  <BaseModal
    v-model="model"
    title="数据源管理"
    width="760px"
    :mask-closable="!deletingItem"
  >
    <!-- 工具栏 -->
    <div class="flex items-center justify-between mb-3">
      <span class="text-[12px] text-gray-400">
        共 {{ dataSourceStore.dataSources.length }} 个数据源
      </span>
      <BaseButton variant="primary" size="sm" @click="openCreate">+ 新增数据源</BaseButton>
    </div>

    <!-- 列表表格 -->
    <div class="border border-gray-200 rounded-lg overflow-hidden">
      <table class="w-full border-collapse text-[13px]">
        <thead>
          <tr class="bg-gray-50 border-b border-gray-200">
            <th class="text-left px-3 py-2 text-gray-500 font-medium w-36">名称</th>
            <th class="text-left px-3 py-2 text-gray-500 font-medium w-20">类型</th>
            <th class="text-left px-3 py-2 text-gray-500 font-medium">JDBC URL</th>
            <th class="text-left px-3 py-2 text-gray-500 font-medium w-28">用户名</th>
            <th class="text-left px-3 py-2 text-gray-500 font-medium w-32">操作</th>
          </tr>
        </thead>
        <tbody>
          <!-- 加载中 -->
          <tr v-if="dataSourceStore.loading">
            <td colspan="5" class="text-center text-gray-400 py-8 text-[12px]">加载中…</td>
          </tr>
          <!-- 空状态 -->
          <tr v-else-if="!dataSourceStore.dataSources.length">
            <td colspan="5" class="text-center text-gray-400 py-8 text-[12px]">
              暂无数据源，点击「新增数据源」添加
            </td>
          </tr>
          <!-- 数据行 -->
          <tr
            v-for="item in dataSourceStore.dataSources"
            :key="item.id"
            class="border-b border-gray-100 last:border-none hover:bg-gray-50 transition-colors"
          >
            <td class="px-3 py-2 text-gray-800 font-medium">{{ item.name }}</td>
            <td class="px-3 py-2">
              <span class="inline-flex items-center px-1.5 py-0.5 rounded text-[11px] font-medium bg-blue-50 text-blue-600">
                {{ item.type }}
              </span>
            </td>
            <td class="px-3 py-2 text-gray-500 font-mono text-[11px]" :title="item.url">
              {{ shortUrl(item.url) }}
            </td>
            <td class="px-3 py-2 text-gray-600">{{ item.username || '-' }}</td>
            <td class="px-3 py-2">
              <div class="flex items-center gap-1">
                <!-- 编辑图标按钮 -->
                <button
                  class="icon-btn icon-btn--edit"
                  title="编辑"
                  @click="openEdit(item)"
                >
                  <!-- 铅笔图标 -->
                  <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>
                    <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>
                  </svg>
                </button>
                <!-- 删除图标按钮 -->
                <button
                  class="icon-btn icon-btn--danger"
                  title="删除"
                  @click="confirmDelete(item)"
                >
                  <!-- 垃圾桶图标 -->
                  <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                    <polyline points="3 6 5 6 21 6"/>
                    <path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/>
                    <path d="M10 11v6"/>
                    <path d="M14 11v6"/>
                    <path d="M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"/>
                  </svg>
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <template #footer>
      <BaseButton @click="model = false">关闭</BaseButton>
    </template>
  </BaseModal>

  <!-- 新增/编辑表单弹窗 -->
  <DataSourceFormModal
    v-model="showFormModal"
    :editing-item="editingItem"
    @saved="onFormSaved"
  />

  <!-- 删除确认弹窗 -->
  <Teleport to="body">
    <Transition name="fade">
      <div
        v-if="deletingItem"
        class="fixed inset-0 z-[60] flex items-center justify-center"
      >
        <!-- 遮罩 -->
        <div class="absolute inset-0 bg-black/40" @click="deletingItem = null" />
        <!-- 确认框 -->
        <div class="relative bg-white rounded-lg shadow-xl p-6 w-80">
          <h3 class="text-sm font-semibold text-gray-800 mb-2">确认删除</h3>
          <p class="text-[13px] text-gray-500 mb-5">
            确定删除数据源「<span class="font-medium text-gray-700">{{ deletingItem.name }}</span>」吗？
            <br />
            <span class="text-orange-500 text-[12px]">引用此数据源的数据集将无法正常查询。</span>
          </p>
          <div class="flex justify-end gap-2">
            <BaseButton :disabled="deleteLoading" @click="deletingItem = null">取消</BaseButton>
            <BaseButton variant="danger" :loading="deleteLoading" @click="doDelete">
              确认删除
            </BaseButton>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
@reference "../../assets/main.css";

/* 与 ReportList 保持一致的 fade 动画 */
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.15s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

/* 图标按钮基础样式 */
.icon-btn {
  @apply inline-flex items-center justify-center w-6 h-6 rounded border cursor-pointer transition-colors;
}

/* 编辑图标按钮：蓝色调 */
.icon-btn--edit {
  @apply text-blue-500 border-blue-200 bg-white hover:bg-blue-50 hover:border-blue-400 active:bg-blue-100;
}

/* 删除图标按钮：红色调 */
.icon-btn--danger {
  @apply text-red-500 border-red-200 bg-white hover:bg-red-50 hover:border-red-400 active:bg-red-100;
}
</style>
