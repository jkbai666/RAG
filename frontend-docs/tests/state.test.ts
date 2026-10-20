import assert from 'node:assert/strict'
import test from 'node:test'
import { setImmediate } from 'node:timers/promises'
import { createRenderer, defineComponent } from 'vue'
import { useDocuments } from '../src/composables/useDocuments.ts'
import type { KnowledgeDocument } from '../src/lib/types.ts'

const ready: KnowledgeDocument = { id: 'one', fileName: '资料.txt', format: 'txt', sizeBytes: 123, status: 'READY', chunkCount: 21, createdAt: '2026-10-20T00:00:00Z', errorMessage: null }
const processing: KnowledgeDocument = { ...ready, id: 'processing', status: 'PROCESSING' }
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
const listing = (items: KnowledgeDocument[]) => json({ items, total: items.length })
const tick = async () => { await setImmediate(); await setImmediate() }

function mount() {
  const renderer = createRenderer({
    createElement: () => ({}), createText: () => ({}), createComment: () => ({}),
    insert() {}, remove() {}, setText() {}, setElementText() {}, patchProp() {},
    parentNode: () => null, nextSibling: () => null,
  })
  let state!: ReturnType<typeof useDocuments>
  const app = renderer.createApp(defineComponent({ setup() { state = useDocuments(); return () => null } }))
  app.mount({})
  return { state, unmount: () => app.unmount() }
}

test('初始列表加载、技术错误和显式重试', async t => {
  const fetchMock = t.mock.method(globalThis, 'fetch', async () => { throw new Error('offline') })
  const { state, unmount } = mount()
  t.after(unmount)
  assert.equal(state.listLoading.value, true)
  await tick()
  assert.match(state.listError.value, /无法连接/)
  assert.equal(state.listLoaded.value, false)
  fetchMock.mock.mockImplementation(async () => listing([]))
  await state.refreshDocuments()
  assert.equal(state.listLoaded.value, true)
  assert.equal(state.listError.value, '')
  assert.deepEqual(state.documents.value, [])
})

test('仅PROCESSING时每1500ms轮询，失败后暂停，重试后恢复', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  let calls = 0
  t.mock.method(globalThis, 'fetch', async () => {
    calls++
    if (calls === 2) return json({ code: 'DOWN', message: '后端不可用' }, 503)
    return listing(calls >= 4 ? [ready] : [processing])
  })
  const { state, unmount } = mount()
  t.after(unmount)
  await tick()
  assert.equal(state.pollActive.value, true)
  t.mock.timers.tick(1499)
  await tick()
  assert.equal(calls, 1)
  t.mock.timers.tick(1)
  await tick()
  assert.equal(calls, 2)
  assert.equal(state.pollActive.value, false)
  t.mock.timers.tick(3000)
  await tick()
  assert.equal(calls, 2)
  await state.refreshDocuments()
  assert.equal(state.pollActive.value, true)
  t.mock.timers.tick(1500)
  await tick()
  assert.equal(calls, 4)
  assert.equal(state.hasProcessing.value, false)
  t.mock.timers.tick(3000)
  await tick()
  assert.equal(calls, 4)
})

test('只能选择READY，分块错误可重试，20块分页且切换时忽略过期响应', async t => {
  let resolveFirst!: (value: Response) => void
  const second = { ...ready, id: 'two' }
  let failChunk = false
  const paths: string[] = []
  t.mock.method(globalThis, 'fetch', async (url: string) => {
    paths.push(url)
    if (url === '/api/documents') return listing([ready, second, processing])
    if (url.includes('/one/')) return new Promise<Response>(resolve => { resolveFirst = resolve })
    if (failChunk) return json({ code: 'DOWN', message: '读取失败' }, 502)
    return json({ items: [{ id: 'c21', documentId: 'two', index: 21, text: '<script>不会执行</script>', pageStart: 2, pageEnd: 3 }], total: 21, page: url.includes('page=2') ? 2 : 1, pageSize: 20 })
  })
  const { state, unmount } = mount()
  t.after(unmount)
  await tick()
  state.selectDocument(processing)
  assert.equal(state.selectedDocument.value, null)
  state.selectDocument(ready)
  await tick()
  state.selectDocument(second)
  await tick()
  resolveFirst(json({ items: [{ id: 'stale', index: 1 }], total: 1, page: 1, pageSize: 20 }))
  await tick()
  assert.equal(state.chunks.value[0]?.id, 'c21')
  failChunk = true
  await state.loadChunks(2)
  assert.equal(state.chunkError.value, '读取失败')
  assert.equal(state.chunkPage.value, 2)
  failChunk = false
  await state.loadChunks()
  assert.equal(state.chunkError.value, '')
  assert.ok(paths.includes('/api/documents/two/chunks?page=2&pageSize=20'))
  assert.equal(state.totalPages.value, 2)
})

test('删除失败保留记录、错误和重试入口，204后才移除并清空选择', async t => {
  let deleteCalls = 0
  let deleted = false
  let completeDelete!: (value: Response) => void
  t.mock.method(globalThis, 'fetch', async (url: string, init: RequestInit) => {
    if (init.method === 'DELETE') {
      deleteCalls++
      if (deleteCalls === 1) return json({ code: 'VECTOR_DELETE_FAILED', message: 'Chroma 删除失败' }, 502)
      return new Promise<Response>(resolve => { completeDelete = resolve })
    }
    if (url === '/api/documents') return listing(deleted ? [] : [ready])
    return json({ items: [], total: 0, page: 1, pageSize: 20 })
  })
  const { state, unmount } = mount()
  t.after(unmount)
  await tick()
  state.openDelete(processing)
  assert.equal(state.deleteTarget.value, null)
  state.selectDocument(ready)
  state.openDelete(ready)
  await state.confirmDelete()
  await tick()
  assert.equal(state.documents.value.length, 1)
  assert.equal(state.deleteErrors.value.one, 'Chroma 删除失败')
  assert.equal(state.deleteTarget.value?.id, 'one')
  assert.equal(state.notice.value, '')
  const deleting = state.confirmDelete()
  await tick()
  assert.equal(state.deletingId.value, 'one')
  assert.equal(state.documents.value.length, 1)
  state.closeDelete()
  assert.equal(state.deleteTarget.value?.id, 'one')
  deleted = true
  completeDelete(new Response(null, { status: 204 }))
  await deleting
  await tick()
  assert.equal(state.documents.value.length, 0)
  assert.equal(state.selectedDocument.value, null)
  assert.equal(state.deleteTarget.value, null)
  assert.match(state.notice.value, /已删除/)
})

test('上传禁止重复提交，失败保留原因，成功后重新读取列表', async t => {
  let uploaded = false
  let postCount = 0
  let completeUpload!: (value: Response) => void
  t.mock.method(globalThis, 'fetch', async (_url: string, init: RequestInit) => {
    if (init.method === 'POST') {
      postCount++
      return new Promise<Response>(resolve => { completeUpload = resolve })
    }
    return listing(uploaded ? [processing] : [])
  })
  const { state, unmount } = mount()
  t.after(unmount)
  await tick()
  const file = new File(['abc'], 'test.txt')
  const uploading = state.uploadDocument(file)
  await state.uploadDocument(file)
  assert.equal(postCount, 1)
  completeUpload(json({ code: 'IMPORT_FAILED', message: '模型未配置' }, 503))
  await uploading
  assert.equal(state.uploadError.value, '模型未配置')
  assert.equal(state.uploadVersion.value, 0)
  const retry = state.uploadDocument(file)
  uploaded = true
  completeUpload(json(processing, 202))
  await retry
  await tick()
  assert.equal(state.uploadVersion.value, 1)
  assert.equal(state.uploadError.value, '')
  assert.equal(state.documents.value[0]?.id, 'processing')
})