export interface KnowledgeStatus {
  readyDocuments: number
  totalDocuments: number
  modelConfigured: boolean
}

export interface Source {
  chunkId: string
  documentId: string
  fileName: string
  chunkIndex: number
  text: string
  pageStart: number | null
  pageEnd: number | null
}

export interface ChatAnswer {
  answer: string
  grounded: boolean
  sources: Source[]
}

export class ApiError extends Error {
  status: number
  code: string

  constructor(message: string, status = 0, code = 'NETWORK_ERROR') {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function statusMessage(status: number): string {
  if (status === 409) return '知识库暂不可用，请确认已有处理完成的文档后重试。'
  if (status === 503) return '服务暂不可用，请检查后端模型配置后重试。'
  if (status === 502) return '模型或检索服务调用失败，请检查后端服务后重试。'
  if (status === 504) return '模型或检索服务响应超时，请稍后重试。'
  if ([400, 413, 422].includes(status)) return '问题未通过校验，请检查内容与字数后重新发送。'
  if (status === 404) return '接口未找到，请检查后端地址与服务版本。'
  return `请求失败（HTTP ${status}），请稍后重试。`
}

async function request(path: string, init: RequestInit, timeoutMs: number): Promise<unknown> {
  const controller = new AbortController()
  const abort = () => controller.abort()
  if (init.signal?.aborted) controller.abort()
  else init.signal?.addEventListener('abort', abort, { once: true })
  const timer = setTimeout(abort, timeoutMs)
  try {
    const response = await fetch(path, { ...init, signal: controller.signal })
    let body: unknown
    try {
      body = await response.json()
    } catch {
      throw new ApiError(response.ok ? '服务返回的数据格式异常，请重试。' : statusMessage(response.status), response.status, 'INVALID_RESPONSE')
    }
    if (!response.ok) {
      const code = isRecord(body) && typeof body.code === 'string' ? body.code : 'HTTP_ERROR'
      const message = code === 'EMPTY_KNOWLEDGE_BASE'
        ? '知识库中还没有就绪文档，请先导入文档，处理完成后重试。'
        : isRecord(body) && typeof body.message === 'string' && body.message.trim()
          ? body.message
          : statusMessage(response.status)
      throw new ApiError(message, response.status, code)
    }
    return body
  } catch (error) {
    if (error instanceof ApiError) throw error
    if (controller.signal.aborted) {
      throw new ApiError(init.signal?.aborted ? '请求已取消。' : '请求超时，请检查服务后重试。', 0, 'REQUEST_TIMEOUT')
    }
    throw new ApiError('无法连接后端服务，请确认服务已启动且代理地址正确，然后重试。')
  } finally {
    clearTimeout(timer)
    init.signal?.removeEventListener('abort', abort)
  }
}

export async function getKnowledgeStatus(signal?: AbortSignal): Promise<KnowledgeStatus> {
  const result = await request('/api/knowledge/status', { signal }, 15_000)
  if (!isRecord(result)
    || !Number.isInteger(result.readyDocuments) || (result.readyDocuments as number) < 0
    || !Number.isInteger(result.totalDocuments) || (result.totalDocuments as number) < (result.readyDocuments as number)
    || typeof result.modelConfigured !== 'boolean') {
    throw new ApiError('知识库状态格式异常，请重试。', 0, 'INVALID_RESPONSE')
  }
  return result as unknown as KnowledgeStatus
}

function isSource(value: unknown): value is Source {
  if (!isRecord(value)) return false
  return ['chunkId', 'documentId', 'fileName', 'text'].every((key) => typeof value[key] === 'string')
    && Number.isInteger(value.chunkIndex) && (value.chunkIndex as number) >= 1
    && (value.pageStart === null || (Number.isInteger(value.pageStart) && (value.pageStart as number) >= 1))
    && (value.pageEnd === null || (Number.isInteger(value.pageEnd) && (value.pageEnd as number) >= 1))
}

export async function askQuestion(question: string, signal?: AbortSignal): Promise<ChatAnswer> {
  const result = await request('/api/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ question }),
    signal,
  }, 180_000)
  if (!isRecord(result) || typeof result.answer !== 'string' || !result.answer.trim()
    || typeof result.grounded !== 'boolean' || !Array.isArray(result.sources)
    || !result.sources.every(isSource)
    || (result.grounded && result.sources.length === 0)
    || (!result.grounded && result.sources.length !== 0)) {
    throw new ApiError('回答或来源数据格式异常，请重新发送；本次不展示未经校验的回答。', 0, 'INVALID_RESPONSE')
  }
  return result as unknown as ChatAnswer
}
