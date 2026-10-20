import assert from 'node:assert/strict'
import test from 'node:test'
import { characterCount, formatDate, formatSize, getChatUrl, MAX_FILE_SIZE, newestFirst, pageLabel, validateFile } from '../src/lib/display.ts'
import type { KnowledgeDocument } from '../src/lib/types.ts'

const doc = (id: string, createdAt: string): KnowledgeDocument => ({ id, createdAt, fileName: '资料.txt', format: 'txt', sizeBytes: 12, status: 'READY', chunkCount: 1, errorMessage: null })

test('单文件校验：格式、大小边界、空文件和未选择', () => {
  for (const name of ['资料.txt', '资料.md', '资料.PDF']) assert.equal(validateFile({ name, size: MAX_FILE_SIZE }), '')
  assert.match(validateFile({ name: '资料.txt', size: MAX_FILE_SIZE + 1 }), /超过 20 MiB/)
  assert.match(validateFile({ name: '资料.txt', size: 0 }), /文件为空/)
  assert.match(validateFile({ name: '资料.docx', size: 2 }), /不支持/)
  assert.match(validateFile({ name: '资料.pdf.exe', size: 2 }), /不支持/)
  assert.match(validateFile(null), /请先选择/)
})

test('按创建时间倒序，保留原数组和完整文件名', () => {
  const items = [doc('a', '2026-10-19T00:00:00Z'), doc('b', '2026-10-20T00:00:00Z')]
  assert.deepEqual(newestFirst(items).map(item => item.id), ['b', 'a'])
  assert.equal(items[0]?.id, 'a')
})

test('字符数按 Unicode 码点计算，页码不伪造', () => {
  assert.equal(characterCount('中😀\nA'), 4)
  assert.equal(pageLabel({ pageStart: 1, pageEnd: 3 }), '第 1–3 页')
  assert.equal(pageLabel({ pageStart: 2, pageEnd: 2 }), '第 2 页')
  assert.equal(pageLabel({ pageStart: null, pageEnd: null }), '页码未提供')
  assert.equal(formatSize(20 * 1024 * 1024), '20.0 MiB')
  assert.equal(formatDate('unknown'), 'unknown')
})

test('问答链接接受 HTTP(S)，拒绝脚本协议和非法值', () => {
  assert.equal(getChatUrl(undefined), 'http://127.0.0.1:5173/')
  assert.equal(getChatUrl('https://example.test/chat'), 'https://example.test/chat')
  assert.equal(getChatUrl('javascript:alert(1)'), 'http://127.0.0.1:5173')
  assert.equal(getChatUrl('not a url'), 'http://127.0.0.1:5173')
})