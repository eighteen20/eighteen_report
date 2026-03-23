/**
 * 全局浮动消息（Messager）
 *
 * 参考 ZUI 3 Messager 的交互：通过 JS 在页面展示短时浮动提示，不阻塞操作。
 * 文档概念对齐：https://www.openzui.com/lib/components/messager/
 *
 * 使用方式：
 * 1. 在 App.vue 根节点挂载一次 `<GlobalMessager />`
 * 2. 任意处 `import { messager } from '@/composables/useMessager'`，调用 `messager.success('...')` 等
 */

import { shallowRef } from 'vue'

/** 单条消息的展示位置（与 ZUI placement 概念对齐，略作合并） */
export type MessagerPlacement =
  | 'top'
  | 'top-left'
  | 'top-right'
  | 'bottom'
  | 'bottom-left'
  | 'bottom-right'
  | 'center'
  | 'left'
  | 'right'

/** 预设颜色/语义类型（映射到 Tailwind 样式类） */
export type MessagerType =
  | 'primary'
  | 'secondary'
  | 'success'
  | 'danger'
  | 'warning'
  | 'gray'
  | 'important'

export interface MessagerShowOptions {
  /** 提示文案 */
  content: string
  /** 外观类型，默认 secondary */
  type?: MessagerType
  /** 出现位置，默认 top */
  placement?: MessagerPlacement
  /** 自动关闭毫秒数；0 表示不自动关闭（需用户点关闭） */
  time?: number
  /** 是否显示右侧关闭按钮，默认 true */
  close?: boolean
}

/** 单条消息的完整运行时状态（内部使用） */
export interface MessagerItem {
  /** 内部唯一 id，用于移除与定时器 */
  id: number
  content: string
  type: MessagerType
  placement: MessagerPlacement
  /** 自动关闭毫秒数；0 表示不自动关闭 */
  time: number
  close: boolean
}

const DEFAULT_TIME = 5000

/** 全局消息列表（由 GlobalMessager 订阅渲染） */
const items = shallowRef<MessagerItem[]>([])

/** 自增主键 */
let seq = 0

const timers = new Map<number, ReturnType<typeof setTimeout>>()

function clearTimer(id: number) {
  const t = timers.get(id)
  if (t !== undefined) {
    clearTimeout(t)
    timers.delete(id)
  }
}

/**
 * 移除指定 id 的消息
 * @param id 消息 id
 */
function remove(id: number) {
  clearTimer(id)
  items.value = items.value.filter((m) => m.id !== id)
}

/**
 * 显示一条浮动消息
 * @param options 配置项
 * @returns 消息 id（可用于扩展：手动关闭等）
 */
function show(options: MessagerShowOptions): number {
  const id = ++seq
  const row: MessagerItem = {
    id,
    content: options.content,
    type: options.type ?? 'secondary',
    placement: options.placement ?? 'top',
    time: options.time ?? DEFAULT_TIME,
    close: options.close !== false,
  }
  items.value = [...items.value, row]

  if (row.time > 0) {
    const t = setTimeout(() => remove(id), row.time)
    timers.set(id, t)
  }
  return id
}

/**
 * 对外导出的全局 API（与常用语义方法）
 */
export const messager = {
  show,

  /** 成功类（绿色） */
  success(content: string, opts?: Omit<MessagerShowOptions, 'content' | 'type'>) {
    return show({ content, type: 'success', ...opts })
  },

  /** 错误/危险（红色） */
  danger(content: string, opts?: Omit<MessagerShowOptions, 'content' | 'type'>) {
    return show({ content, type: 'danger', ...opts })
  },

  /** 警告（琥珀色） */
  warning(content: string, opts?: Omit<MessagerShowOptions, 'content' | 'type'>) {
    return show({ content, type: 'warning', ...opts })
  },

  /** 主色（蓝色） */
  primary(content: string, opts?: Omit<MessagerShowOptions, 'content' | 'type'>) {
    return show({ content, type: 'primary', ...opts })
  },

  /** 中性灰 */
  gray(content: string, opts?: Omit<MessagerShowOptions, 'content' | 'type'>) {
    return show({ content, type: 'gray', ...opts })
  },

  /** 普通提示（与 ZUI secondary 接近） */
  info(content: string, opts?: Omit<MessagerShowOptions, 'content' | 'type'>) {
    return show({ content, type: 'secondary', ...opts })
  },

  remove,
}

/**
 * 供展示组件读取的消息列表（只读引用，勿在外部直接改）
 */
export function useMessagerItems() {
  return { items }
}
