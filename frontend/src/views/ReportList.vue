<script setup lang="ts">
/**
 * 报表列表页
 *
 * 展示所有报表模板，支持：
 * - 编辑（跳转设计器）
 * - 预览（跳转预览页）
 * - 删除（二次确认后删除）
 * - 新建（跳转设计器空白状态）
 * - 数据源管理（弹窗，支持数据源增删改查）
 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useReportStore } from '@/stores'
import { BaseButton } from '@/components/base'
import { messager } from '@/composables/useMessager'
import DataSourceAdminModal from '@/components/dataSource/DataSourceAdminModal.vue'
import type { ReportTemplate } from '@/types'

const router = useRouter()
const reportStore = useReportStore()

/** 控制数据源管理弹窗显隐 */
const showDataSourceAdmin = ref(false)

/** 确认删除弹窗的目标模板 */
const deletingItem = ref<ReportTemplate | null>(null)
const deleteLoading = ref(false)

onMounted(() => {
  reportStore.fetchTemplates()
})

/** 跳转到报表设计器（新建模式） */
function goCreate() {
  router.push('/report/design')
}

/** 跳转到编辑设计器 */
function goEdit(id: string) {
  router.push(`/report/design/${id}`)
}

/** 跳转到预览页（新标签页打开） */
function goPreview(id: string) {
  window.open(`/report/preview/${id}`, '_blank')
}

/** 弹出删除确认 */
function confirmDelete(item: ReportTemplate) {
  deletingItem.value = item
}

/** 执行删除 */
async function doDelete() {
  if (!deletingItem.value) return
  deleteLoading.value = true
  try {
    await reportStore.removeTemplate(deletingItem.value.id)
    deletingItem.value = null
  } catch (e) {
    messager.danger('删除失败：' + (e as Error).message)
  } finally {
    deleteLoading.value = false
  }
}

/** 格式化时间显示 */
function formatDate(str?: string): string {
  if (!str) return '-'
  return new Date(str).toLocaleString('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit',
  })
}
</script>

<template>
  <div class="flex flex-col h-full bg-gray-50">
    <!-- 顶部工具栏 -->
    <header class="flex items-center gap-3 px-4 py-2.5 bg-white border-b border-gray-200 shrink-0 h-11">
      <h1 class="text-base font-semibold text-gray-800">报表列表</h1>
      <div class="flex-1" />
      <BaseButton @click="showDataSourceAdmin = true">数据源管理</BaseButton>
      <BaseButton variant="primary" @click="goCreate">+ 新建报表</BaseButton>
    </header>

    <!-- 主内容区 -->
    <main class="flex-1 p-4 overflow-auto">
      <!-- 加载中 -->
      <div
        v-if="reportStore.listLoading"
        class="text-center text-gray-400 py-16 text-sm"
      >
        加载中...
      </div>

      <!-- 报表列表表格 -->
      <div
        v-else
        class="bg-white rounded-lg shadow-sm border border-gray-200 overflow-hidden"
      >
        <table class="w-full border-collapse text-[13px]">
          <thead>
            <tr class="bg-gray-50 border-b border-gray-200">
              <th class="text-left px-4 py-2.5 text-gray-500 font-medium">名称</th>
              <th class="text-left px-4 py-2.5 text-gray-500 font-medium">描述</th>
              <th class="text-left px-4 py-2.5 text-gray-500 font-medium w-40">更新时间</th>
              <th class="text-left px-4 py-2.5 text-gray-500 font-medium w-52">操作</th>
            </tr>
          </thead>
          <tbody>
            <!-- 空状态 -->
            <tr v-if="!reportStore.templates.length">
              <td colspan="4" class="text-center text-gray-400 py-12">
                暂无报表，点击「新建报表」开始创建
              </td>
            </tr>
            <!-- 数据行 -->
            <tr
              v-for="item in reportStore.templates"
              :key="item.id"
              class="border-b border-gray-100 last:border-none hover:bg-gray-50 transition-colors"
            >
              <td class="px-4 py-2.5 text-gray-800 font-medium">{{ item.name || '-' }}</td>
              <td class="px-4 py-2.5 text-gray-500">{{ item.description || '-' }}</td>
              <td class="px-4 py-2.5 text-gray-400">{{ formatDate(item.updatedAt) }}</td>
              <td class="px-4 py-2.5">
                <div class="flex items-center gap-2">
                  <BaseButton size="sm" @click="goEdit(item.id)">编辑</BaseButton>
                  <BaseButton size="sm" @click="goPreview(item.id)">预览</BaseButton>
                  <BaseButton size="sm" variant="danger" @click="confirmDelete(item)">删除</BaseButton>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </main>

    <!-- 数据源管理弹窗 -->
    <DataSourceAdminModal v-model="showDataSourceAdmin" />

    <!-- 删除确认弹窗 -->
    <Teleport to="body">
      <Transition name="fade">
        <div
          v-if="deletingItem"
          class="fixed inset-0 z-50 flex items-center justify-center"
        >
          <!-- 遮罩 -->
          <div class="absolute inset-0 bg-black/40" @click="deletingItem = null" />
          <!-- 确认框 -->
          <div class="relative bg-white rounded-lg shadow-xl p-6 w-80">
            <h3 class="text-sm font-semibold text-gray-800 mb-2">确认删除</h3>
            <p class="text-[13px] text-gray-500 mb-5">
              确定删除报表「<span class="font-medium text-gray-700">{{ deletingItem.name }}</span>」吗？此操作不可撤销。
            </p>
            <div class="flex justify-end gap-2">
              <BaseButton @click="deletingItem = null">取消</BaseButton>
              <BaseButton variant="danger" :loading="deleteLoading" @click="doDelete">
                确认删除
              </BaseButton>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>
