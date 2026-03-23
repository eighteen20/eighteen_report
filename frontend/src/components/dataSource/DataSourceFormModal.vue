<script setup lang="ts">
/**
 * 数据源新增/编辑表单弹窗
 *
 * - 新增模式：所有字段空白，密码字段必填
 * - 编辑模式：回填除密码外的所有字段；密码默认为空，
 *   仅当用户填写时才提交新密码；不填则后端保留旧密码
 *
 * 当前仅适配 MySQL（预留其他 JDBC 扩展点）。
 * 保存成功后 emit('saved') 通知父组件刷新列表。
 */
import { ref, watch, computed } from 'vue'
import { createDataSource, updateDataSource } from '@/api/datasource'
import { BaseButton, BaseInput, BaseSelect, BaseModal } from '@/components/base'
import { messager } from '@/composables/useMessager'
import type { DataSourceConfig } from '@/types'

/** 控制弹窗显隐（v-model） */
const model = defineModel<boolean>()

const props = defineProps<{
  /**
   * 编辑时传入已有数据源（不含 password 字段）
   * 为 null/undefined 时表示新增模式
   */
  editingItem?: DataSourceConfig | null
}>()

const emit = defineEmits<{
  /** 保存成功后触发，通知父组件刷新列表 */
  (e: 'saved'): void
}>()

/** 是否编辑模式 */
const isEdit = computed(() => !!props.editingItem)

/**
 * JDBC 数据库引擎预设（目前仅启用 MySQL，其他数据库预留扩展位）
 * 未来只需在此处添加新条目即可。
 */
const ENGINE_OPTIONS = [
  {
    value: 'MySQL',
    label: 'MySQL',
    driverClass: 'com.mysql.cj.jdbc.Driver',
    urlPlaceholder: 'jdbc:mysql://host:3306/dbname?useSSL=false&serverTimezone=UTC',
    disabled: false,
  },
  { value: 'PostgreSQL', label: 'PostgreSQL（暂不支持）', driverClass: '', urlPlaceholder: '', disabled: true },
  { value: 'Oracle',     label: 'Oracle（暂不支持）',     driverClass: '', urlPlaceholder: '', disabled: true },
  { value: 'SQLServer',  label: 'SQL Server（暂不支持）', driverClass: '', urlPlaceholder: '', disabled: true },
]

/** 当前选择引擎的预设信息 */
const currentEngine = computed(
  () => ENGINE_OPTIONS.find((e) => e.value === form.value.type) ?? ENGINE_OPTIONS[0],
)

/** 表单字段 */
const form = ref({
  name: '',
  type: 'MySQL',
  url: '',
  username: '',
  driverClass: 'com.mysql.cj.jdbc.Driver',
  password: '',
})

/** 表单提交加载状态 */
const saving = ref(false)

/** 打开弹窗时重置/回填表单 */
watch(
  () => model.value,
  (visible) => {
    if (!visible) return
    const item = props.editingItem
    if (item) {
      // 编辑模式：回填除 password 外的所有字段
      form.value = {
        name: item.name,
        type: item.type || 'MySQL',
        url: item.url || '',
        username: item.username || '',
        driverClass: item.driverClass || 'com.mysql.cj.jdbc.Driver',
        password: '',  // 编辑时密码字段始终为空，用户主动填写才提交
      }
    } else {
      // 新增模式：清空表单
      form.value = {
        name: '',
        type: 'MySQL',
        url: '',
        username: '',
        driverClass: 'com.mysql.cj.jdbc.Driver',
        password: '',
      }
    }
  },
)

/** 切换数据库类型时自动填入预设的 driverClass */
function onTypeChange() {
  const engine = ENGINE_OPTIONS.find((e) => e.value === form.value.type)
  if (engine && engine.driverClass) {
    form.value.driverClass = engine.driverClass
  }
}

/** 保存数据源（新增或更新） */
async function doSave() {
  // 基础字段校验
  if (!form.value.name.trim()) { messager.warning('请输入数据源名称'); return }
  if (!form.value.url.trim()) { messager.warning('请输入 JDBC URL'); return }
  if (!form.value.username.trim()) { messager.warning('请输入用户名'); return }
  if (!form.value.driverClass.trim()) { messager.warning('请输入驱动类名'); return }
  if (!isEdit.value && !form.value.password) { messager.warning('新建数据源时请输入密码'); return }

  saving.value = true
  try {
    // 构建提交体：仅在用户填写了 password 时才包含此字段（避免覆盖旧密码为空）
    const dto: Record<string, string> = {
      name: form.value.name.trim(),
      type: form.value.type,
      url: form.value.url.trim(),
      username: form.value.username.trim(),
      driverClass: form.value.driverClass.trim(),
    }
    if (form.value.password) {
      dto.password = form.value.password
    }

    if (isEdit.value && props.editingItem?.id) {
      await updateDataSource(props.editingItem.id, dto)
      messager.success('数据源已更新')
    } else {
      await createDataSource(dto)
      messager.success('数据源已创建')
    }

    model.value = false
    emit('saved')
  } catch (e) {
    messager.danger('保存失败：' + (e as Error).message)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <BaseModal
    v-model="model"
    :title="isEdit ? '编辑数据源' : '新增数据源'"
    width="520px"
    :mask-closable="!saving"
  >
    <div class="flex flex-col gap-4">
      <!-- 数据源名称 -->
      <div class="form-row">
        <label class="form-label">数据源名称 <span class="text-red-500">*</span></label>
        <BaseInput v-model="form.name" placeholder="如：业务数据库" />
      </div>

      <!-- 数据库类型 -->
      <div class="form-row">
        <label class="form-label">数据库类型 <span class="text-red-500">*</span></label>
        <BaseSelect v-model="form.type" @change="onTypeChange">
          <option
            v-for="eng in ENGINE_OPTIONS"
            :key="eng.value"
            :value="eng.value"
            :disabled="eng.disabled"
          >
            {{ eng.label }}
          </option>
        </BaseSelect>
      </div>

      <!-- JDBC URL -->
      <div class="form-row">
        <label class="form-label">JDBC URL <span class="text-red-500">*</span></label>
        <BaseInput
          v-model="form.url"
          :placeholder="currentEngine.urlPlaceholder || 'jdbc:...'"
        />
      </div>

      <!-- 用户名 -->
      <div class="form-row">
        <label class="form-label">用户名 <span class="text-red-500">*</span></label>
        <BaseInput v-model="form.username" placeholder="数据库用户名" />
      </div>

      <!-- 密码 -->
      <div class="form-row">
        <label class="form-label">
          密码
          <span v-if="!isEdit" class="text-red-500">*</span>
          <span v-else class="text-gray-400 font-normal text-[11px] ml-1">（不填则保留原密码）</span>
        </label>
        <BaseInput
          v-model="form.password"
          type="password"
          :placeholder="isEdit ? '留空则不更改密码' : '数据库密码'"
          autocomplete="new-password"
        />
      </div>

      <!-- 驱动类 -->
      <div class="form-row">
        <label class="form-label">JDBC 驱动类 <span class="text-red-500">*</span></label>
        <BaseInput v-model="form.driverClass" placeholder="com.mysql.cj.jdbc.Driver" />
        <p class="text-[11px] text-gray-400 mt-0.5">
          MySQL 默认：<code class="bg-gray-100 px-1 rounded">com.mysql.cj.jdbc.Driver</code>
        </p>
      </div>
    </div>

    <template #footer>
      <BaseButton :disabled="saving" @click="model = false">取消</BaseButton>
      <BaseButton variant="primary" :loading="saving" @click="doSave">
        {{ isEdit ? '保存修改' : '创建' }}
      </BaseButton>
    </template>
  </BaseModal>
</template>

<style scoped>
@reference "../../assets/main.css";

.form-row {
  @apply flex flex-col gap-1;
}

.form-label {
  @apply text-[12px] text-gray-600 font-medium;
}
</style>
