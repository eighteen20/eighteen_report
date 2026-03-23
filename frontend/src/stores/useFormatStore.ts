/**
 * 格式工具栏状态 Pinia Store
 *
 * 管理格式工具栏的显示状态（当前选区锚点单元格的格式），
 * 工具栏组件通过此 store 读取当前值并高亮对应按钮。
 * 实际格式数据存储在 useDesignerStore.cells 中。
 */
import { defineStore } from 'pinia'
import { computed } from 'vue'
import { useDesignerStore } from './useDesignerStore'

export const useFormatStore = defineStore('format', () => {
  const designerStore = useDesignerStore()

  /**
   * 获取锚点单元格指定样式属性的值，无则返回空字符串
   */
  function getAnchorStyle(prop: string): string {
    const ar = designerStore.anchorRef
    if (!ar) return ''
    const c = designerStore.cells[ar]
    return (c?.style?.[prop as keyof typeof c.style]) || ''
  }

  /** 是否加粗 */
  const isBold = computed(() => getAnchorStyle('fontWeight') === 'bold')
  /** 是否斜体 */
  const isItalic = computed(() => getAnchorStyle('fontStyle') === 'italic')
  /** textDecoration 原始值 */
  const textDecoration = computed(() => getAnchorStyle('textDecoration'))
  /** 是否下划线 */
  const isUnderline = computed(() => textDecoration.value.includes('underline'))
  /** 是否删除线 */
  const isStrike = computed(() => textDecoration.value.includes('line-through'))
  /** 字体 */
  const fontFamily = computed(() => getAnchorStyle('fontFamily'))
  /** 字号 */
  const fontSize = computed(() => getAnchorStyle('fontSize'))
  /** 字体颜色 */
  const fontColor = computed(() => getAnchorStyle('color') || '#000000')
  /** 背景色 */
  const bgColor = computed(() => getAnchorStyle('backgroundColor') || '#ffffff')
  /** 水平对齐 */
  const textAlign = computed(() => getAnchorStyle('textAlign') || 'left')

  return {
    isBold,
    isItalic,
    isUnderline,
    isStrike,
    fontFamily,
    fontSize,
    fontColor,
    bgColor,
    textAlign,
  }
})
