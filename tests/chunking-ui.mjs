import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// Real Vue page; every API request is intercepted before it can reach the backend.
// Run with the docs Vite server on 15174, or set DOCS_URL to an isolated local preview.
const root = path.dirname(fileURLToPath(import.meta.url));
const output = path.join(root, '../.local/test-results/uc004-ui');
const base = process.env.DOCS_URL || 'http://127.0.0.1:15174';
const target = new URL(base);
assert.ok(['127.0.0.1', 'localhost'].includes(target.hostname), 'Use a local test UI');
await fs.mkdir(output, { recursive: true });
const browser = await chromium.launch({ channel: 'chrome', headless: true });
const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN', serviceWorkers: 'block' });
const page = await context.newPage();
const cases = [], errors = [], unexpected = [], chunksRequested = [], deleted = [];
const injection = '<script>window.__uc004Injected=true</script><img src=x onerror="window.__uc004Injected=true">';
const doc = (id, fields = {}) => ({
  id, fileName: `${id}.txt`, format: 'txt', sizeBytes: 123, status: 'READY', chunkCount: 1,
  createdAt: '2026-11-18T00:00:00Z', errorMessage: null, ...fields,
});
let documents = [
  doc('rule', { chunkingStrategy: 'RULE', chunkingFallbackReason: null }),
  doc('ai', { chunkCount: 21, chunkingStrategy: 'LLM', chunkingFallbackReason: null }),
  doc('legacy', { chunkingStrategy: 'LEGACY', chunkingFallbackReason: null }),
  doc('unknown', { chunkingStrategy: null, chunkingFallbackReason: null }),
  doc('old-api'),
  doc('pending', { status: 'PROCESSING', chunkCount: 0, chunkingStrategy: null, chunkingFallbackReason: null }),
  doc('inflight', { status: 'PROCESSING', chunkCount: 0, chunkingStrategy: 'LLM', chunkingFallbackReason: null }),
  doc('fallback', { chunkingStrategy: 'RULE', chunkingFallbackReason: 'MODEL_TIMEOUT' }),
  doc('bad-boundaries', { chunkingStrategy: 'RULE', chunkingFallbackReason: 'INVALID_BOUNDARIES' }),
  doc('call-limit', { chunkingStrategy: 'RULE', chunkingFallbackReason: 'CALL_LIMIT' }),
  doc('unknown-reason', { chunkingStrategy: 'RULE', chunkingFallbackReason: '<script>private upstream body</script>' }),
  doc('unsafe-name', { fileName: `${injection}${'很长的文件名'.repeat(20)}.txt`, chunkingStrategy: 'RULE' }),
];
let listingCalls = 0, finishPending = false, finishUpload = false, deleteCalls = 0, releaseDelete;
const pass = name => { cases.push({ name, result: 'PASS' }); console.log(`PASS ${name}`); };
const card = id => page.locator('.document-card').filter({ has: page.getByRole('heading', {
  name: documents.find(item => item.id === id)?.fileName || `${id}.txt`, exact: true,
}) });
const wait = async (predicate, message) => {
  const deadline = Date.now() + 15000;
  while (Date.now() < deadline) {
    if (await predicate()) return;
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  throw new Error(message);
};
page.on('pageerror', error => errors.push(String(error)));
await context.route('**/api/**', async route => {
  const request = route.request();
  const url = new URL(request.url());
  const json = (body, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
  if (url.origin !== target.origin) {
    unexpected.push(`${request.method()} ${url.pathname}`);
    return route.abort();
  }
  if (url.pathname === '/api/documents' && request.method() === 'GET') {
    listingCalls++;
    if (finishPending) documents = documents.map(item => ['pending', 'inflight'].includes(item.id)
      ? { ...item, status: 'READY', chunkCount: 1, chunkingStrategy: 'LLM' } : item);
    if (finishUpload) documents = documents.map(item => item.id === 'uploaded'
      ? { ...item, status: 'READY', chunkCount: 1, chunkingStrategy: 'RULE', chunkingFallbackReason: 'DOCUMENT_DEADLINE' } : item);
    return json({ items: documents, total: documents.length });
  }
  if (url.pathname === '/api/documents' && request.method() === 'POST') {
    const uploaded = doc('uploaded', { status: 'PROCESSING', chunkCount: 0, chunkingStrategy: null, chunkingFallbackReason: null });
    documents.push(uploaded);
    return json(uploaded, 202);
  }
  const chunks = url.pathname.match(/^\/api\/documents\/([^/]+)\/chunks$/);
  if (chunks && request.method() === 'GET') {
    const id = decodeURIComponent(chunks[1]);
    const selected = documents.find(item => item.id === id);
    const number = Number(url.searchParams.get('page'));
    assert.equal(url.searchParams.get('pageSize'), '20');
    assert.ok(selected && selected.status === 'READY');
    chunksRequested.push({ id, page: number });
    const start = (number - 1) * 20 + 1;
    const items = Array.from({ length: Math.max(0, Math.min(20, selected.chunkCount - start + 1)) }, (_, offset) => ({
      id: `${id}-${start + offset}`, documentId: id, index: start + offset,
      text: id === 'unsafe-name' ? injection : `${id} 原文第 ${start + offset} 段`, pageStart: null, pageEnd: null,
    }));
    return json({ items, total: selected.chunkCount, page: number, pageSize: 20 });
  }
  const deletion = url.pathname.match(/^\/api\/documents\/([^/]+)$/);
  if (deletion && request.method() === 'DELETE') {
    const id = decodeURIComponent(deletion[1]);
    assert.equal(id, 'rule');
    deleteCalls++;
    if (deleteCalls === 1) return json({ code: 'VECTOR_DELETE_FAILED', message: '测试索引暂不可用' }, 502);
    await new Promise(resolve => { releaseDelete = resolve; });
    deleted.push(id);
    documents = documents.filter(item => item.id !== id);
    return route.fulfill({ status: 204 });
  }
  unexpected.push(`${request.method()} ${url.pathname}`);
  return route.abort();
});

try {
  await page.goto(base);
  await page.getByRole('heading', { name: '文档管理', exact: true }).waitFor();
  await card('rule').getByText('规则切块', { exact: true }).waitFor();
  await card('ai').getByText('AI 切块', { exact: true }).waitFor();
  await card('legacy').getByText('历史切块（LEGACY）', { exact: true }).waitFor();
  for (const id of ['unknown', 'old-api']) await card(id).getByText('切块方式未知', { exact: true }).waitFor();
  assert.equal(await card('rule').locator('.chunking-fallback').count(), 0);
  assert.equal(await card('ai').locator('.chunking-fallback').count(), 0);
  pass('RULE, LLM, LEGACY, null and old API fields render the actual strategy');

  for (const id of ['pending', 'inflight']) {
    assert.equal(await card(id).locator('.document-chunking, .chunking-fallback').count(), 0);
    assert.equal(await card(id).getByRole('button', { name: `查看 ${id}.txt 的分块`, exact: true }).isDisabled(), true);
    assert.equal(await card(id).getByRole('button', { name: `删除 ${id}.txt`, exact: true }).isDisabled(), true);
  }
  pass('PROCESSING has no final strategy even when metadata has already been written');

  await card('fallback').getByText('AI 失败回退（规则切块）', { exact: true }).waitFor();
  assert.match(await card('fallback').locator('.chunking-fallback').innerText(), /AI 切块请求超时/);
  assert.match(await card('bad-boundaries').locator('.chunking-fallback').innerText(), /AI 返回的切块边界无效/);
  assert.match(await card('call-limit').locator('.chunking-fallback').innerText(), /达到单份文档的 AI 切块请求上限/);
  assert.match(await card('unknown-reason').locator('.chunking-fallback').innerText(), /AI 切块未完成，已使用规则切块/);
  assert.doesNotMatch(await page.locator('body').innerText(), /private upstream body/);
  pass('fallback uses fixed safe explanations and hides unrecognized upstream content');

  await card('ai').getByRole('button', { name: '查看 ai.txt 的分块', exact: true }).click();
  await page.locator('.chunk-text').filter({ hasText: 'ai 原文第 1 段' }).waitFor();
  assert.equal(await page.locator('.chunk-card').count(), 20);
  await page.getByRole('button', { name: '下一页', exact: true }).click();
  await page.locator('.chunk-text').filter({ hasText: 'ai 原文第 21 段' }).waitFor();
  assert.equal(await page.locator('.chunk-card').count(), 1);
  await card('rule').getByRole('button', { name: '查看 rule.txt 的分块', exact: true }).click();
  await page.locator('.chunk-text').filter({ hasText: 'rule 原文第 1 段' }).waitFor();
  assert.ok(chunksRequested.some(item => item.id === 'ai' && item.page === 2));
  assert.equal(await page.getByRole('button', { name: '上一页，当前已是第一页' }).isDisabled(), true);
  pass('document switching resets pagination and all 21 saved chunks remain accessible');

  await card('unsafe-name').getByRole('button', { name: /^查看 .* 的分块$/ }).click();
  await page.locator('.chunk-text').filter({ hasText: injection }).waitFor();
  assert.equal(await page.locator('.chunk-text').innerText(), injection);
  assert.equal(await page.locator('.document-card script, .document-card img, .chunk-text script, .chunk-text img').count(), 0);
  assert.equal(await page.evaluate(() => window.__uc004Injected), undefined);
  pass('script-shaped file names and chunk content remain plain text and never execute');

  const beforePoll = listingCalls;
  finishPending = true;
  await card('pending').getByText('AI 切块', { exact: true }).waitFor();
  await card('inflight').getByText('AI 切块', { exact: true }).waitFor();
  assert.ok(listingCalls > beforePoll);
  pass('polling replaces pending metadata with the completed persisted strategy');

  await page.locator('input[type=file]').setInputFiles({ name: 'uploaded.txt', mimeType: 'text/plain', buffer: Buffer.from('隔离浏览器验证。') });
  await page.getByRole('button', { name: '上传文档', exact: true }).click();
  await card('uploaded').locator('.status-processing').waitFor();
  assert.equal(await card('uploaded').locator('.document-chunking').count(), 0);
  finishUpload = true;
  await card('uploaded').getByText('AI 失败回退（规则切块）', { exact: true }).waitFor();
  assert.match(await card('uploaded').locator('.chunking-fallback').innerText(), /达到单份文档的 AI 切块处理时限/);
  pass('upload and polling preserve null metadata until the final fallback is ready');

  await card('rule').getByRole('button', { name: '查看 rule.txt 的分块', exact: true }).click();
  await page.locator('.chunk-text').filter({ hasText: 'rule 原文第 1 段' }).waitFor();
  const opener = card('rule').getByRole('button', { name: '删除 rule.txt', exact: true });
  await opener.click();
  await page.getByRole('button', { name: '取消', exact: true }).waitFor();
  assert.equal(await page.getByRole('button', { name: '取消', exact: true }).evaluate(element => element === document.activeElement), true);
  await page.keyboard.press('Escape');
  await wait(async () => await page.getByRole('dialog').count() === 0, 'Escape did not close delete dialog');
  assert.equal(await opener.evaluate(element => element === document.activeElement), true);
  assert.equal(deleteCalls, 0);
  await opener.press('Enter');
  await page.getByRole('button', { name: '确认删除', exact: true }).click();
  await page.getByRole('button', { name: '重试删除', exact: true }).waitFor();
  assert.equal(await card('rule').count(), 1);
  await page.getByRole('button', { name: '重试删除', exact: true }).click();
  await wait(() => Boolean(releaseDelete), 'Retry was not sent');
  assert.equal(await page.getByRole('button', { name: '正在删除…', exact: true }).isDisabled(), true);
  assert.equal(await card('rule').count(), 1);
  releaseDelete();
  await page.getByRole('heading', { name: '选择一份就绪文档', exact: true }).waitFor();
  assert.equal(await page.locator('.document-card').filter({ has: page.getByRole('heading', { name: 'rule.txt', exact: true }) }).count(), 0);
  assert.deepEqual(deleted, ['rule']);
  pass('keyboard cancel restores focus; failed deletion retains the record until retry returns 204');

  for (const [name, viewport] of [['desktop', { width: 1440, height: 900 }], ['mobile', { width: 390, height: 844 }]]) {
    await page.setViewportSize(viewport);
    const metrics = await page.evaluate(() => ({ width: innerWidth, scroll: document.documentElement.scrollWidth }));
    assert.ok(metrics.scroll <= metrics.width + 1, JSON.stringify(metrics));
    await card('unsafe-name').scrollIntoViewIfNeeded();
    const bounds = await card('unsafe-name').boundingBox();
    assert.ok(bounds.x >= 0 && bounds.x + bounds.width <= viewport.width + 1, JSON.stringify(bounds));
    await card('fallback').scrollIntoViewIfNeeded();
    await page.screenshot({ path: path.join(output, `documents-${name}.png`), fullPage: false });
    pass(`${viewport.width}x${viewport.height} preserves long names, fallback text and page width`);
  }
  assert.deepEqual(unexpected, []);
  assert.deepEqual(errors, []);
  assert.equal(await page.evaluate(() => window.__uc004Injected), undefined);
  pass('every API request intercepted; no uncaught browser errors or script injection');
} catch (error) {
  cases.push({ name: 'UI verification', result: 'FAIL', detail: String(error) });
  await page.screenshot({ path: path.join(output, 'failure.png'), fullPage: true }).catch(() => {});
  throw error;
} finally {
  await fs.writeFile(path.join(output, 'result.json'), JSON.stringify({ mode: 'mocked-api-real-browser', base, cases, errors, unexpected }, null, 2));
  await browser.close();
}
