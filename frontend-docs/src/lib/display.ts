import type { DocumentChunk, KnowledgeDocument } from './types.ts'

export const MAX_FILE_SIZE = 20 * 1024 * 1024
export const PAGE_SIZE = 20
export const POLL_DELAY = 1500

export function validateFile(file: Pick<File, 'name' | 'size'> | null): string {
  if (!file) return '请先选择一个文件。'
  if (!/\.(txt|md|pdf)$/i.test(file.name)) return '不支持此格式，请选择 .txt、.md 或 .pdf 文件。'
  if (file.size === 0) return '文件为空，请选择包含有效文本的文件。'
  if (file.size > MAX_FILE_SIZE) return '文件超过 20 MiB，请选择较小的文件。'
  return ''
}

export function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MiB`
}

const dateFormatter = new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  hour12: false,
})

export function formatDate(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : dateFormatter.format(date)
}

export function newestFirst(items: KnowledgeDocument[]): KnowledgeDocument[] {
  return [...items].sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt))
}

export function characterCount(text: string): number {
  return Array.from(text).length
}

export function pageLabel(chunk: Pick<DocumentChunk, 'pageStart' | 'pageEnd'>): string {
  if (chunk.pageStart === null) return '页码未提供'
  if (chunk.pageEnd === null || chunk.pageStart === chunk.pageEnd) return `第 ${chunk.pageStart} 页`
  return `第 ${chunk.pageStart}–${chunk.pageEnd} 页`
}

export function getChatUrl(value: string | undefined): string {
  try {
    const url = new URL(value || 'http://127.0.0.1:5173')
    if (url.protocol === 'http:' || url.protocol === 'https:') return url.href
  } catch { /* 非法地址使用本地默认值，不允许脚本协议。 */ }
  return 'http://127.0.0.1:5173'
}