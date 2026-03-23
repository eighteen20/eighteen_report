<script setup lang="ts">
/**
 * 右侧设置面板组件
 *
 * 提供两个 Tab：
 * 1. 报表设置：全局配置（列排序、网格线、默认行高/列宽、纸张、分页、水印等）
 * 2. 单元格设置：当前选区锚点单元格的坐标、行高、列宽、值、类型
 *
 * 通过 emit 通知父组件调用 gridApi 方法刷新 grid 显示。
 */
import { ref, computed } from 'vue'
import { useDesignerStore } from '@/stores'
import { BaseButton, BaseInput, BaseModal, BaseSelect } from '@/components/base'
import type { GridApi } from 'ag-grid-community'
import { COL_LETTERS } from '@/stores'

const props = defineProps<{
  /** AG Grid API 实例（用于调整行高、列宽等） */
  gridApi: GridApi | null
}>()

const emit = defineEmits<{
  /** 需要重置行高 */
  resetRowHeights: []
  /** 需要重置列宽 */
  resetColWidths: []
  /** 需要更新列排序设置 */
  setSortable: [enabled: boolean]
  /** 需要刷新单元格 */
  refreshCells: []
}>()

const designerStore = useDesignerStore()

/** 当前激活的 Tab */
const activeTab = ref<'report' | 'cell'>('report')

/** 锚点单元格引用（如 "A1"） */
const anchorRef = computed(() => designerStore.anchorRef)

/** 锚点单元格解析后的行列坐标 */
const anchorParsed = computed(() => {
  if (!anchorRef.value) return null
  return designerStore.parseCellRef(anchorRef.value)
})

/** 当前锚点单元格行高 */
const cellRowHeight = computed({
  get: () => {
    if (!anchorParsed.value) return ''
    return String(
      designerStore.gridMeta.rowHeights[String(anchorParsed.value.row)] ||
        designerStore.gridMeta.defaultRowHeight ||
        25,
    )
  },
  set: (v) => {
    const num = parseInt(v)
    if (!anchorParsed.value || isNaN(num) || num < 10) return
    designerStore.gridMeta.rowHeights[String(anchorParsed.value.row)] = num
    emit('resetRowHeights')
  },
})

/** 当前锚点单元格列宽 */
const cellColWidth = computed({
  get: () => {
    if (!anchorParsed.value) return ''
    const colId = COL_LETTERS[anchorParsed.value.col]
    const saved = designerStore.gridMeta.colWidths[colId]
    if (saved) return String(saved)
    if (props.gridApi) {
      const col = props.gridApi.getColumn(colId)
      return col ? String(Math.round(col.getActualWidth())) : String(designerStore.gridMeta.defaultColWidth || 100)
    }
    return String(designerStore.gridMeta.defaultColWidth || 100)
  },
  set: (v) => {
    const num = parseInt(v)
    if (!anchorParsed.value || isNaN(num) || num < 30) return
    const colId = COL_LETTERS[anchorParsed.value.col]
    designerStore.gridMeta.colWidths[colId] = num
    props.gridApi?.setColumnWidths([{ key: colId, newWidth: num }])
  },
})

/** 当前锚点单元格值 */
const cellValue = computed({
  get: () => {
    if (!anchorRef.value) return ''
    return designerStore.cells[anchorRef.value]?.value || ''
  },
  set: (v) => {
    if (!anchorRef.value) return
    const c = designerStore.ensureCell(anchorRef.value)
    c.value = v
    designerStore.cleanCell(anchorRef.value)
    emit('refreshCells')
  },
})

/** 当前锚点单元格类型 */
const cellType = computed({
  get: () => {
    if (!anchorRef.value) return 'text'
    return designerStore.cells[anchorRef.value]?.type || 'text'
  },
  set: (v) => {
    if (!anchorRef.value) return
    const c = designerStore.ensureCell(anchorRef.value)
    c.type = v as 'text' | 'number' | 'image' | 'barcode' | 'qrcode'
    designerStore.cleanCell(anchorRef.value)
  },
})

/**
 * 水印设置弹窗显隐状态
 */
const showWatermarkModal = ref(false)

/**
 * 水印设置弹窗的本地表单模型
 *
 * 说明：
 * - 点击「水印设置」时，从 gridMeta 读取当前值填入弹窗；
 * - 点击「保存」前，先在本地编辑，避免用户输入到一半就实时污染全局配置；
 * - 点击「取消」时不落库。
 */
const watermarkForm = ref({
  watermark: '',
  watermarkCallbackUrl: '',
  watermarkDensity: 1,
})

/** 报表设置：列排序 */
function onSortableChange(e: Event) {
  const checked = (e.target as HTMLInputElement).checked
  designerStore.gridMeta.columnSortable = checked
  emit('setSortable', checked)
}

/**
 * 打开水印设置弹窗，并从当前 gridMeta 回填表单
 */
function openWatermarkSettings() {
  watermarkForm.value = {
    watermark: designerStore.gridMeta.watermark || '',
    watermarkCallbackUrl: designerStore.gridMeta.watermarkCallbackUrl ?? '',
    watermarkDensity: Number(designerStore.gridMeta.watermarkDensity ?? 1),
  }
  showWatermarkModal.value = true
}

/**
 * 保存水印设置
 *
 * 这里统一做参数边界收敛，避免异常输入导致预览层水印过密/过稀：
 * - density 最小 0.5，最大 4；
 * - callbackUrl 去除两端空白。
 */
function saveWatermarkSettings() {
  const rawDensity = Number(watermarkForm.value.watermarkDensity)
  const density = Number.isFinite(rawDensity) ? Math.min(4, Math.max(0.5, rawDensity)) : 1

  designerStore.gridMeta.watermark = watermarkForm.value.watermark || ''
  designerStore.gridMeta.watermarkCallbackUrl = (watermarkForm.value.watermarkCallbackUrl || '').trim()
  designerStore.gridMeta.watermarkDensity = density

  showWatermarkModal.value = false
}
</script>

<template>
  <aside class="flex flex-col w-[260px] shrink-0 border-l border-gray-200 bg-white h-full">
    <!-- Tab 切换 -->
    <div class="flex border-b border-gray-200 shrink-0">
      <button
        class="flex-1 py-2 text-[12px] font-medium transition-colors"
        :class="activeTab === 'report'
          ? 'text-blue-500 border-b-2 border-blue-500'
          : 'text-gray-500 hover:text-gray-700'"
        @click="activeTab = 'report'"
      >
        报表设置
      </button>
      <button
        class="flex-1 py-2 text-[12px] font-medium transition-colors"
        :class="activeTab === 'cell'
          ? 'text-blue-500 border-b-2 border-blue-500'
          : 'text-gray-500 hover:text-gray-700'"
        @click="activeTab = 'cell'"
      >
        单元格设置
      </button>
    </div>

    <!-- 内容区（可滚动） -->
    <div class="flex-1 overflow-y-auto p-3 space-y-3 text-[12px]">

      <!-- ======= 报表设置 Tab ======= -->
      <template v-if="activeTab === 'report'">

        <!-- 列排序 -->
        <div class="sp-row">
          <label class="sp-label">
            <input
              type="checkbox"
              :checked="designerStore.gridMeta.columnSortable"
              class="mr-1"
              @change="onSortableChange"
            />
            允许列排序
          </label>
        </div>

        <!-- 网格线 -->
        <div class="sp-row">
          <label class="sp-label">
            <input
              v-model="designerStore.gridMeta.showGridLines"
              type="checkbox"
              class="mr-1"
            />
            设计器网格线
          </label>
        </div>
        <div class="sp-row">
          <label class="sp-label">
            <input
              v-model="designerStore.gridMeta.renderShowGridLines"
              type="checkbox"
              class="mr-1"
            />
            预览/渲染显示网格线
          </label>
          <span class="text-[11px] text-gray-400 block mt-0.5">关则预览仅显示单元格边框，适合出稿</span>
        </div>

        <!-- 预览列头（A/B/C…） -->
        <div class="sp-row">
          <label class="sp-label">
            <input
              v-model="designerStore.gridMeta.showPreviewColHeader"
              type="checkbox"
              class="mr-1"
            />
            预览展示列头
          </label>
        </div>

        <!-- 预览内容对齐 -->
        <div class="sp-row">
          <span class="sp-label">预览内容位置</span>
          <BaseSelect v-model="designerStore.gridMeta.previewContentAlign" class="w-24">
            <option value="left">左对齐</option>
            <option value="center">居中</option>
            <option value="right">右对齐</option>
          </BaseSelect>
        </div>

        <div class="w-full h-px bg-gray-100" />

        <!-- 默认行高 -->
        <div class="sp-row">
          <span class="sp-label">默认行高（px）</span>
          <BaseInput
            type="number"
            :model-value="String(designerStore.gridMeta.defaultRowHeight)"
            :min="10"
            class="w-20"
            @change="(e: Event) => {
              const v = parseInt((e.target as HTMLInputElement).value)
              if (v >= 10) { designerStore.gridMeta.defaultRowHeight = v; emit('resetRowHeights') }
            }"
          />
        </div>

        <!-- 默认列宽 -->
        <div class="sp-row">
          <span class="sp-label">默认列宽（px）</span>
          <BaseInput
            type="number"
            :model-value="String(designerStore.gridMeta.defaultColWidth)"
            :min="30"
            class="w-20"
            @change="(e: Event) => {
              const v = parseInt((e.target as HTMLInputElement).value)
              if (v >= 30) {
                designerStore.gridMeta.defaultColWidth = v
                emit('resetColWidths')
              }
            }"
          />
        </div>

        <div class="w-full h-px bg-gray-100" />

        <!-- 页边距：预览与导出时渲染表格与容器四边的间距（单位 px） -->
        <div class="sp-row flex-wrap gap-y-1">
          <span class="sp-label w-full">页边距（px）</span>
          <div class="grid grid-cols-2 gap-1 w-full">
            <div class="flex items-center gap-1">
              <span class="text-gray-500 text-[11px] shrink-0">上</span>
              <BaseInput
                type="number"
                :model-value="String(designerStore.gridMeta.pageMargins.top)"
                :min="0"
                class="w-16"
                @change="(e: Event) => { const v = Number((e.target as HTMLInputElement).value); if (v >= 0) designerStore.gridMeta.pageMargins.top = v }"
              />
            </div>
            <div class="flex items-center gap-1">
              <span class="text-gray-500 text-[11px] shrink-0">右</span>
              <BaseInput
                type="number"
                :model-value="String(designerStore.gridMeta.pageMargins.right)"
                :min="0"
                class="w-16"
                @change="(e: Event) => { const v = Number((e.target as HTMLInputElement).value); if (v >= 0) designerStore.gridMeta.pageMargins.right = v }"
              />
            </div>
            <div class="flex items-center gap-1">
              <span class="text-gray-500 text-[11px] shrink-0">下</span>
              <BaseInput
                type="number"
                :model-value="String(designerStore.gridMeta.pageMargins.bottom)"
                :min="0"
                class="w-16"
                @change="(e: Event) => { const v = Number((e.target as HTMLInputElement).value); if (v >= 0) designerStore.gridMeta.pageMargins.bottom = v }"
              />
            </div>
            <div class="flex items-center gap-1">
              <span class="text-gray-500 text-[11px] shrink-0">左</span>
              <BaseInput
                type="number"
                :model-value="String(designerStore.gridMeta.pageMargins.left)"
                :min="0"
                class="w-16"
                @change="(e: Event) => { const v = Number((e.target as HTMLInputElement).value); if (v >= 0) designerStore.gridMeta.pageMargins.left = v }"
              />
            </div>
          </div>
        </div>

        <div class="w-full h-px bg-gray-100" />

        <div class="sp-row">
          <span class="sp-label">水印设置</span>
          <BaseButton size="sm" @click="openWatermarkSettings">配置</BaseButton>
        </div>

        <div class="w-full h-px bg-gray-100" />

        <!--
          图片上传回调：报表工具将文件以 imgFile 参数 POST 到该地址，
          业务方返回图片 URL（纯字符串或 JSON: {url:'...'}）。
          未配置时，设计器上传图片按钮会弹出提示引导配置。
        -->
        <div class="flex flex-col gap-1 w-full">
          <span class="sp-label">图片上传回调地址</span>
          <BaseInput
            v-model="designerStore.gridMeta.imageUploadCallbackUrl"
            placeholder="https://业务系统/upload/image（可留空）"
            class="w-full"
          />
          <p class="text-[11px] text-gray-500 leading-snug px-0.5">
            上传时报表后端将文件以 multipart POST 到此地址（参数名 imgFile），业务方须返回图片 URL。
          </p>
        </div>
      </template>

      <!-- ======= 单元格设置 Tab ======= -->
      <template v-else>
        <!-- 无选区提示 -->
        <div v-if="!anchorRef" class="text-center text-gray-400 py-8">
          请先点击选中单元格
        </div>

        <template v-else>
          <!-- 单元格坐标 -->
          <div class="sp-row">
            <span class="sp-label">单元格坐标</span>
            <BaseInput :model-value="anchorRef" disabled />
          </div>

          <!-- 行高 -->
          <div class="sp-row">
            <span class="sp-label">行高（px）</span>
            <BaseInput v-model="cellRowHeight" type="number" :min="10" class="w-20" />
          </div>

          <!-- 列宽 -->
          <div class="sp-row">
            <span class="sp-label">列宽（px）</span>
            <BaseInput v-model="cellColWidth" type="number" :min="30" class="w-20" />
          </div>

          <div class="w-full h-px bg-gray-100" />

          <!-- 值 -->
          <div class="sp-row">
            <span class="sp-label">单元格值</span>
            <BaseInput v-model="cellValue" placeholder="输入值或 ${变量}" />
          </div>

          <!-- 类型 -->
          <div class="sp-row">
            <span class="sp-label">值类型</span>
            <BaseSelect v-model="cellType">
              <option value="text">文本</option>
              <option value="number">数值</option>
              <option value="image">图片</option>
              <option value="barcode">条形码</option>
              <option value="qrcode">二维码</option>
            </BaseSelect>
          </div>
        </template>
      </template>
    </div>
  </aside>

  <!--
    水印设置弹窗：
    将原先面板中的三项配置合并到一个弹窗中，避免右侧面板过长且配置分散。
  -->
  <BaseModal v-model="showWatermarkModal" title="水印设置" width="560px">
    <div class="flex flex-col gap-3 text-[12px]">
      <div class="sp-row">
        <span class="sp-label">水印文字</span>
        <BaseInput
          v-model="watermarkForm.watermark"
          placeholder="可留空；有动态回调且成功时会被覆盖"
        />
      </div>

      <!--
        动态水印回调：渲染/预览时由报表工具后端对该 URL 发起 GET（附带 templateId 与 URL 上的其它查询参数，不含 watermark），
        业务按约定返回 JSON：{"watermark":"展示文案"}。配置后服务端不再信任请求体中的 watermark，避免 URI 篡改。
      -->
      <div class="flex flex-col gap-1 w-full">
        <div class="sp-row">
          <span class="sp-label">动态水印回调 URL</span>
        </div>
        <BaseInput
          v-model="watermarkForm.watermarkCallbackUrl"
          placeholder="https://业务系统/watermark?（可留空，留空则仅固定水印或旧版 params.watermark）"
          class="w-full"
        />
        <p class="text-[11px] text-gray-500 leading-snug px-0.5">
          回调异常时仅不显示动态水印，报表数据仍正常返回。生产环境请在服务端配置 host 白名单（见 application.yaml eighteen.report.watermark-callback），减轻 SSRF 风险。
        </p>
      </div>

      <div class="sp-row">
        <span class="sp-label">水印密度</span>
        <BaseInput
          v-model="watermarkForm.watermarkDensity"
          type="number"
          :min="0.5"
          :step="0.25"
          class="w-20"
        />
      </div>
    </div>

    <template #footer>
      <BaseButton @click="showWatermarkModal = false">取消</BaseButton>
      <BaseButton variant="primary" @click="saveWatermarkSettings">保存</BaseButton>
    </template>
  </BaseModal>
</template>

<style scoped>
/* TailwindCSS v4 在 scoped style 中需要 @reference 才能使用 @apply */
@reference "../../assets/main.css";

.sp-row {
  @apply flex items-center justify-between gap-2;
}

.sp-label {
  @apply text-gray-600 shrink-0;
}
</style>
