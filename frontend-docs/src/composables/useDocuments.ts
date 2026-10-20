import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { api, errorMessage } from '../lib/api.ts'
import { newestFirst, PAGE_SIZE, POLL_DELAY, validateFile } from '../lib/display.ts'
import type { DocumentChunk, KnowledgeDocument } from '../lib/types.ts'

export function useDocuments() {
  const documents = ref<KnowledgeDocument[]>([])
  const listLoading = ref(false)
  const listLoaded = ref(false)
  const listError = ref('')
  const selectedId = ref<string | null>(null)
  const selectedDocument = computed(() => documents.value.find(item => item.id === selectedId.value) ?? null)
  const selectionNote = ref('')
  const chunks = ref<DocumentChunk[]>([])
  const chunkTotal = ref(0)
  const chunkPage = ref(1)
  const chunkLoading = ref(false)
  const chunkError = ref('')
  const totalPages = computed(() => Math.max(1, Math.ceil(chunkTotal.value / PAGE_SIZE)))
  const uploading = ref(false)
  const uploadError = ref('')
  const uploadVersion = ref(0)
  const notice = ref('')
  const pendingDelete = ref<KnowledgeDocument | null>(null)
  const deletingId = ref<string | null>(null)
  const deleteErrors = ref<Record<string, string>>({})
  const hasProcessing = computed(() => documents.value.some(item => item.status === 'PROCESSING'))
  const pollActive = computed(() => hasProcessing.value && !listError.value)
  const deleteTarget = computed(() => documents.value.find(item => item.id === pendingDelete.value?.id) ?? pendingDelete.value)

  let disposed = false
  let pollTimer: ReturnType<typeof setTimeout> | undefined
  let listSequence = 0
  let chunkSequence = 0
  let listController: AbortController | undefined
  let chunkController: AbortController | undefined
  let uploadController: AbortController | undefined
  let deleteController: AbortController | undefined

  function stopPolling() {
    clearTimeout(pollTimer)
    pollTimer = undefined
  }

  function clearSelection(message = '') {
    chunkSequence++
    chunkController?.abort()
    selectedId.value = null
    chunks.value = []
    chunkTotal.value = 0
    chunkPage.value = 1
    chunkLoading.value = false
    chunkError.value = ''
    selectionNote.value = message
  }

  async function refreshDocuments() {
    stopPolling()
    listController?.abort()
    const sequence = ++listSequence
    listController = new AbortController()
    listLoading.value = true
    listError.value = ''
    try {
      const result = await api.listDocuments(listController.signal)
      if (disposed || sequence !== listSequence) return
      documents.value = newestFirst(result.items)
      listLoaded.value = true
      if (selectedId.value && selectedDocument.value?.status !== 'READY') {
        clearSelection('所选文档已被移除或不再就绪，请选择其他就绪文档。')
      }
    } catch (error) {
      if (disposed || sequence !== listSequence) return
      listError.value = errorMessage(error)
    } finally {
      if (!disposed && sequence === listSequence) {
        listLoading.value = false
        if (hasProcessing.value && !listError.value) {
          pollTimer = setTimeout(() => { void refreshDocuments() }, POLL_DELAY)
        }
      }
    }
  }

  async function loadChunks(page = chunkPage.value) {
    const doc = selectedDocument.value
    if (!doc || doc.status !== 'READY') return
    chunkController?.abort()
    const sequence = ++chunkSequence
    chunkController = new AbortController()
    chunkPage.value = page
    chunkLoading.value = true
    chunkError.value = ''
    chunks.value = []
    try {
      const result = await api.listChunks(doc.id, page, chunkController.signal)
      if (disposed || sequence !== chunkSequence || selectedId.value !== doc.id) return
      chunks.value = [...result.items].sort((a, b) => a.index - b.index)
      chunkTotal.value = result.total
      chunkPage.value = result.page
    } catch (error) {
      if (disposed || sequence !== chunkSequence) return
      chunkError.value = errorMessage(error)
    } finally {
      if (!disposed && sequence === chunkSequence) chunkLoading.value = false
    }
  }

  function selectDocument(doc: KnowledgeDocument) {
    if (doc.status !== 'READY' || deletingId.value === doc.id) return
    if (selectedId.value === doc.id) return
    clearSelection()
    selectedId.value = doc.id
    chunkTotal.value = doc.chunkCount
    void loadChunks(1)
  }

  async function uploadDocument(file: File) {
    if (uploading.value) return
    uploadError.value = validateFile(file)
    if (uploadError.value) return
    uploading.value = true
    notice.value = ''
    uploadController = new AbortController()
    try {
      const doc = await api.uploadDocument(file, uploadController.signal)
      if (disposed) return
      documents.value = newestFirst([doc, ...documents.value.filter(item => item.id !== doc.id)])
      uploadVersion.value++
      notice.value = `“${doc.fileName}”已上传，后端将自动提取文本并建立索引。`
      void refreshDocuments()
    } catch (error) {
      if (!disposed) uploadError.value = errorMessage(error)
    } finally {
      if (!disposed) uploading.value = false
    }
  }

  function openDelete(doc: KnowledgeDocument) {
    if (doc.status === 'PROCESSING' || deletingId.value) return
    pendingDelete.value = doc
  }

  function closeDelete() {
    if (!deletingId.value) pendingDelete.value = null
  }

  async function confirmDelete() {
    const doc = deleteTarget.value
    if (!doc || doc.status === 'PROCESSING' || deletingId.value) return
    deletingId.value = doc.id
    deleteErrors.value = { ...deleteErrors.value, [doc.id]: '' }
    notice.value = ''
    deleteController = new AbortController()
    try {
      await api.deleteDocument(doc.id, deleteController.signal)
      if (disposed) return
      // 只有服务端确认 204 后才从本地列表移除；不做乐观删除。
      documents.value = documents.value.filter(item => item.id !== doc.id)
      if (selectedId.value === doc.id) clearSelection('所选文档已删除，请选择其他就绪文档。')
      pendingDelete.value = null
      notice.value = `“${doc.fileName}”已删除，其分块和检索数据已移除。`
      void refreshDocuments()
    } catch (error) {
      if (disposed) return
      deleteErrors.value = { ...deleteErrors.value, [doc.id]: errorMessage(error) }
      // 跨存储删除失败可能改变后端状态；保留记录并重新读取，不假装删除成功。
      void refreshDocuments()
    } finally {
      if (!disposed) deletingId.value = null
    }
  }

  onMounted(() => { void refreshDocuments() })
  onBeforeUnmount(() => {
    disposed = true
    stopPolling()
    listController?.abort()
    chunkController?.abort()
    uploadController?.abort()
    deleteController?.abort()
  })

  return {
    documents, listLoading, listLoaded, listError, hasProcessing, pollActive,
    selectedDocument, selectionNote, chunks, chunkTotal, chunkPage, chunkLoading, chunkError, totalPages,
    uploading, uploadError, uploadVersion, notice, deleteTarget, deletingId, deleteErrors,
    refreshDocuments, selectDocument, loadChunks, uploadDocument, openDelete, closeDelete, confirmDelete,
  }
}