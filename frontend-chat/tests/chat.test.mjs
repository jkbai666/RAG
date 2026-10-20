import { test } from 'node:test'
import assert from 'node:assert/strict'
import { askQuestion, getKnowledgeStatus, ApiError } from '../src/api.ts'
import { createChatSession, unicodeLength, validateQuestion, statusBlockReason, documentsUrl, pageLabel } from '../src/chat-session.ts'

const ready = { readyDocuments: 1, totalDocuments: 1, modelConfigured: true }
const source = { chunkId: 'c1', documentId: 'd1', fileName: '制度说明.pdf', chunkIndex: 2, text: '来源原文\n第二行', pageStart: 3, pageEnd: 4 }
const grounded = { answer: '根据文档给出的回答。', grounded: true, sources: [source] }
const noEvidence = { answer: '知识库中没有足够依据。', grounded: false, sources: [] }
const deferred = () => {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
const makeSession = (overrides = {}) => createChatSession({
  getKnowledgeStatus: async () => ready,
  askQuestion: async () => grounded,
  ...overrides,
})

test('以 Unicode 码点而非 UTF-16 单元计算长度，限制 2000 字符', () => {
  assert.equal(unicodeLength('中文😀'), 3)
  assert.equal(validateQuestion('😀'.repeat(2000)), '')
  assert.match(validateQuestion('😀'.repeat(2001)), /2000/)
  assert.match(validateQuestion(' \n\t'), /请输入/)
  assert.match(validateQuestion(' '.repeat(2000) + '问'), /2000/)
})

test('文档链接只允许 HTTP(S)，保留合法配置', () => {
  assert.equal(documentsUrl(undefined), 'http://127.0.0.1:5174/')
  assert.equal(documentsUrl('https://docs.example.test/path'), 'https://docs.example.test/path')
  assert.equal(documentsUrl('javascript:alert(1)'), 'http://127.0.0.1:5174/')
  assert.equal(documentsUrl('not a url'), 'http://127.0.0.1:5174/')
})

test('PDF 页码包括单页、范围及空值', () => {
  assert.equal(pageLabel(null, null), '')
  assert.equal(pageLabel(3, 3), '第 3 页')
  assert.equal(pageLabel(3, 5), '第 3–5 页')
  assert.equal(pageLabel(null, 4), '第 4 页')
})

test('未获取状态、空库、配置缺失均阻止发送', async () => {
  let calls = 0
  const session = makeSession({ askQuestion: async () => { calls++; return grounded } })
  session.state.draft = '有什么规定？'
  assert.equal(await session.send(), false)
  await session.refreshStatus()
  session.state.status = { ...ready, modelConfigured: false }
  assert.match(statusBlockReason(session.state), /模型尚未配置/)
  assert.equal(await session.send(), false)
  session.state.status = { ...ready, readyDocuments: 0 }
  assert.match(statusBlockReason(session.state), /导入/)
  assert.equal(await session.send(), false)
  assert.equal(calls, 0)
})

test('空白和过长问题不触发 API', async () => {
  let calls = 0
  const session = makeSession({ askQuestion: async () => { calls++; return grounded } })
  await session.refreshStatus()
  for (const draft of ['  ', '中'.repeat(2001)]) {
    session.state.draft = draft
    assert.equal(await session.send(), false)
  }
  assert.equal(calls, 0)
  assert.equal(session.state.messages.length, 0)
})

test('等待期间阻止重复发送，成功才清空输入，独立提问不带历史', async () => {
  const wait = deferred()
  const calls = []
  const session = makeSession({ askQuestion: (question) => { calls.push(question); return wait.promise } })
  await session.refreshStatus()
  session.state.draft = '  文档中有什么规定？  '
  const pending = session.send()
  assert.equal(session.state.sending, true)
  assert.equal(session.state.messages[0].phase, 'pending')
  assert.equal(session.state.draft, '  文档中有什么规定？  ')
  assert.equal(await session.send(), false)
  wait.resolve(grounded)
  assert.equal(await pending, true)
  assert.equal(session.state.sending, false)
  assert.equal(session.state.draft, '')
  assert.deepEqual(calls, ['文档中有什么规定？'])
  session.state.draft = '第二个独立问题'
  await session.send()
  assert.deepEqual(calls, ['文档中有什么规定？', '第二个独立问题'])
  assert.equal(session.state.messages.length, 2)
})

test('失败保留原输入，同一问题重试复用失败项且能成功', async () => {
  let calls = 0
  const session = makeSession({ askQuestion: async () => {
    if (++calls === 1) throw new ApiError('模型不可用', 502, 'MODEL_ERROR')
    return grounded
  } })
  await session.refreshStatus()
  session.state.draft = ' 原问题\n第二行 '
  assert.equal(await session.send(), false)
  assert.equal(session.state.draft, ' 原问题\n第二行 ')
  assert.equal(session.state.messages[0].phase, 'failed')
  assert.equal(session.state.messages[0].error, '模型不可用')
  assert.equal(await session.send(), true)
  assert.equal(session.state.messages.length, 1)
  assert.equal(session.state.messages[0].phase, 'answered')
  assert.equal(session.state.messages[0].error, '')
})

test('失败后允许修改问题重新发送', async () => {
  const questions = []
  const session = makeSession({ askQuestion: async (question) => {
    questions.push(question)
    if (questions.length === 1) throw new Error('服务失败')
    return grounded
  } })
  await session.refreshStatus()
  session.state.draft = '旧问题'
  await session.send()
  session.state.draft = '新问题'
  await session.send()
  assert.deepEqual(questions, ['旧问题', '新问题'])
  assert.equal(session.state.messages.length, 2)
})

test('grounded=false 是正常回答，不当作异常', async () => {
  const session = makeSession({ askQuestion: async () => noEvidence })
  await session.refreshStatus()
  session.state.draft = '文档以外的问题'
  assert.equal(await session.send(), true)
  assert.equal(session.state.messages[0].response.grounded, false)
  assert.equal(session.state.messages[0].phase, 'answered')
  assert.equal(session.state.messages[0].error, '')
})

test('后续状态变化不删除消息，来源快照不随外部对象改变', async () => {
  const response = { ...grounded, sources: [{ ...source }] }
  let currentStatus = ready
  const session = makeSession({ getKnowledgeStatus: async () => currentStatus, askQuestion: async () => response })
  await session.refreshStatus()
  session.state.draft = '问题'
  await session.send()
  response.sources[0].text = '后续变化'
  currentStatus = { readyDocuments: 0, totalDocuments: 0, modelConfigured: false }
  await session.refreshStatus()
  assert.equal(session.state.messages.length, 1)
  assert.equal(session.state.messages[0].response.sources[0].text, source.text)
  assert.equal(session.state.status.readyDocuments, 0)
  assert.equal(makeSession().state.messages.length, 0)
})

test('状态获取失败阻止沿用旧状态发送，重试恢复', async () => {
  let fail = false
  const session = makeSession({ getKnowledgeStatus: async () => {
    if (fail) throw new Error('后端未启动')
    return ready
  } })
  await session.refreshStatus()
  fail = true
  await session.refreshStatus()
  session.state.draft = '问题'
  assert.equal(await session.send(), false)
  assert.equal(session.state.statusError, '后端未启动')
  fail = false
  await session.refreshStatus()
  assert.equal(session.state.statusError, '')
  assert.equal(await session.send(), true)
})

test('状态并发刷新以最后一次为准，忽略过时结果', async () => {
  const first = deferred(), second = deferred()
  let calls = 0
  const session = makeSession({ getKnowledgeStatus: () => ++calls === 1 ? first.promise : second.promise })
  const one = session.refreshStatus()
  const two = session.refreshStatus()
  second.resolve(ready)
  await two
  first.resolve({ readyDocuments: 0, totalDocuments: 0, modelConfigured: false })
  await one
  assert.deepEqual({ ...session.state.status }, ready)
  assert.equal(session.state.statusLoading, false)
})

test('卸载时取消请求，不再写回结果', async () => {
  const wait = deferred()
  let signal
  const session = makeSession({ askQuestion: (_question, inputSignal) => { signal = inputSignal; return wait.promise } })
  await session.refreshStatus()
  session.state.draft = '问题'
  const pending = session.send()
  session.dispose()
  assert.equal(signal.aborted, true)
  wait.resolve(grounded)
  assert.equal(await pending, false)
  assert.equal(session.state.messages[0].response, null)
  assert.equal(await session.send(), false)
})

test('空库或配置错误会自动重新查询状态', async () => {
  let calls = 0
  const session = makeSession({
    getKnowledgeStatus: async () => { calls++; return ready },
    askQuestion: async () => { throw new ApiError('空库', 409, 'EMPTY_KNOWLEDGE_BASE') },
  })
  await session.refreshStatus()
  session.state.draft = '问题'
  await session.send()
  assert.equal(calls, 2)
  assert.equal(session.state.draft, '问题')
})

test('API 使用固定契约路径及仅包含 question 的 JSON', async (t) => {
  const calls = []
  t.mock.method(globalThis, 'fetch', async (url, init) => {
    calls.push({ url, init })
    return Response.json(url.endsWith('/status') ? ready : grounded)
  })
  assert.deepEqual(await getKnowledgeStatus(), ready)
  assert.deepEqual(await askQuestion('中文问题'), grounded)
  assert.equal(calls[0].url, '/api/knowledge/status')
  assert.equal(calls[1].url, '/api/chat')
  assert.equal(calls[1].init.method, 'POST')
  assert.equal(calls[1].init.headers['Content-Type'], 'application/json')
  assert.deepEqual(JSON.parse(calls[1].init.body), { question: '中文问题' })
})

test('API 接受无依据响应并保留纯文本，包括 HTML 形状的原文', async (t) => {
  t.mock.method(globalThis, 'fetch', async () => Response.json(noEvidence))
  assert.deepEqual(await askQuestion('问题'), noEvidence)
  const value = { ...grounded, answer: '<script>alert(1)</script>', sources: [{ ...source, text: '<img src=x onerror=alert(1)>' }] }
  globalThis.fetch.mock.mockImplementation(async () => Response.json(value))
  assert.deepEqual(await askQuestion('问题'), value)
})

test('API 错误使用中文 message，并明确识别空库', async (t) => {
  t.mock.method(globalThis, 'fetch', async () => Response.json({ code: 'MODEL_ERROR', message: '生成模型暂不可用' }, { status: 502 }))
  await assert.rejects(askQuestion('问题'), (error) => error instanceof ApiError && error.status === 502 && error.message === '生成模型暂不可用')
  globalThis.fetch.mock.mockImplementation(async () => Response.json({ code: 'EMPTY_KNOWLEDGE_BASE', message: 'Empty' }, { status: 409 }))
  await assert.rejects(askQuestion('问题'), /请先导入文档/)
})

test('非 JSON 网关错误、网络中断有明确提示', async (t) => {
  t.mock.method(globalThis, 'fetch', async () => new Response('<html>Gateway error</html>', { status: 504 }))
  await assert.rejects(askQuestion('问题'), /响应超时/)
  globalThis.fetch.mock.mockImplementation(async () => { throw new TypeError('Failed to fetch') })
  await assert.rejects(getKnowledgeStatus(), /无法连接后端服务/)
})

test('契约错误不伪装成功，不展示未经校验来源', async (t) => {
  t.mock.method(globalThis, 'fetch', async () => Response.json({ answer: '回答', grounded: true, sources: [] }))
  await assert.rejects(askQuestion('问题'), /格式异常/)
  globalThis.fetch.mock.mockImplementation(async () => Response.json({ ...grounded, sources: [{ ...source, chunkIndex: 0 }] }))
  await assert.rejects(askQuestion('问题'), /格式异常/)
  globalThis.fetch.mock.mockImplementation(async () => Response.json({ ...ready, readyDocuments: -1 }))
  await assert.rejects(getKnowledgeStatus(), /状态格式异常/)
})
