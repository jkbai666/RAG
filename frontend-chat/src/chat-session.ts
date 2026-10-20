import { reactive } from 'vue'
import { ApiError, askQuestion, getKnowledgeStatus } from './api.ts'
import type { ChatAnswer, KnowledgeStatus } from './api.ts'

export const QUESTION_LIMIT = 2000

export function unicodeLength(value: string): number {
  return Array.from(value).length
}

export function validateQuestion(value: string): string {
  if (!value.trim()) return '请输入问题后再发送。'
  if (unicodeLength(value) > QUESTION_LIMIT) return `问题不能超过 ${QUESTION_LIMIT} 个 Unicode 字符，请精简后发送。`
  return ''
}

export function documentsUrl(value: string | undefined): string {
  try {
    const url = new URL(value || 'http://127.0.0.1:5174')
    if (['http:', 'https:'].includes(url.protocol)) return url.href
  } catch { /* 配置无效时回到本地文档端，不执行不安全的链接。 */ }
  return 'http://127.0.0.1:5174/'
}

export function pageLabel(start: number | null, end: number | null): string {
  if (start === null && end === null) return ''
  if (start === null) return `第 ${end} 页`
  return end !== null && end !== start ? `第 ${start}–${end} 页` : `第 ${start} 页`
}

export interface Exchange {
  id: number
  question: string
  phase: 'pending' | 'answered' | 'failed'
  response: ChatAnswer | null
  error: string
}

export interface ChatState {
  draft: string
  status: KnowledgeStatus | null
  statusLoading: boolean
  statusError: string
  sending: boolean
  messages: Exchange[]
}

interface ChatApi {
  getKnowledgeStatus: (signal?: AbortSignal) => Promise<KnowledgeStatus>
  askQuestion: (question: string, signal?: AbortSignal) => Promise<ChatAnswer>
}

export function statusBlockReason(state: ChatState): string {
  if (state.statusLoading) return '正在确认知识库状态，请稍候。'
  if (state.statusError) return '暂时无法确认知识库状态，请重试获取状态。'
  if (!state.status) return '正在获取知识库状态。'
  if (!state.status.modelConfigured) return '模型尚未配置，请先在后端配置生成模型和向量模型。'
  if (state.status.readyDocuments === 0) return '请先导入文档，等待处理完成后再提问。'
  return ''
}

export function createChatSession(api: ChatApi = { getKnowledgeStatus, askQuestion }) {
  const state = reactive<ChatState>({
    draft: '', status: null, statusLoading: false, statusError: '', sending: false, messages: [],
  })
  let sequence = 0
  let statusVersion = 0
  let disposed = false
  let statusController: AbortController | undefined
  let chatController: AbortController | undefined

  async function refreshStatus(): Promise<void> {
    const version = ++statusVersion
    statusController?.abort()
    statusController = new AbortController()
    state.statusLoading = true
    state.statusError = ''
    try {
      const status = await api.getKnowledgeStatus(statusController.signal)
      if (!disposed && version === statusVersion) state.status = { ...status }
    } catch (error) {
      if (!disposed && version === statusVersion) {
        state.statusError = error instanceof Error ? error.message : '无法获取知识库状态，请重试。'
      }
    } finally {
      if (!disposed && version === statusVersion) state.statusLoading = false
    }
  }

  async function send(): Promise<boolean> {
    if (disposed || state.sending || statusBlockReason(state) || validateQuestion(state.draft)) return false
    const question = state.draft.trim()
    const last = state.messages.at(-1)
    const exchange: Exchange = last?.phase === 'failed' && last.question === question
      ? last
      : { id: ++sequence, question, phase: 'pending', response: null, error: '' }
    exchange.phase = 'pending'
    exchange.error = ''
    if (exchange !== last) state.messages.push(exchange)
    // 使用响应式代理更新异步结果，避免更新原始对象而不触发页面刷新。
    const current = state.messages.at(-1)!
    state.sending = true
    chatController = new AbortController()
    try {
      const response = await api.askQuestion(question, chatController.signal)
      if (disposed) return false
      current.response = { ...response, sources: response.sources.map((source) => ({ ...source })) }
      current.phase = 'answered'
      state.draft = ''
      return true
    } catch (error) {
      if (disposed) return false
      current.phase = 'failed'
      current.error = error instanceof Error ? error.message : '发送失败，请重新发送。'
      // 原输入只在成功后清空；失败时允许修改后重新发送。
      if (error instanceof ApiError && [409, 503].includes(error.status)) void refreshStatus()
      return false
    } finally {
      if (!disposed) state.sending = false
    }
  }

  function dispose(): void {
    disposed = true
    statusController?.abort()
    chatController?.abort()
  }

  return { state, refreshStatus, send, dispose }
}
