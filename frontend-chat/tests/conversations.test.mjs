import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createConversation, getConversation, sendMessage } from '../src/api.ts'
import { createChatSession, SESSION_STORAGE_KEY } from '../src/chat-session.ts'

const id = '11111111-1111-4111-8111-111111111111'
const other = '22222222-2222-4222-8222-222222222222'
const requestId = '33333333-3333-4333-8333-333333333333'
const secondRequest = '44444444-4444-4444-8444-444444444444'
const ready = { readyDocuments: 1, totalDocuments: 1, modelConfigured: true }
const response = { answer: '历史回答', grounded: true, sources: [{ chunkId: 'c1', documentId: 'd1', fileName: '原文.txt', chunkIndex: 1, text: '原文快照', pageStart: null, pageEnd: null }] }
const exchange = { requestId, question: '历史问题', response, createdAt: '2026-10-20T07:00:00Z' }
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
const memory = (value = null) => {
  const map = new Map(value ? [[SESSION_STORAGE_KEY, JSON.stringify(value)]] : [])
  return { getItem: key => map.get(key) ?? null, setItem: (key, item) => map.set(key, item), saved: () => JSON.parse(map.get(SESSION_STORAGE_KEY)) }
}
const defaults = {
  getKnowledgeStatus: async () => ready,
  createConversation: async () => ({ id: other, exchanges: [] }),
  getConversation: async input => ({ id: input, exchanges: [] }),
  sendMessage: async () => response,
}
const setup = (overrides = {}, storage = memory(), uuid = () => requestId) => ({ session: createChatSession({ ...defaults, ...overrides }, storage, uuid), storage })
const prepare = async session => { await Promise.all([session.initialize(), session.refreshStatus()]) }

test('仅恢复已保存ID，历史与来源来自服务器而非浏览器', async () => {
  let created = 0, requested
  const { session, storage } = setup({ createConversation: async () => { created++; return { id: other, exchanges: [] } }, getConversation: async input => { requested = input; return { id, exchanges: [exchange] } } }, memory({ conversationId: id, pending: null }))
  await prepare(session)
  assert.equal(requested, id); assert.equal(created, 0)
  assert.equal(session.state.messages[0].response.sources[0].text, '原文快照')
  assert.deepEqual(storage.saved(), { conversationId: id, pending: null })
  assert.equal(session.state.sessionReady, true)
})

test('恢复失败保留ID及pending，显式重试恢复而不静默创建', async () => {
  let fail = true, created = 0
  const storage = memory({ conversationId: id, pending: { requestId, question: '未完成问题' } })
  const { session } = setup({ createConversation: async () => { created++; throw Error('不该调用') }, getConversation: async () => { if (fail) throw Error('后端暂不可用'); return { id, exchanges: [] } } }, storage)
  await prepare(session)
  assert.equal(session.state.conversationId, id); assert.equal(created, 0); assert.match(session.state.sessionError, /暂不可用/)
  session.state.draft = '问题'; assert.equal(await session.send(), false)
  assert.equal(storage.saved().pending.requestId, requestId)
  fail = false; await session.initialize(true)
  assert.equal(session.state.sessionError, ''); assert.equal(session.state.draft, '未完成问题')
})

test('浏览器断线后服务端已成功，刷新恢复只显示一轮并清理pending', async () => {
  const storage = memory({ conversationId: id, pending: { requestId, question: exchange.question } })
  let sends = 0
  const { session } = setup({ getConversation: async () => ({ id, exchanges: [exchange] }), sendMessage: async () => { sends++; return response } }, storage)
  await prepare(session)
  assert.equal(session.state.messages.length, 1); assert.equal(session.state.messages[0].phase, 'answered')
  assert.equal(storage.saved().pending, null); assert.equal(session.state.draft, ''); assert.equal(sends, 0)
})

test('浏览器断线且服务端尚未返回成功，刷新重试复用requestId', async () => {
  const storage = memory({ conversationId: id, pending: { requestId, question: '等待中的问题' } })
  const calls = []
  const { session } = setup({ sendMessage: async (...args) => { calls.push(args); return response } }, storage, () => { throw Error('不能生成新ID') })
  await prepare(session)
  assert.equal(session.state.draft, '等待中的问题'); assert.equal(session.state.messages[0].phase, 'failed')
  assert.equal(await session.send(), true)
  assert.deepEqual(calls[0].slice(0, 3), [id, '等待中的问题', requestId])
  assert.equal(session.state.messages.length, 1); assert.equal(storage.saved().pending, null)
})

test('失败保留输入和ID，同问题重试复用ID，改变问题使用新ID', async () => {
  const calls = []; let uuidCalls = 0
  const { session, storage } = setup({ sendMessage: async (...args) => { calls.push(args); throw Error('模型不可用') } }, memory(), () => ++uuidCalls === 1 ? requestId : secondRequest)
  await prepare(session); session.state.draft = ' 原问题 '
  await session.send(); assert.equal(session.state.draft, ' 原问题 ')
  await session.send(); assert.equal(calls[0][2], calls[1][2]); assert.equal(uuidCalls, 1)
  session.state.draft = '新问题'; await session.send()
  assert.equal(calls[2][2], secondRequest); assert.deepEqual(storage.saved().pending, { requestId: secondRequest, question: '新问题' })
  assert.equal(JSON.stringify(storage.saved()).includes('sources'), false)
})

test('新对话只调用创建、不删除旧会话或知识库，不带旧历史', async () => {
  let created = 0
  const { session, storage } = setup({ getConversation: async () => ({ id, exchanges: [exchange] }), createConversation: async () => { created++; return { id: other, exchanges: [] } } }, memory({ conversationId: id, pending: null }))
  await prepare(session); session.state.draft = '草稿'
  assert.equal(await session.newConversation(), true); assert.equal(created, 1)
  assert.equal(session.state.conversationId, other); assert.equal(session.state.messages.length, 0); assert.equal(session.state.draft, '')
  assert.deepEqual(storage.saved(), { conversationId: other, pending: null })
})

test('发送中禁止新对话/恢复切换，重复发送被阻止', async () => {
  const wait = deferred(); let creates = 0, sends = 0
  const { session } = setup({ createConversation: async () => { creates++; return { id, exchanges: [] } }, sendMessage: async () => { sends++; return wait.promise } })
  await prepare(session); session.state.draft = '问题'; const task = session.send()
  assert.equal(session.state.sending, true); assert.equal(await session.newConversation(), false)
  await session.initialize(true); assert.equal(await session.send(), false)
  assert.equal(creates, 1); assert.equal(sends, 1)
  wait.resolve(response); assert.equal(await task, true)
})

test('新对话创建失败保留旧会话与消息，可重试恢复', async () => {
  const { session, storage } = setup({ getConversation: async () => ({ id, exchanges: [exchange] }), createConversation: async () => { throw Error('创建失败') } }, memory({ conversationId: id, pending: null }))
  await prepare(session); assert.equal(await session.newConversation(), false)
  assert.equal(session.state.conversationId, id); assert.equal(session.state.messages.length, 1)
  assert.equal(storage.saved().conversationId, id); await session.initialize(true); assert.equal(session.state.sessionError, '')
})

test('卸载后迟到的恢复/新对话结果不修改状态与本地存储', async () => {
  for (const creating of [false, true]) {
    const wait = deferred(); let signal
    const storage = memory({ conversationId: id, pending: null })
    const { session } = setup({ getConversation: (_id, value) => { signal = value; return wait.promise }, createConversation: value => { signal = value; return wait.promise } }, storage)
    const task = creating ? session.newConversation() : session.initialize()
    session.dispose(); assert.equal(signal.aborted, true); wait.resolve({ id: other, exchanges: [exchange] }); await task
    assert.equal(storage.saved().conversationId, id); assert.equal(session.state.messages.length, 0)
  }
})

test('卸载时发送中断保留pending，迟到成功不能清除恢复凭据', async () => {
  const wait = deferred(); let signal
  const { session, storage } = setup({ sendMessage: (_id, _q, _r, value) => { signal = value; return wait.promise } })
  await prepare(session); session.state.draft = '问题'; const task = session.send(); session.dispose()
  assert.equal(signal.aborted, true); wait.resolve(response); assert.equal(await task, false)
  assert.equal(storage.saved().pending.requestId, requestId); assert.equal(session.state.messages[0].response, null)
})

test('存储失败时不发出收费消息，保持输入并提示恢复', async () => {
  let blocked = false, sends = 0
  const storage = memory(); const original = storage.setItem
  storage.setItem = (...args) => { if (blocked) throw Error('QuotaExceeded'); original(...args) }
  const { session } = setup({ sendMessage: async () => { sends++; return response } }, storage)
  await prepare(session); blocked = true; session.state.draft = '原问题'
  assert.equal(await session.send(), false); assert.equal(sends, 0); assert.equal(session.state.draft, '原问题')
  assert.match(session.state.sessionError, /无法保存/)
})

test('畸形本地标识不静默覆盖，只有显式新对话可替换', async () => {
  const storage = memory({ conversationId: 'broken', pending: null }); let creates = 0
  const { session } = setup({ createConversation: async () => { creates++; return { id: other, exchanges: [] } } }, storage)
  await prepare(session); assert.equal(creates, 0); assert.equal(session.state.conversationId, 'broken')
  assert.match(session.state.sessionError, /无效/); assert.equal(storage.saved().conversationId, 'broken')
  await session.newConversation(); assert.equal(creates, 1); assert.equal(storage.saved().conversationId, other)
})

test('API严格使用共享会话路径、requestId和原Answer，不发送浏览器历史', async t => {
  const calls = []
  t.mock.method(globalThis, 'fetch', async (url, init) => {
    calls.push({ url, init })
    return Response.json(url.endsWith('/messages') ? response : { id, exchanges: init.method === 'POST' ? [] : [exchange] })
  })
  assert.deepEqual(await createConversation(), { id, exchanges: [] })
  assert.deepEqual(await getConversation(id), { id, exchanges: [exchange] })
  assert.deepEqual(await sendMessage(id, '追问', requestId), response)
  assert.deepEqual(calls.map(c => c.url), ['/api/conversations', '/api/conversations/' + id, '/api/conversations/' + id + '/messages'])
  assert.deepEqual(JSON.parse(calls[2].init.body), { question: '追问', requestId })
})

test('恢复API拒绝错误会话ID、伪造来源和重复请求历史', async t => {
  t.mock.method(globalThis, 'fetch', async () => Response.json({ id: other, exchanges: [] }))
  await assert.rejects(getConversation(id), /标识不一致/)
  globalThis.fetch.mock.mockImplementation(async () => Response.json({ id, exchanges: [exchange, exchange] }))
  await assert.rejects(getConversation(id), /历史格式异常/)
  globalThis.fetch.mock.mockImplementation(async () => Response.json({ id, exchanges: [{ ...exchange, response: { answer: 'x', grounded: true, sources: [] } }] }))
  await assert.rejects(getConversation(id), /格式异常/)
})


test('首次创建后存储失败仍保留服务器ID，重试恢复不重复新建', async () => {
  let creates = 0, gets = 0, fail = true
  const storage = memory(); const original = storage.setItem
  storage.setItem = (...args) => { if (fail) throw Error('存储不可用'); original(...args) }
  const { session } = setup({
    createConversation: async () => { creates++; return { id, exchanges: [] } },
    getConversation: async value => { gets++; return { id: value, exchanges: [] } },
  }, storage)
  await prepare(session)
  assert.equal(session.state.conversationId, id); assert.equal(creates, 1)
  fail = false; await session.initialize(true)
  assert.equal(creates, 1); assert.equal(gets, 1); assert.equal(storage.saved().conversationId, id)
})
