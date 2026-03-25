<script setup lang="ts">
/**
 * 格式工具栏组件
 *
 * Excel 风格的格式工具栏，包含：
 * - 加粗、斜体、下划线、删除线（切换按钮）
 * - 字体、字号选择
 * - 对齐方式（左/居中/右）下拉菜单
 * - 边框（所有/外侧/上/下/左/右/无）下拉菜单
 * - 字体颜色、背景色选择器
 * - 合并单元格切换
 * - 冻结行切换
 *
 * 所有操作通过 useDesignerStore 修改单元格样式，
 * 并通知父组件调用 gridApi.refreshCells() 刷新显示。
 */
import { ref, computed, nextTick, onMounted, onUnmounted } from 'vue'
import { useDesignerStore, useFormatStore } from '@/stores'
import { BaseColorPicker } from '@/components/base'
import { uploadLocalImage, uploadRemoteImage } from '@/api/report'
import { messager } from '@/composables/useMessager'

const emit = defineEmits<{
  /** 需要刷新 grid 单元格（格式变化后调用） */
  refreshCells: []
  /** 切换合并单元格 */
  toggleMerge: []
  /** 切换冻结行 */
  toggleFreeze: []
}>()

const designerStore = useDesignerStore()
const formatStore = useFormatStore()

/** 是否有选区（对齐等操作依赖此项，需先于对齐菜单逻辑声明） */
const hasSelection = computed(() => designerStore.sel.r1 >= 0)

/** 对齐下拉菜单是否展开 */
const alignMenuOpen = ref(false)
/** 对齐按钮 DOM（用于计算下拉 fixed 位置，避免父级 overflow-x-auto 裁切下拉） */
const alignBtnRef = ref<HTMLButtonElement | null>(null)
/** 下拉菜单 fixed 坐标（视口像素） */
const alignMenuPos = ref({ top: 0, left: 0 })

/**
 * 根据按钮位置更新下拉菜单位置（滚动/打开时调用）
 */
function updateAlignMenuPosition() {
  if (!alignMenuOpen.value) return
  const el = alignBtnRef.value
  if (!el) return
  const r = el.getBoundingClientRect()
  alignMenuPos.value = { top: r.bottom + 2, left: r.left }
}

/**
 * 切换对齐菜单；打开时在下一帧测量按钮位置，避免被工具栏 overflow 裁切看不见
 */
function toggleAlignMenu() {
  if (!hasSelection.value) return
  alignMenuOpen.value = !alignMenuOpen.value
  if (alignMenuOpen.value) {
    nextTick(() => updateAlignMenuPosition())
  }
}

/** 点击文档其它区域时关闭对齐/边框/图片菜单 */
function onDocumentMousedown(e: MouseEvent) {
  const t = e.target as Node
  if (alignMenuOpen.value) {
    if (alignBtnRef.value?.contains(t)) return
    if (document.getElementById('er-fmt-align-menu')?.contains(t)) return
    alignMenuOpen.value = false
  }
  if (borderMenuOpen.value) {
    if (borderBtnRef.value?.contains(t)) return
    if (document.getElementById('er-fmt-border-menu')?.contains(t)) return
    borderMenuOpen.value = false
  }
  if (imageMenuOpen.value) {
    if (imageBtnRef.value?.contains(t)) return
    if (document.getElementById('er-fmt-image-menu')?.contains(t)) return
    imageMenuOpen.value = false
  }
}

/** 边框下拉是否展开 */
const borderMenuOpen = ref(false)
const borderBtnRef = ref<HTMLButtonElement | null>(null)
const borderMenuPos = ref({ top: 0, left: 0 })

function updateBorderMenuPosition() {
  if (!borderMenuOpen.value) return
  const el = borderBtnRef.value
  if (!el) return
  const r = el.getBoundingClientRect()
  borderMenuPos.value = { top: r.bottom + 2, left: r.left }
}

function toggleBorderMenu() {
  if (!hasSelection.value) return
  borderMenuOpen.value = !borderMenuOpen.value
  if (borderMenuOpen.value) {
    nextTick(() => updateBorderMenuPosition())
  }
}

/** 边框预设（与 design.ftl 一致） */
const borderPresets: { value: 'all' | 'outer' | 'top' | 'bottom' | 'left' | 'right' | 'none'; label: string }[] = [
  { value: 'all', label: '所有边框' },
  { value: 'outer', label: '外侧边框' },
  { value: 'top', label: '上边框' },
  { value: 'bottom', label: '下边框' },
  { value: 'left', label: '左边框' },
  { value: 'right', label: '右边框' },
  { value: 'none', label: '无边框' },
]

function setBorder(preset: 'all' | 'outer' | 'top' | 'bottom' | 'left' | 'right' | 'none') {
  const fn = designerStore.setSelectionBorder
  if (typeof fn === 'function') {
    fn(preset)
  }
  borderMenuOpen.value = false
  emit('refreshCells')
}

onMounted(() => {
  document.addEventListener('mousedown', onDocumentMousedown, true)
  window.addEventListener('scroll', updateAlignMenuPosition, true)
  window.addEventListener('scroll', updateBorderMenuPosition, true)
  window.addEventListener('resize', updateAlignMenuPosition)
  window.addEventListener('resize', updateBorderMenuPosition)
})

onUnmounted(() => {
  document.removeEventListener('mousedown', onDocumentMousedown, true)
  window.removeEventListener('scroll', updateAlignMenuPosition, true)
  window.removeEventListener('scroll', updateBorderMenuPosition, true)
  window.removeEventListener('resize', updateAlignMenuPosition)
  window.removeEventListener('resize', updateBorderMenuPosition)
})

/** 字体列表 */
const fontOptions = [
  { value: '', label: '默认字体' },
  { value: 'Microsoft YaHei', label: '微软雅黑' },
  { value: 'SimSun', label: '宋体' },
  { value: 'SimHei', label: '黑体' },
  { value: 'Arial', label: 'Arial' },
  { value: 'Times New Roman', label: 'Times New Roman' },
]

/** 字号列表 */
const sizeOptions = [
  { value: '', label: '默认' },
  ...['10', '11', '12', '13', '14', '16', '18', '20', '22', '24', '28', '32', '36', '48', '72'].map(
    (v) => ({ value: v + 'px', label: v }),
  ),
]

/** 当前字体（v-model 绑定） */
const currentFont = computed({
  get: () => formatStore.fontFamily,
  set: (v) => { designerStore.setSelectionStyle('fontFamily', v); emit('refreshCells') },
})

/** 当前字号 */
const currentSize = computed({
  get: () => formatStore.fontSize,
  set: (v) => { designerStore.setSelectionStyle('fontSize', v); emit('refreshCells') },
})

/** 字体颜色（color picker 双向绑定） */
const fontColor = computed({
  get: () => formatStore.fontColor,
  set: (v) => { designerStore.setSelectionStyle('color', v); emit('refreshCells') },
})

/** 背景色 */
const bgColor = computed({
  get: () => formatStore.bgColor,
  set: (v) => { designerStore.setSelectionStyle('backgroundColor', v); emit('refreshCells') },
})

function toggleBold() {
  designerStore.toggleSelectionStyle('fontWeight', 'bold')
  emit('refreshCells')
}

function toggleItalic() {
  designerStore.toggleSelectionStyle('fontStyle', 'italic')
  emit('refreshCells')
}

function toggleUnderline() {
  designerStore.toggleTextDecoration('underline')
  emit('refreshCells')
}

function toggleStrike() {
  designerStore.toggleTextDecoration('line-through')
  emit('refreshCells')
}

function setAlign(align: string) {
  designerStore.setSelectionStyle('textAlign', align)
  alignMenuOpen.value = false
  emit('refreshCells')
}

/** 对齐图标映射 */
const alignIcons: Record<string, string> = {
  left: '≡←',
  center: '≡=',
  right: '≡→',
}

// ============================================================
// 图片上传
// ============================================================

/** 图片上传下拉菜单是否展开 */
const imageMenuOpen = ref(false)
const imageBtnRef = ref<HTMLButtonElement | null>(null)
const imageMenuPos = ref({ top: 0, left: 0 })

/** 隐藏的本地文件选择 input */
const fileInputRef = ref<HTMLInputElement | null>(null)

/** 网络图片弹窗显隐 */
const showNetworkImageModal = ref(false)
/** 网络图片 URL 输入值 */
const networkImageUrl = ref('')
/** 上传进行中 */
const imageUploading = ref(false)

function updateImageMenuPosition() {
  if (!imageMenuOpen.value) return
  const el = imageBtnRef.value
  if (!el) return
  const r = el.getBoundingClientRect()
  imageMenuPos.value = { top: r.bottom + 2, left: r.left }
}

function toggleImageMenu() {
  if (!hasSelection.value) return
  imageMenuOpen.value = !imageMenuOpen.value
  if (imageMenuOpen.value) {
    nextTick(() => updateImageMenuPosition())
  }
}

/** 校验当前模板是否已配置图片上传回调地址，未配置则提示并返回 false */
function checkCallbackConfigured(): boolean {
  const url = designerStore.gridMeta.imageUploadCallbackUrl
  if (!url || !url.trim()) {
    messager.warning('请先在右侧"图片上传回调地址"中配置业务方的图片上传接口地址')
    return false
  }
  return true
}

/** 获取当前锚点单元格所在模板 ID（从路由或全局 store 读取，需父组件提供） */
// 由于 FormatToolbar 不知道 templateId，通过 props 传入
const props = defineProps<{
  /** 当前报表模板 ID，用于图片上传接口 */
  templateId?: string
}>()

/** 点击「本地图片」 */
function onLocalImage() {
  imageMenuOpen.value = false
  if (!checkCallbackConfigured()) return
  fileInputRef.value?.click()
}

/** 点击「网络图片」 */
function onNetworkImage() {
  imageMenuOpen.value = false
  if (!checkCallbackConfigured()) return
  networkImageUrl.value = ''
  showNetworkImageModal.value = true
}

/** 本地文件选择后触发 */
async function onFileSelected(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file || !props.templateId) return
  input.value = ''
  imageUploading.value = true
  try {
    const url = await uploadLocalImage(props.templateId, file)
    writeImageToCell(url)
    messager.success('图片上传成功')
  } catch (err: unknown) {
    const msg = (err as { message?: string })?.message || '上传失败'
    messager.danger(`图片上传失败：${msg}`)
  } finally {
    imageUploading.value = false
  }
}

/** 确认网络图片上传 */
async function onConfirmNetworkImage() {
  const url = networkImageUrl.value.trim()
  if (!url) {
    messager.warning('请输入图片网络地址')
    return
  }
  if (!props.templateId) return
  imageUploading.value = true
  showNetworkImageModal.value = false
  try {
    const resultUrl = await uploadRemoteImage(props.templateId, url)
    writeImageToCell(resultUrl)
    messager.success('图片上传成功')
  } catch (err: unknown) {
    const msg = (err as { message?: string })?.message || '上传失败'
    messager.danger(`图片上传失败：${msg}`)
  } finally {
    imageUploading.value = false
  }
}

/**
 * 将图片 URL 写入当前锚点单元格：设置 type='image'，value=url
 */
function writeImageToCell(url: string) {
  const anchor = designerStore.anchorRef
  if (!anchor) return
  const cell = designerStore.ensureCell(anchor)
  cell.type = 'image'
  cell.value = url
  // 默认走「嵌入单元格」导出，与后端缺省一致；用户可在设置面板取消勾选
  delete cell.embedImageInCell
  emit('refreshCells')
}

onMounted(() => {
  window.addEventListener('scroll', updateImageMenuPosition, true)
  window.addEventListener('resize', updateImageMenuPosition)
})

onUnmounted(() => {
  window.removeEventListener('scroll', updateImageMenuPosition, true)
  window.removeEventListener('resize', updateImageMenuPosition)
})
</script>

<template>
  <div class="flex items-center gap-0.5 px-2 h-9 bg-white border-b border-gray-200 shrink-0 overflow-x-auto">

    <!-- 加粗 -->
    <button
      class="fmt-btn"
      :class="{ 'fmt-btn-active': formatStore.isBold }"
      :disabled="!hasSelection"
      title="加粗 (Ctrl+B)"
      @click="toggleBold"
    >
      <strong>B</strong>
    </button>

    <!-- 斜体 -->
    <button
      class="fmt-btn"
      :class="{ 'fmt-btn-active': formatStore.isItalic }"
      :disabled="!hasSelection"
      title="斜体 (Ctrl+I)"
      @click="toggleItalic"
    >
      <em>I</em>
    </button>

    <!-- 下划线 -->
    <button
      class="fmt-btn"
      :class="{ 'fmt-btn-active': formatStore.isUnderline }"
      :disabled="!hasSelection"
      title="下划线 (Ctrl+U)"
      @click="toggleUnderline"
    >
      <u>U</u>
    </button>

    <!-- 删除线 -->
    <button
      class="fmt-btn"
      :class="{ 'fmt-btn-active': formatStore.isStrike }"
      :disabled="!hasSelection"
      title="删除线"
      @click="toggleStrike"
    >
      <s>S</s>
    </button>

    <div class="w-px h-5 bg-gray-200 mx-1" />

    <!-- 字体选择 -->
    <select
      v-model="currentFont"
      :disabled="!hasSelection"
      class="fmt-select w-28"
      title="字体"
    >
      <option v-for="f in fontOptions" :key="f.value" :value="f.value">{{ f.label }}</option>
    </select>

    <!-- 字号选择 -->
    <select
      v-model="currentSize"
      :disabled="!hasSelection"
      class="fmt-select w-16"
      title="字号"
    >
      <option v-for="s in sizeOptions" :key="s.value" :value="s.value">{{ s.label }}</option>
    </select>

    <div class="w-px h-5 bg-gray-200 mx-1" />

    <!-- 对齐方式下拉（面板 Teleport 到 body，避免工具栏 overflow-x-auto 裁切） -->
    <div class="relative shrink-0">
      <button
        ref="alignBtnRef"
        type="button"
        class="fmt-btn min-w-[28px]"
        :disabled="!hasSelection"
        title="文本对齐"
        @click.stop="toggleAlignMenu"
      >
        {{ alignIcons[formatStore.textAlign] || '≡' }}
      </button>
    </div>

    <Teleport to="body">
      <div
        v-if="alignMenuOpen"
        id="er-fmt-align-menu"
        class="fixed z-[200] bg-white border border-gray-200 rounded shadow-lg py-1 min-w-[140px]"
        :style="{ top: alignMenuPos.top + 'px', left: alignMenuPos.left + 'px' }"
        @click.stop
      >
        <button
          v-for="align in ['left', 'center', 'right']"
          :key="align"
          type="button"
          class="flex items-center gap-2 w-full px-3 py-1.5 text-[12px] text-gray-700
                 hover:bg-gray-50 transition-colors text-left"
          :class="{ 'text-blue-500 font-medium': formatStore.textAlign === align }"
          @click="setAlign(align)"
        >
          {{ alignIcons[align] }}
          {{ align === 'left' ? '左对齐' : align === 'center' ? '居中' : '右对齐' }}
        </button>
      </div>
    </Teleport>

    <!-- 边框（下拉：所有/外侧/上/下/左/右/无，Teleport 避免 overflow 裁切） -->
    <div class="relative shrink-0">
      <button
        ref="borderBtnRef"
        type="button"
        class="fmt-btn w-7"
        :disabled="!hasSelection"
        title="边框"
        @click.stop="toggleBorderMenu"
      >
        <svg
          class="w-4 h-4 shrink-0"
          viewBox="0 0 16 16"
          fill="none"
          stroke="currentColor"
          stroke-width="1.5"
          aria-hidden="true"
        >
          <rect x="1" y="1" width="14" height="14" />
          <line x1="8" y1="1" x2="8" y2="15" />
          <line x1="1" y1="8" x2="15" y2="8" />
        </svg>
      </button>
    </div>

    <Teleport to="body">
      <div
        v-if="borderMenuOpen"
        id="er-fmt-border-menu"
        class="fixed z-[200] bg-white border border-gray-200 rounded shadow-lg py-1 min-w-[140px]"
        :style="{ top: borderMenuPos.top + 'px', left: borderMenuPos.left + 'px' }"
        @click.stop
      >
        <button
          v-for="opt in borderPresets"
          :key="opt.value"
          type="button"
          class="flex items-center gap-2 w-full px-3 py-1.5 text-[12px] text-gray-700
                 hover:bg-gray-50 transition-colors text-left"
          @click="setBorder(opt.value)"
        >
          <span class="fmt-border-icon" :class="'bi-' + opt.value" aria-hidden="true">
            <template v-if="opt.value === 'all'">
              <svg class="w-3.5 h-3.5" viewBox="0 0 14 14" fill="none" stroke="currentColor" stroke-width="1.2"><rect x="1" y="1" width="12" height="12"/><line x1="7" y1="1" x2="7" y2="13"/><line x1="1" y1="7" x2="13" y2="7"/></svg>
            </template>
            <template v-else-if="opt.value === 'outer'">
              <svg class="w-3.5 h-3.5" viewBox="0 0 14 14" fill="none" stroke="currentColor" stroke-width="1.2"><rect x="1" y="1" width="12" height="12"/></svg>
            </template>
            <template v-else-if="opt.value === 'top'">
              <svg class="w-3.5 h-3.5" viewBox="0 0 14 14" fill="none" stroke="currentColor" stroke-width="1.2"><line x1="1" y1="1" x2="13" y2="1"/><rect x="1" y="3" width="12" height="10" stroke="none" fill="transparent"/></svg>
            </template>
            <template v-else-if="opt.value === 'bottom'">
              <svg class="w-3.5 h-3.5" viewBox="0 0 14 14" fill="none" stroke="currentColor" stroke-width="1.2"><line x1="1" y1="13" x2="13" y2="13"/></svg>
            </template>
            <template v-else-if="opt.value === 'left'">
              <svg class="w-3.5 h-3.5" viewBox="0 0 14 14" fill="none" stroke="currentColor" stroke-width="1.2"><line x1="1" y1="1" x2="1" y2="13"/></svg>
            </template>
            <template v-else-if="opt.value === 'right'">
              <svg class="w-3.5 h-3.5" viewBox="0 0 14 14" fill="none" stroke="currentColor" stroke-width="1.2"><line x1="13" y1="1" x2="13" y2="13"/></svg>
            </template>
            <template v-else>
              <!-- 无边框：方框 + 斜线表示清除 -->
              <svg class="w-3.5 h-3.5" viewBox="0 0 14 14" fill="none" stroke="currentColor" stroke-width="1.2"><rect x="1" y="1" width="12" height="12"/><line x1="2" y1="2" x2="12" y2="12"/></svg>
            </template>
          </span>
          {{ opt.label }}
        </button>
      </div>
    </Teleport>

    <div class="w-px h-5 bg-gray-200 mx-1" />

    <!-- 字体颜色 -->
    <BaseColorPicker
      v-model="fontColor"
      class="fmt-btn w-7 h-7"
      :disabled="!hasSelection"
    >
      <span class="text-[13px] font-bold leading-none">A</span>
    </BaseColorPicker>

    <!-- 背景色 -->
    <BaseColorPicker
      v-model="bgColor"
      class="fmt-btn w-7 h-7"
      :disabled="!hasSelection"
    >
      <!-- 油漆桶图标（用文字替代） -->
      <span class="text-[12px] leading-none">▓</span>
    </BaseColorPicker>

    <div class="w-px h-5 bg-gray-200 mx-1" />

    <!-- 合并单元格（图标：相邻两格合并示意） -->
    <button
      type="button"
      class="fmt-btn w-7"
      :class="{ 'fmt-btn-active': designerStore.selectionOverlapsMerge() }"
      :disabled="!hasSelection"
      :title="designerStore.selectionOverlapsMerge() ? '取消合并单元格' : '合并单元格'"
      @click="emit('toggleMerge')"
    >
      <svg
        class="w-4 h-4 shrink-0"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="1.75"
        stroke-linecap="round"
        stroke-linejoin="round"
        aria-hidden="true"
      >
        <!-- 上排两格、下排合并为一格 -->
        <rect x="4" y="4" width="7" height="6" rx="1" />
        <rect x="13" y="4" width="7" height="6" rx="1" />
        <rect x="4" y="13" width="16" height="7" rx="1" />
      </svg>
    </button>

    <!-- 冻结行（图标：上方冻结区 + 分隔线 + 下方表格） -->
    <button
      type="button"
      class="fmt-btn w-7"
      :class="{ 'fmt-btn-active': designerStore.gridMeta.freezeHeaderRows > 0 }"
      :disabled="!hasSelection"
      :title="designerStore.gridMeta.freezeHeaderRows > 0
        ? `已冻结前 ${designerStore.gridMeta.freezeHeaderRows} 行，点击取消`
        : '冻结到当前选中行'"
      @click="emit('toggleFreeze')"
    >
      <svg
        class="w-4 h-4 shrink-0"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="1.75"
        stroke-linecap="round"
        stroke-linejoin="round"
        aria-hidden="true"
      >
        <rect x="4" y="4" width="16" height="5" rx="1" />
        <line x1="3" y1="11.5" x2="21" y2="11.5" />
        <rect x="4" y="14" width="16" height="6" rx="1" />
        <line x1="12" y1="14" x2="12" y2="20" opacity="0.5" />
      </svg>
    </button>

    <div class="w-px h-5 bg-gray-200 mx-1" />

    <!-- 图片上传（下拉：本地图片 / 网络图片，Teleport 避免 overflow 裁切） -->
    <div class="relative shrink-0">
      <button
        ref="imageBtnRef"
        type="button"
        class="fmt-btn w-7"
        :disabled="!hasSelection || imageUploading"
        :title="imageUploading ? '上传中…' : '插入图片'"
        @click.stop="toggleImageMenu"
      >
        <!-- 图片图标 -->
        <svg class="w-4 h-4 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <rect x="3" y="3" width="18" height="18" rx="2" />
          <circle cx="8.5" cy="8.5" r="1.5" />
          <polyline points="21 15 16 10 5 21" />
        </svg>
      </button>
    </div>

    <!-- 图片下拉菜单 -->
    <Teleport to="body">
      <div
        v-if="imageMenuOpen"
        id="er-fmt-image-menu"
        class="fixed z-[200] bg-white border border-gray-200 rounded shadow-lg py-1 min-w-[140px]"
        :style="{ top: imageMenuPos.top + 'px', left: imageMenuPos.left + 'px' }"
        @click.stop
      >
        <button
          type="button"
          class="flex items-center gap-2 w-full px-3 py-1.5 text-[12px] text-gray-700 hover:bg-gray-50 transition-colors text-left"
          @click="onLocalImage"
        >
          <svg class="w-3.5 h-3.5 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/></svg>
          本地图片
        </button>
        <button
          type="button"
          class="flex items-center gap-2 w-full px-3 py-1.5 text-[12px] text-gray-700 hover:bg-gray-50 transition-colors text-left"
          @click="onNetworkImage"
        >
          <svg class="w-3.5 h-3.5 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><line x1="2" y1="12" x2="22" y2="12"/><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/></svg>
          网络图片
        </button>
      </div>
    </Teleport>

    <!-- 隐藏文件选择输入框 -->
    <input
      ref="fileInputRef"
      type="file"
      accept="image/*"
      class="hidden"
      @change="onFileSelected"
    />

    <!-- 网络图片地址输入弹窗 -->
    <Teleport to="body">
      <div
        v-if="showNetworkImageModal"
        class="fixed inset-0 z-[300] flex items-center justify-center bg-black/30"
        @click.self="showNetworkImageModal = false"
      >
        <div class="bg-white rounded-lg shadow-xl w-[420px] p-5 flex flex-col gap-3">
          <h3 class="text-[14px] font-medium text-gray-800">插入网络图片</h3>
          <input
            v-model="networkImageUrl"
            type="url"
            placeholder="请输入图片网络地址（http/https）"
            class="border border-gray-300 rounded px-3 py-1.5 text-[13px] outline-none focus:border-blue-500 w-full"
            @keydown.enter="onConfirmNetworkImage"
            @keydown.esc="showNetworkImageModal = false"
          />
          <div class="flex justify-end gap-2 mt-1">
            <button
              type="button"
              class="px-3 py-1 text-[12px] border border-gray-300 rounded hover:bg-gray-50"
              @click="showNetworkImageModal = false"
            >取消</button>
            <button
              type="button"
              class="px-3 py-1 text-[12px] bg-blue-500 text-white rounded hover:bg-blue-600 disabled:opacity-50"
              :disabled="!networkImageUrl.trim()"
              @click="onConfirmNetworkImage"
            >确认</button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
/* TailwindCSS v4 在 scoped style 中需要 @reference 才能使用 @apply */
@reference "../../assets/main.css";

/* 工具栏按钮通用样式（scoped 以避免污染全局） */
.fmt-btn {
  @apply inline-flex items-center justify-center h-7 min-w-[28px] px-1.5
         rounded text-[13px] text-gray-700 cursor-pointer
         hover:bg-gray-100 transition-colors border-0 bg-transparent
         disabled:opacity-40 disabled:cursor-not-allowed;
}

.fmt-btn-active {
  @apply bg-blue-100 text-blue-600;
}

.fmt-select {
  @apply border border-gray-300 rounded px-1.5 py-0.5 text-[12px] outline-none h-7
         focus:border-blue-500 bg-white disabled:opacity-40 disabled:cursor-not-allowed;
}
</style>
