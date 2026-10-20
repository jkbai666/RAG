import { reactive } from 'vue'
import { ApiError, createConversation, getConversation, sendMessage, getKnowledgeStatus, UUID_PATTERN } from './api.ts'
import type { ChatAnswer, Conversation, KnowledgeStatus } from './api.ts'

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

export const SESSION_STORAGE_KEY = 'rag.current-conversation.v1'
export interface Exchange {
  id: string
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
  conversationId: string | null
  sessionLoading: boolean
  sessionReady: boolean
  sessionError: string
}
interface ChatApi {
  getKnowledgeStatus: (signal?: AbortSignal) => Promise<KnowledgeStatus>
  createConversation: (signal?: AbortSignal) => Promise<Conversation>
  getConversation: (id: string, signal?: AbortSignal) => Promise<Conversation>
  sendMessage: (id: string, question: string, requestId: string, signal?: AbortSignal) => Promise<ChatAnswer>
}
interface SessionStorage { getItem(key: string): string | null; setItem(key: string, value: string): void }
interface Pending { requestId: string; question: string }
interface Saved { conversationId: string; pending: Pending | null }

export function statusBlockReason(state: ChatState): string {
  if (state.sessionLoading) return '正在恢复或创建会话，请稍候。'
  if (state.sessionError) return '会话暂不可用，请先重试恢复。'
  if (!state.sessionReady) return '正在准备会话。'
  if (state.statusLoading) return '正在确认知识库状态，请稍候。'
  if (state.statusError) return '暂时无法确认知识库状态，请重试获取状态。'
  if (!state.status) return '正在获取知识库状态。'
  if (!state.status.modelConfigured) return '模型尚未配置，请先在后端配置生成模型和向量模型。'
  if (state.status.readyDocuments === 0) return '请先导入文档，等待处理完成后再提问。'
  return ''
}
export function createChatSession(
  api: ChatApi = { getKnowledgeStatus, createConversation, getConversation, sendMessage },
  storage: SessionStorage = { getItem: key => localStorage.getItem(key), setItem: (key, value) => localStorage.setItem(key, value) },
  newId: () => string = () => crypto.randomUUID(),
) {
  const state = reactive<ChatState>({
    draft: '', status: null, statusLoading: false, statusError: '', sending: false, messages: [],
    conversationId: null, sessionLoading: false, sessionReady: false, sessionError: '',
  })
  let pending: Pending | null = null
  let loaded = false
  let statusVersion = 0
  let sessionVersion = 0
  let disposed = false
  let statusController: AbortController | undefined
  let chatController: AbortController | undefined
  let sessionController: AbortController | undefined
  const snapshot = (answer: ChatAnswer): ChatAnswer => ({ ...answer, sources: answer.sources.map(source => ({ ...source })) })
  function persist(id: string, value: Pending | null): void {
    // No successful Q/A, answer text or historical sources are cached in the browser.
    storage.setItem(SESSION_STORAGE_KEY, JSON.stringify({ conversationId: id, pending: value } satisfies Saved))
  }
  function readSaved(): void {
    const raw = storage.getItem(SESSION_STORAGE_KEY)
    if (!raw) { loaded = true; return }
    const value = JSON.parse(raw) as Saved
    if (!value || typeof value.conversationId !== 'string') throw new Error('本地会话标识无效，请明确选择新对话。')
    state.conversationId = value.conversationId // Preserve even a malformed ID; never silently replace it.
    if (!UUID_PATTERN.test(value.conversationId) || (value.pending && (!UUID_PATTERN.test(value.pending.requestId)
      || typeof value.pending.question !== 'string' || validateQuestion(value.pending.question)))) {
      throw new Error('本地会话或待重试信息无效，请明确选择新对话。')
    }
    pending = value.pending || null
    loaded = true
  }
  async function initialize(force = false): Promise<void> {
    if (disposed || state.sending || state.sessionLoading || (state.sessionReady && !force)) return
    const version = ++sessionVersion
    state.sessionLoading = true
    state.sessionError = ''
    sessionController = new AbortController()
    try {
      if (!loaded) readSaved()
      const id = state.conversationId
      const conversation = id ? await api.getConversation(id, sessionController.signal) : await api.createConversation(sessionController.signal)
      if (disposed || version !== sessionVersion) return
      const completed = pending && conversation.exchanges.find(item => item.requestId === pending!.requestId)
      if (completed && completed.question !== pending!.question) throw new Error('待重试请求与服务端问题不一致，请检查后重试。')
      const nextPending = completed ? null : pending
      // Keep a received server ID in memory even if browser storage is temporarily unavailable.
      state.conversationId = conversation.id
      persist(conversation.id, nextPending)
      pending = nextPending
      state.messages = conversation.exchanges.map(item => ({ id: item.requestId, question: item.question, phase: 'answered', response: snapshot(item.response), error: '' }))
      if (pending) {
        state.draft = pending.question
        state.messages.push({ id: pending.requestId, question: pending.question, phase: 'failed', response: null,
          error: '上次发送结果尚未确认；可复用原请求标识重试，避免重复问答。' })
      } else if (completed) state.draft = ''
      state.sessionReady = true
    } catch (error) {
      if (!disposed && version === sessionVersion) {
        state.sessionReady = false
        state.sessionError = error instanceof Error ? error.message : '无法恢复会话，请重试。'
      }
    } finally {
      if (!disposed && version === sessionVersion) state.sessionLoading = false
    }
  }
  async function newConversation(): Promise<boolean> {
    if (disposed || state.sending || state.sessionLoading) return false
    const version = ++sessionVersion
    state.sessionLoading = true
    sessionController = new AbortController()
    try {
      const conversation = await api.createConversation(sessionController.signal)
      if (disposed || version !== sessionVersion) return false
      persist(conversation.id, null)
      loaded = true
      pending = null
      state.conversationId = conversation.id
      state.messages = []
      state.draft = ''
      state.sessionError = ''
      state.sessionReady = true
      return true
    } catch (error) {
      if (!disposed && version === sessionVersion) state.sessionError = error instanceof Error ? error.message : '新对话创建失败，请重试。'
      return false
    } finally {
      if (!disposed && version === sessionVersion) state.sessionLoading = false
    }
  }
  async function refreshStatus(): Promise<void> {
    if (disposed) return
    const version = ++statusVersion
    statusController?.abort()
    statusController = new AbortController()
    state.statusLoading = true
    state.statusError = ''
    try {
      const status = await api.getKnowledgeStatus(statusController.signal)
      if (!disposed && version === statusVersion) state.status = { ...status }
    } catch (error) {
      if (!disposed && version === statusVersion) state.statusError = error instanceof Error ? error.message : '无法获取知识库状态，请重试。'
    } finally {
      if (!disposed && version === statusVersion) state.statusLoading = false
    }
  }
  async function send(): Promise<boolean> {
    if (disposed || state.sending || statusBlockReason(state) || validateQuestion(state.draft) || !state.conversationId) return false
    const question = state.draft.trim()
    const requestId = pending?.question === question ? pending.requestId : newId()
    try { persist(state.conversationId, { requestId, question }) }
    catch { state.sessionError = '无法保存待重试请求，尚未发送。请检查浏览器存储后重试恢复。'; return false }
    pending = { requestId, question }
    const last = state.messages.at(-1)
    if (last?.phase !== 'failed' || last.id !== requestId) {
      state.messages.push({ id: requestId, question, phase: 'pending', response: null, error: '' })
    }
    const current = state.messages.at(-1)!
    current.phase = 'pending'
    current.error = ''
    state.sending = true
    chatController = new AbortController()
    try {
      const response = await api.sendMessage(state.conversationId, question, requestId, chatController.signal)
      if (disposed) return false
      persist(state.conversationId, null)
      pending = null
      current.response = snapshot(response)
      current.phase = 'answered'
      state.draft = ''
      return true
    } catch (error) {
      if (disposed) return false
      current.phase = 'failed'
      current.error = error instanceof Error ? error.message : '发送失败，请重新发送。'
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
    sessionController?.abort()
  }
  return { state, initialize, newConversation, refreshStatus, send, dispose }
}
