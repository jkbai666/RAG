import assert from 'node:assert/strict'
import test from 'node:test'
import { chunkingFallbackLabel, chunkingLabel } from '../src/lib/display.ts'
import { api } from '../src/lib/api.ts'
import type { KnowledgeDocument } from '../src/lib/types.ts'

const ready: KnowledgeDocument = {
  id: 'doc', fileName: '资料.txt', format: 'txt', sizeBytes: 10,
  status: 'READY', chunkCount: 1, createdAt: '2026-11-18T00:00:00Z', errorMessage: null,
}

test('旧 API 缺字段、空值和历史标记均不冒充 AI 或新规则切块', () => {
  assert.equal(chunkingLabel(ready), '切块方式未知')
  assert.equal(chunkingLabel({ ...ready, chunkingStrategy: null, chunkingFallbackReason: null }), '切块方式未知')
  assert.equal(chunkingLabel({ ...ready, chunkingStrategy: 'LEGACY' }), '历史切块（LEGACY）')
})

test('根据持久化策略区分 AI、规则和 AI 失败回退', () => {
  assert.equal(chunkingLabel({ ...ready, chunkingStrategy: 'LLM', chunkingFallbackReason: null }), 'AI 切块')
  assert.equal(chunkingLabel({ ...ready, chunkingStrategy: 'RULE', chunkingFallbackReason: null }), '规则切块')
  assert.equal(chunkingLabel({ ...ready, chunkingStrategy: 'RULE', chunkingFallbackReason: 'AI 切块超时' }), 'AI 失败回退（规则切块）')
  assert.equal(chunkingLabel({ ...ready, chunkingStrategy: 'LLM', chunkingFallbackReason: 'stale' }), 'AI 切块')
  assert.equal(chunkingLabel({ ...ready, chunkingStrategy: 'RULE', chunkingFallbackReason: '' }), '规则切块')
})

test('导入尚在处理中，即使策略已写入也不显示最终结论', () => {
  for (const chunkingStrategy of [undefined, null, 'LEGACY', 'RULE', 'LLM'] as const) {
    assert.equal(chunkingLabel({ ...ready, status: 'PROCESSING', chunkingStrategy, chunkingFallbackReason: 'AI 切块超时' }), '')
  }
  assert.equal(chunkingLabel({ ...ready, status: 'FAILED' }), '切块方式未知')
  assert.equal(chunkingLabel({ ...ready, status: 'FAILED', chunkingStrategy: 'RULE', chunkingFallbackReason: 'AI 切块超时' }), 'AI 失败回退（规则切块）')
})

test('列表与上传响应兼容旧字段，并保留新接口的可空元数据', async t => {
  const legacy = { ...ready, id: 'legacy', chunkingStrategy: 'LEGACY' }
  const fallback = { ...ready, id: 'fallback', chunkingStrategy: 'RULE', chunkingFallbackReason: 'AI 切块超时' }
  const pending = { ...ready, id: 'pending', status: 'PROCESSING', chunkingStrategy: null, chunkingFallbackReason: null }
  t.mock.method(globalThis, 'fetch', async (_url: string, init: RequestInit) => new Response(
    JSON.stringify(init.method === 'POST' ? pending : { items: [ready, legacy, fallback], total: 3 }),
    { status: init.method === 'POST' ? 202 : 200, headers: { 'Content-Type': 'application/json' } },
  ))
  const documents = await api.listDocuments()
  assert.deepEqual(documents.items.map(chunkingLabel), ['切块方式未知', '历史切块（LEGACY）', 'AI 失败回退（规则切块）'])
  assert.equal(documents.items[2]?.chunkingFallbackReason, 'AI 切块超时')
  const uploaded = await api.uploadDocument(new File(['资料'], '资料.txt'))
  assert.equal(uploaded.chunkingStrategy, null)
  assert.equal(chunkingLabel(uploaded), '')
})

test('回退原因仅显示受控说明，不回显未知上游消息或 HTML', () => {
  const fallback: KnowledgeDocument = { ...ready, chunkingStrategy: 'RULE', chunkingFallbackReason: 'MODEL_TIMEOUT' }
  assert.equal(chunkingFallbackLabel(fallback), 'AI 切块请求超时')
  for (const reason of ['AI_CHUNKING_FAILED', '<script>private upstream body</script>', 'constructor', '__proto__']) {
    assert.equal(chunkingFallbackLabel({ ...fallback, chunkingFallbackReason: reason }), 'AI 切块未完成，已使用规则切块')
  }
  assert.equal(chunkingFallbackLabel({ ...fallback, status: 'PROCESSING' }), '')
  assert.equal(chunkingFallbackLabel({ ...fallback, chunkingStrategy: 'LLM' }), '')
  assert.equal(chunkingFallbackLabel(ready), '')
})
