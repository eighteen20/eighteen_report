<script setup lang="ts">
/**
 * 数据集配置弹窗组件
 *
 * 支持两种数据集类型的添加和编辑：
 * - SQL：选择数据源 + 输入 SQL 语句，测试后获取字段列表
 * - API：输入接口 URL + 方法，测试后获取字段列表
 *
 * 保存后将数据集定义写入 designerStore.datasets。
 */
import { ref, watch } from 'vue'
import { useDesignerStore, useDataSourceStore } from '@/stores'
import { testDataSource } from '@/api/datasource'
import { BaseButton, BaseInput, BaseSelect, BaseModal } from '@/components/base'
import type { DatasetDefinition } from '@/types'
import { messager } from '@/composables/useMessager'

const model = defineModel<boolean>()

const props = defineProps<{
  /** 传入时为编辑模式，否则为新增模式 */
  editingDataset?: DatasetDefinition | null
}>()

const designerStore = useDesignerStore()
const dataSourceStore = useDataSourceStore()

/** 表单状态（dataSourceId 为 UUID 字符串，与后端一致） */
const form = ref({
  editKey: '',       // 编辑时记录原始 key，用于查找和替换
  key: '',           // 数据集标识符（如 orderDs）
  type: 'SQL' as 'SQL' | 'API',
  dataSourceId: '' as string,
  sql: '',
  url: '',
  method: 'GET' as 'GET' | 'POST',
})

/** 最近一次测试获取的字段列表 */
const testFields = ref<string[]>([])
/** 测试结果状态 */
const testStatus = ref<'idle' | 'loading' | 'success' | 'error'>('idle')
/** 测试结果信息 */
const testMessage = ref('')
/** 预览行数据 */
const previewRows = ref<unknown[][]>([])

/** 打开弹窗时重置表单 */
watch(
  () => model.value,
  async (visible) => {
    if (!visible) return
    // 加载数据源列表
    await dataSourceStore.fetchDataSources()
    resetForm()
  },
)

/** 监听编辑目标变化，回填表单 */
watch(
  () => props.editingDataset,
  (ds) => {
    if (ds && model.value) {
      form.value = {
        editKey: ds.key,
        key: ds.key,
        type: ds.type,
        dataSourceId: ds.dataSourceId ?? '',
        sql: ds.sql || '',
        url: ds.url || '',
        method: ds.method || 'GET',
      }
      testFields.value = ds.fields || []
      testStatus.value = 'idle'
    }
  },
  { immediate: true },
)

function resetForm() {
  const ds = props.editingDataset
  if (ds) {
    form.value = {
      editKey: ds.key,
      key: ds.key,
      type: ds.type,
      dataSourceId: ds.dataSourceId ?? '',
      sql: ds.sql || '',
      url: ds.url || '',
      method: ds.method || 'GET',
    }
    testFields.value = ds.fields || []
  } else {
    form.value = {
      editKey: '',
      key: '',
      type: 'SQL',
      dataSourceId: '',
      sql: '',
      url: '',
      method: 'GET',
    }
    testFields.value = []
  }
  testStatus.value = 'idle'
  testMessage.value = ''
  previewRows.value = []
}

/** 执行数据集测试 */
async function doTest() {
  testStatus.value = 'loading'
  testMessage.value = ''
  previewRows.value = []
  try {
    const req =
      form.value.type === 'SQL'
        ? {
            type: 'SQL' as const,
            dataSourceId: form.value.dataSourceId || undefined,
            sql: form.value.sql,
          }
        : {
            type: 'API' as const,
            apiUrl: form.value.url,
            apiMethod: form.value.method,
          }

    if (form.value.type === 'SQL') {
      if (!form.value.dataSourceId) { testMessage.value = '请选择数据源'; testStatus.value = 'error'; return }
      if (!form.value.sql.trim()) { testMessage.value = '请输入 SQL'; testStatus.value = 'error'; return }
    } else {
      if (!form.value.url.trim()) { testMessage.value = '请输入 API URL'; testStatus.value = 'error'; return }
    }

    const res = await testDataSource(req)
    testFields.value = res.data.fields || []
    previewRows.value = res.data.previewRows || []
    testStatus.value = 'success'
    testMessage.value = `字段列表：${testFields.value.join(', ')}`
  } catch (e) {
    testStatus.value = 'error'
    testMessage.value = '测试失败：' + (e as Error).message
  }
}

/** 保存数据集 */
function doSave() {
  const key = form.value.key.trim()
  if (!key) { messager.warning('请输入数据集标识符'); return }
  if (!/^\w+$/.test(key)) { messager.warning('标识符只能包含字母、数字、下划线'); return }

  const ds: DatasetDefinition = {
    key,
    type: form.value.type,
    fields: testFields.value,
    ...(form.value.type === 'SQL'
      ? { dataSourceId: form.value.dataSourceId || undefined, sql: form.value.sql }
      : { url: form.value.url, method: form.value.method }),
  }

  if (form.value.editKey) {
    // 编辑模式：替换原有数据集
    const idx = designerStore.datasets.findIndex((d: DatasetDefinition) => d.key === form.value.editKey)
    if (idx >= 0) designerStore.datasets[idx] = ds
    else designerStore.datasets.push(ds)
  } else {
    // 新增模式：检查 key 唯一性
    if (designerStore.datasets.some((d: DatasetDefinition) => d.key === key)) {
      messager.warning('数据集标识符已存在')
      return
    }
    designerStore.datasets.push(ds)
  }

  model.value = false
}
</script>

<template>
  <BaseModal v-model="model" :title="props.editingDataset ? '编辑数据集' : '添加数据集'" width="560px">
    <div class="flex flex-col gap-4">
      <!-- 标识符 -->
      <div class="form-row">
        <label class="form-label">数据集标识符 <span class="text-red-500">*</span></label>
        <BaseInput
          v-model="form.key"
          placeholder="如：orderDs（字母开头，仅限字母数字下划线）"
        />
      </div>

      <!-- 类型选择 -->
      <div class="form-row">
        <label class="form-label">数据集类型</label>
        <BaseSelect v-model="form.type">
          <option value="SQL">SQL 查询</option>
          <option value="API">API 接口</option>
        </BaseSelect>
      </div>

      <!-- SQL 类型配置 -->
      <template v-if="form.type === 'SQL'">
        <div class="form-row">
          <label class="form-label">数据源 <span class="text-red-500">*</span></label>
          <BaseSelect v-model="form.dataSourceId" placeholder="-- 请选择数据源 --">
            <option
              v-for="ds in dataSourceStore.dataSources"
              :key="ds.id"
              :value="ds.id"
            >
              {{ ds.name }} ({{ ds.type }})
            </option>
          </BaseSelect>
        </div>
        <div class="form-row">
          <label class="form-label">SQL 语句 <span class="text-red-500">*</span></label>
          <textarea
            v-model="form.sql"
            placeholder="SELECT * FROM table_name WHERE ..."
            rows="4"
            class="w-full border border-gray-300 rounded px-2 py-1.5 text-[13px] outline-none
                   focus:border-blue-500 focus:ring-1 focus:ring-blue-500 font-mono resize-none"
          />
        </div>
      </template>

      <!-- API 类型配置 -->
      <template v-else>
        <div class="form-row">
          <label class="form-label">API URL <span class="text-red-500">*</span></label>
          <BaseInput v-model="form.url" placeholder="https://api.example.com/data" />
        </div>
        <div class="form-row">
          <label class="form-label">请求方法</label>
          <BaseSelect v-model="form.method">
            <option value="GET">GET</option>
            <option value="POST">POST</option>
          </BaseSelect>
        </div>
      </template>

      <!-- 测试按钮 -->
      <div>
        <BaseButton
          :loading="testStatus === 'loading'"
          @click="doTest"
        >
          测试连接
        </BaseButton>
      </div>

      <!-- 测试结果 -->
      <div
        v-if="testStatus !== 'idle'"
        class="rounded p-3 text-[12px]"
        :class="testStatus === 'error' ? 'bg-red-50 text-red-600' : 'bg-green-50 text-green-700'"
      >
        <div>{{ testMessage }}</div>
        <!-- 预览数据表格 -->
        <div v-if="previewRows.length > 0 && testFields.length > 0" class="mt-2 overflow-auto max-h-40">
          <table class="w-full border-collapse text-[11px]">
            <thead>
              <tr>
                <th
                  v-for="f in testFields"
                  :key="f"
                  class="border border-gray-300 px-2 py-1 bg-gray-50 text-gray-600"
                >
                  {{ f }}
                </th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(row, ri) in previewRows.slice(0, 5)" :key="ri">
                <td
                  v-for="(val, ci) in row"
                  :key="ci"
                  class="border border-gray-200 px-2 py-1 text-gray-700"
                >
                  {{ val ?? '' }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <template #footer>
      <BaseButton @click="model = false">取消</BaseButton>
      <BaseButton variant="primary" @click="doSave">保存</BaseButton>
    </template>
  </BaseModal>
</template>

<style scoped>
/* TailwindCSS v4 在 scoped style 中需要 @reference 才能使用 @apply */
@reference "../../assets/main.css";

.form-row {
  @apply flex flex-col gap-1;
}

.form-label {
  @apply text-[12px] text-gray-600 font-medium;
}
</style>
