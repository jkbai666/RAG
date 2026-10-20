export type DocumentStatus = 'PROCESSING' | 'READY' | 'FAILED'

export interface KnowledgeDocument {
  id: string
  fileName: string
  format: string
  sizeBytes: number
  status: DocumentStatus
  chunkCount: number
  createdAt: string
  errorMessage: string | null
}

export interface DocumentChunk {
  id: string
  documentId: string
  index: number
  text: string
  pageStart: number | null
  pageEnd: number | null
}

export interface DocumentList {
  items: KnowledgeDocument[]
  total: number
}

export interface ChunkPage {
  items: DocumentChunk[]
  total: number
  page: number
  pageSize: number
}