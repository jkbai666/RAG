import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';

// Development UI checks against the real Vue page. Every API request is intercepted.
// These checks do not validate cloud answers or real MySQL persistence.
const root = path.dirname(fileURLToPath(import.meta.url));
const output = path.join(root, '../.local/test-results/uc003-ui');
await fs.mkdir(output, { recursive: true });
const browser = await chromium.launch({ channel: 'chrome', headless: true });
const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' });
const page = await context.newPage();
const conversations = new Map();
const posts = [];
const errors = [];
const unexpected = [];
const cases = [];
let createCount = 0;
let rejectRestore = false;
let abortNextMessage = false;
let saveBeforeAbort = false;
let releaseMessage;
let delayMessage = false;
const source = { chunkId: randomUUID(), documentId: randomUUID(), fileName: '开发验证.txt', chunkIndex: 1,
  pageStart: 1, pageEnd: 2, text: '负责人是林溪。<script>window.__ragInjected=true</script>' };
const answer = { answer: '负责人是林溪。', grounded: true, sources: [source] };
const pass = name => { cases.push({ name, result: 'PASS' }); console.log('PASS ' + name); };
const wait = async (fn, description) => {
  const deadline = Date.now() + 15000;
  while (Date.now() < deadline) {
    if (await fn()) return;
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  throw new Error(description);
};
page.on('pageerror', error => errors.push(String(error)));
await context.route('**/api/**', async route => {
  const req = route.request();
  const url = new URL(req.url());
  const json = (body, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
  if (url.pathname === '/api/knowledge/status') return json({ readyDocuments: 1, totalDocuments: 1, modelConfigured: true });
  if (url.pathname === '/api/conversations' && req.method() === 'POST') {
    const id = randomUUID();
    const conversation = { id, exchanges: [] };
    conversations.set(id, conversation); createCount++;
    return json(conversation);
  }
  const match = url.pathname.match(/^\/api\/conversations\/([^/]+)(\/messages)?$/);
  if (match) {
    const conversation = conversations.get(match[1]);
    if (!conversation) return json({ code: 'NOT_FOUND', message: '会话不存在。' }, 404);
    if (req.method() === 'GET' && !match[2]) {
      if (rejectRestore) return json({ code: 'UNAVAILABLE', message: '暂时无法恢复会话，请重试。' }, 503);
      return json(conversation);
    }
    if (req.method() === 'POST' && match[2]) {
      const body = req.postDataJSON();
      posts.push({ conversationId: conversation.id, ...body });
      if (delayMessage) await new Promise(resolve => { releaseMessage = resolve; });
      const existing = conversation.exchanges.find(turn => turn.requestId === body.requestId);
      if (existing && existing.question !== body.question) return json({ code: 'CONFLICT', message: '请求标识冲突。' }, 409);
      const turn = existing ?? { requestId: body.requestId, question: body.question, response: answer, createdAt: new Date().toISOString() };
      if (abortNextMessage) {
        abortNextMessage = false;
        if (saveBeforeAbort && !existing) conversation.exchanges.push(turn);
        return route.abort('connectionfailed');
      }
      if (!existing) conversation.exchanges.push(turn);
      return json(turn.response);
    }
  }
  unexpected.push({ method: req.method(), path: url.pathname });
  return route.abort('blockedbyclient');
});

try {
  await page.goto(process.env.CHAT_UI_URL || 'http://127.0.0.1:5173');
  const input = page.getByLabel('向知识库提问', { exact: true });
  const send = page.getByRole('button', { name: '发送问题', exact: true });
  const newChat = page.getByRole('button', { name: '新对话', exact: true });
  await input.fill('青禾项目负责人是谁？');
  await wait(async () => !(await send.isDisabled()), 'Initial conversation not ready');
  assert.equal(createCount, 1);
  assert.equal(await page.getByText(/每次提问独立处理/).count(), 0);
  const firstId = [...conversations.keys()][0];
  pass('initializes one isolated conversation');

  delayMessage = true;
  await input.press('Enter');
  await wait(() => posts.length === 1 && !!releaseMessage, 'Message was not posted');
  assert.equal(await newChat.isDisabled(), true);
  assert.equal(await page.getByRole('button', { name: '正在生成', exact: true }).isDisabled(), true);
  await input.press('Enter');
  assert.equal(posts.length, 1);
  delayMessage = false; releaseMessage();
  await page.locator('.answer-text').waitFor();
  await page.locator('details.sources > summary').click();
  await page.locator('.source-item > summary').first().click();
  assert.match(await page.locator('.source-text').innerText(), /<script>/);
  assert.equal(await page.locator('.source-text script').count(), 0);
  assert.equal(await page.evaluate(() => window.__ragInjected), undefined);
  pass('Enter submit, pending duplicate guard, new-chat guard and safe source text');

  await page.reload();
  await page.locator('.answer-text').waitFor();
  assert.equal(createCount, 1);
  assert.equal(posts.length, 1);
  await input.fill('他负责什么？');
  await send.click();
  await wait(() => conversations.get(firstId).exchanges.length === 2, 'Follow-up was not saved');
  await wait(async () => await page.locator('.answer-text').count() === 2, 'Follow-up answer missing');
  assert.equal(posts.at(-1).conversationId, firstId);
  assert.equal(posts.at(-1).question, '他负责什么？');
  pass('reload restores server history; follow-up uses the same conversation');
  await page.screenshot({ path: path.join(output, 'chat-desktop.png'), fullPage: false });

  await newChat.click();
  await wait(() => createCount === 2, 'New conversation not created');
  await wait(async () => await page.locator('.answer-text').count() === 0, 'New conversation leaked old answers');
  await page.reload();
  await wait(async () => (await newChat.isEnabled()) && createCount === 2, 'New conversation reload not ready');
  assert.equal(await page.locator('.answer-text').count(), 0);
  assert.equal(conversations.get(firstId).exchanges.length, 2);
  pass('new chat isolates messages, keeps old records and survives refresh');

  abortNextMessage = true; saveBeforeAbort = true;
  await input.fill('请说明项目目标');
  await send.click();
  await page.getByRole('heading', { name: '这次未能获取回答' }).waitFor();
  assert.equal(await input.inputValue(), '请说明项目目标');
  const pending = posts.at(-1);
  await page.getByRole('button', { name: '重新发送', exact: true }).click();
  await page.locator('.answer-text').waitFor();
  assert.equal(posts.at(-1).requestId, pending.requestId);
  assert.equal(conversations.get(pending.conversationId).exchanges.length, 1);
  pass('ambiguous transport failure preserves input and retries with the same request ID');

  abortNextMessage = true; saveBeforeAbort = false;
  await input.fill('断线后刷新再重试的问题');
  await send.click();
  await page.getByRole('heading', { name: '这次未能获取回答' }).waitFor();
  const interrupted = posts.at(-1);
  await page.reload();
  await page.getByRole('button', { name: '重新发送', exact: true }).waitFor();
  assert.equal(await input.inputValue(), interrupted.question);
  await page.getByRole('button', { name: '重新发送', exact: true }).click();
  await wait(async () => await page.locator('.answer-text').count() === 2, 'Retry after refresh did not recover');
  assert.equal(posts.at(-1).requestId, interrupted.requestId);
  pass('refresh keeps an unresolved request identity and retries the original question');

  const previousCreateCount = createCount;
  rejectRestore = true;
  await page.reload();
  await page.getByText('暂时无法恢复会话，请重试。', { exact: false }).first().waitFor();
  assert.equal(createCount, previousCreateCount);
  await input.fill('不得发送到新会话');
  assert.equal(await send.isDisabled(), true);
  rejectRestore = false;
  await page.getByRole('button', { name: '重试恢复会话', exact: true }).click();
  await page.locator('.answer-text').first().waitFor();
  assert.equal(createCount, previousCreateCount);
  pass('restore failure blocks sending without silently replacing the conversation');

  await page.setViewportSize({ width: 390, height: 844 });
  await input.fill('第一行');
  await input.press('Shift+Enter');
  await input.pressSequentially('第二行');
  assert.match(await input.inputValue(), /\n/);
  const metrics = await page.evaluate(() => ({ width: innerWidth, scroll: document.documentElement.scrollWidth }));
  assert.ok(metrics.scroll <= metrics.width + 1, JSON.stringify(metrics));
  await page.screenshot({ path: path.join(output, 'chat-mobile.png'), fullPage: false });
  pass('390x844 mobile has no horizontal overflow; Shift+Enter retains newline');
  assert.deepEqual(unexpected, []);
  assert.deepEqual(errors, []);
  pass('all API requests intercepted; no uncaught browser errors');
} catch (error) {
  cases.push({ name: 'UI verification', result: 'FAIL', detail: String(error) });
  await page.screenshot({ path: path.join(output, 'failure.png'), fullPage: true });
  throw error;
} finally {
  await fs.writeFile(path.join(output, 'result.json'), JSON.stringify({ mode: 'mocked-api-real-browser', cases, errors, unexpected }, null, 2));
  await browser.close();
}
