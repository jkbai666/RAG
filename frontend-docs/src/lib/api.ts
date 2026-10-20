import type { ChunkPage, DocumentList, KnowledgeDocument } from './types.ts'
import { PAGE_SIZE } from './display.ts'

export class ApiError extends Error {
  readonly status: number
  readonly code: string

  constructor(message: string, status = 0, code = 'NETWORK_ERROR') {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : '请求未能完成，请重试。'
}

async function request<T>(path: string, init: RequestInit = {}, timeout = 30_000, expectedStatus?: number): Promise<T> {
  const timeoutSignal = AbortSignal.timeout(timeout)
  const signal = init.signal ? AbortSignal.any([init.signal, timeoutSignal]) : timeoutSignal
  let response: Response
  try {
    response = await fetch(`/api${path}`, { ...init, signal, headers: { Accept: 'application/json', ...init.headers } })
  } catch (error) {
    if (init.signal?.aborted) throw error
    if (timeoutSignal.aborted) throw new ApiError('请求超时，请检查后端服务后重试。上传超时不代表服务端未接收文件，请先刷新列表确认。', 0, 'TIMEOUT')
    throw new ApiError('无法连接后端服务，请检查服务和代理配置后重试。')
  }

  if (!response.ok) {
    let code = `HTTP_${response.status}`
    let message = `请求失败（HTTP ${response.status}），请稍后重试。`
    try {
      const body: unknown = await response.json()
      if (body && typeof body === 'object') {
        if ('code' in body && typeof body.code === 'string') code = body.code
        if ('message' in body && typeof body.message === 'string' && body.message.trim()) message = body.message
      }
    } catch { /* 代理返回非 JSON 时保留 HTTP 错误，不误报为空数据。 */ }
    throw new ApiError(message, response.status, code)
  }

  if (expectedStatus !== undefined && response.status !== expectedStatus) {
    throw new ApiError('服务返回了非预期的状态，请刷新列表确认结果后重试。', response.status, 'UNEXPECTED_STATUS')
  }
  if (response.status === 204) return undefined as T
  try {
    return await response.json() as T
  } catch {
    throw new ApiError('服务返回了无法读取的数据，请重试。', response.status, 'INVALID_RESPONSE')
  }
}

export const api = {
  listDocuments(signal?: AbortSignal): Promise<DocumentList> {
    return request('/documents', { signal })
  },
  uploadDocument(file: File, signal?: AbortSignal): Promise<KnowledgeDocument> {
    const form = new FormData()
    form.append('file', file)
    return request('/documents', { method: 'POST', body: form, signal }, 120_000)
  },
  listChunks(id: string, page: number, signal?: AbortSignal): Promise<ChunkPage> {
    return request(`/documents/${encodeURIComponent(id)}/chunks?page=${page}&pageSize=${PAGE_SIZE}`, { signal })
  },
  deleteDocument(id: string, signal?: AbortSignal): Promise<void> {
    return request(`/documents/${encodeURIComponent(id)}`, { method: 'DELETE', signal }, 30_000, 204)
  },
}