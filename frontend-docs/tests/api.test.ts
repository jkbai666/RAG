import assert from 'node:assert/strict'
import test from 'node:test'
import { api, ApiError } from '../src/lib/api.ts'

function json(value: unknown, status = 200) { return new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } }) }

test('分块请求遵循固定契约，路径编码、页码和每页20块', async t => {
  const fetchMock = t.mock.method(globalThis, 'fetch', async (url: string) => {
    assert.equal(url, '/api/documents/a%2Fb/chunks?page=2&pageSize=20')
    return json({ items: [], total: 0, page: 2, pageSize: 20 })
  })
  assert.equal((await api.listChunks('a/b', 2)).page, 2)
  assert.equal(fetchMock.mock.callCount(), 1)
})

test('上传仅发送一个 multipart file，不手写 Content-Type', async t => {
  t.mock.method(globalThis, 'fetch', async (url: string, init: RequestInit) => {
    assert.equal(url, '/api/documents')
    assert.equal(init.method, 'POST')
    assert.ok(init.body instanceof FormData)
    assert.equal(Array.from(init.body.keys()).join(','), 'file')
    assert.equal((init.body.get('file') as File).name, '资料.md')
    assert.equal(new Headers(init.headers).get('Content-Type'), null)
    return json({ id: 'new' }, 202)
  })
  assert.equal((await api.uploadDocument(new File(['资料'], '资料.md'))).id, 'new')
})

test('204删除成功；502/409保留服务端原因和错误码', async t => {
  const fetchMock = t.mock.method(globalThis, 'fetch', async () => new Response(null, { status: 204 }))
  assert.equal(await api.deleteDocument('1'), undefined)
  fetchMock.mock.mockImplementation(async () => json({ code: 'VECTOR_DELETE_FAILED', message: 'Chroma 暂不可用' }, 502))
  await assert.rejects(api.deleteDocument('1'), (error: unknown) => error instanceof ApiError && error.message === 'Chroma 暂不可用' && error.code === 'VECTOR_DELETE_FAILED' && error.status === 502)
  fetchMock.mock.mockImplementation(async () => json({ code: 'DOCUMENT_PROCESSING', message: '处理中不可删除' }, 409))
  await assert.rejects(api.deleteDocument('1'), /处理中不可删除/)
})

test('网络异常、非JSON代理错误和坏响应都不伪装空数据', async t => {
  const fetchMock = t.mock.method(globalThis, 'fetch', async () => { throw new TypeError('fetch failed') })
  await assert.rejects(api.listDocuments(), /无法连接后端服务/)
  fetchMock.mock.mockImplementation(async () => new Response('<html>Bad gateway</html>', { status: 502 }))
  await assert.rejects(api.listDocuments(), /HTTP 502/)
  fetchMock.mock.mockImplementation(async () => new Response('not-json', { status: 200 }))
  await assert.rejects(api.listDocuments(), /无法读取的数据/)
})
test('删除仅接受204，其他成功状态也不冒充已删除', async t => {
  t.mock.method(globalThis, 'fetch', async () => json({}, 200))
  await assert.rejects(api.deleteDocument('1'), (error: unknown) => error instanceof ApiError && error.code === 'UNEXPECTED_STATUS')
})
