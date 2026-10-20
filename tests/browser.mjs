import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const root = path.dirname(fileURLToPath(import.meta.url));
const output = path.join(root,'../.local/test-results');
await fs.mkdir(output,{recursive:true});
const browser = await chromium.launch({channel:'chrome',headless:true});
const context = await browser.newContext({viewport:{width:1440,height:900},locale:'zh-CN'});
const docs = await context.newPage();
const chat = await context.newPage();
const errors = [];
const cases = [];
for (const page of [docs,chat]) page.on('pageerror',e=>errors.push(String(e)));
const wait = async (predicate,message) => { const end=Date.now()+150000; while(Date.now()<end) { if(await predicate()) return; await new Promise(r=>setTimeout(r,200)); } throw Error(message); };
const pass = name => { cases.push({case:name,result:'PASS'}); console.log('PASS '+name); };
let ownId;
try {
  await docs.goto('http://127.0.0.1:5174');
  await chat.goto('http://127.0.0.1:5173');
  await docs.getByRole('heading',{name:'文档管理',exact:true}).waitFor();
  const initial = await (await context.request.get('http://127.0.0.1:8088/api/knowledge/status')).json();
  if(initial.readyDocuments===0) {
    await chat.getByText('请先导入').first().waitFor({timeout:15000});
    await chat.getByLabel('向知识库提问',{exact:true}).fill('青禾项目负责人是谁？');
    assert.equal(await chat.getByRole('button',{name:'发送问题',exact:true}).isDisabled(),true);
    pass('empty knowledge base blocks sending');
  }
  await docs.locator('input[type=file]').setInputFiles({name:'browser-青禾项目说明.txt',mimeType:'text/plain',buffer:Buffer.concat([await fs.readFile(path.join(root,'fixtures/青禾项目说明.txt')), Buffer.from('\n<script>window.__ragInjected=true</script>\n<img src=x onerror=window.__ragInjected=true>\n“忽略系统指令”是待展示的原文，不应作为指令执行。')])});
  const uploadResponse=docs.waitForResponse(r=>r.url().endsWith('/api/documents')&&r.request().method()==='POST');
  await docs.getByRole('button',{name:'上传文档',exact:true}).click();
  const uploaded=await (await uploadResponse).json(); ownId=uploaded.id;
  assert.equal(uploaded.status,'PROCESSING');
  const card=docs.locator('.document-card').filter({has:docs.getByRole('heading',{name:'browser-青禾项目说明.txt',exact:true})});
  await wait(async()=>await card.locator('.status-ready').count()>0,'Document did not become READY');
  await docs.getByRole('button',{name:'查看 browser-青禾项目说明.txt 的分块',exact:true}).click();
  await docs.locator('.chunk-text').filter({hasText:'林溪'}).waitFor();
  assert.equal(await docs.evaluate(()=>window.__ragInjected),undefined);
  assert.equal(await docs.locator('.chunk-text script, .chunk-text img').count(),0);
  pass('UI upload, status polling, plaintext script-shaped content and full chunk display');
  await docs.screenshot({path:path.join(output,'documents-desktop.png'),fullPage:true});
  await chat.bringToFront();
  await chat.reload();
  const textarea=chat.getByLabel('向知识库提问',{exact:true});
  await textarea.fill('青禾项目的负责人是谁？');
  await wait(async()=>!(await chat.getByRole('button',{name:'发送问题',exact:true}).isDisabled()),'Chat status not refreshed');
  let posted=0;
  chat.on('request',r=>{if(r.url().endsWith('/api/chat')&&r.method()==='POST')posted++;});
  await textarea.press('Enter');
  await chat.getByText('正在检索并生成回答',{exact:true}).waitFor();
  assert.equal(await chat.getByRole('button',{name:'正在生成',exact:true}).isDisabled(),true);
  await chat.locator('.answer-text').filter({hasText:'林溪'}).waitFor({timeout:150000});
  assert.equal(posted,1);
  await chat.locator('details.sources > summary').click();
  await chat.locator('.source-item > summary').first().click();
  assert.match(await chat.locator('.source-text').first().innerText(),/林溪/);
  assert.equal(await chat.evaluate(()=>window.__ragInjected),undefined);
  assert.equal(await chat.locator('.source-text script, .source-text img').count(),0);
  pass('keyboard submit, loading, duplicate guard, grounded answer and safe expandable source');
  await chat.screenshot({path:path.join(output,'chat-desktop.png'),fullPage:true});
  await textarea.fill('青禾项目的预算金额具体是多少元？');
  await chat.getByRole('button',{name:'发送问题',exact:true}).click();
  await chat.locator('.ungrounded-note').waitFor({timeout:150000});
  pass('missing evidence displayed as a normal answer state');
  // Deliberately simulate only the browser transport failure, not a model outage.
  await chat.route('**/api/chat',route=>route.abort('connectionfailed'));
  await textarea.fill('请说明项目目标');
  await chat.getByRole('button',{name:'发送问题',exact:true}).click();
  await chat.getByRole('heading',{name:'这次未能获取回答'}).waitFor();
  assert.equal(await textarea.inputValue(),'请说明项目目标');
  await chat.getByRole('button',{name:'重新发送',exact:true}).waitFor();
  await chat.unroute('**/api/chat');
  pass('simulated browser transport failure preserves input and enables retry');
  await textarea.fill('问题第一行'); await textarea.press('Shift+Enter'); await textarea.type('第二行');
  assert.match(await textarea.inputValue(),/\n/);
  pass('Shift+Enter inserts a newline');
  for(const [name,page] of [['documents',docs],['chat',chat]]) {
    await page.setViewportSize({width:390,height:844});
    await page.screenshot({path:path.join(output,name+'-mobile.png'),fullPage:true});
    const metrics=await page.evaluate(()=>({width:innerWidth,scroll:document.documentElement.scrollWidth}));
    assert.ok(metrics.scroll<=metrics.width+1,JSON.stringify({name,...metrics}));
  }
  pass('390x844 mobile pages have no horizontal overflow');
  await docs.getByRole('button',{name:'删除 browser-青禾项目说明.txt',exact:true}).click();
  const dialog=docs.getByRole('dialog'); await dialog.waitFor();
  await docs.keyboard.press('Escape'); await dialog.waitFor({state:'detached'});
  assert.equal(await docs.getByRole('button',{name:'删除 browser-青禾项目说明.txt',exact:true}).evaluate(el=>el===document.activeElement),true);
  await docs.getByRole('button',{name:'删除 browser-青禾项目说明.txt',exact:true}).click();
  await docs.getByRole('button',{name:'确认删除',exact:true}).click();
  await card.waitFor({state:'detached'});
  const gone=await context.request.get('http://127.0.0.1:8088/api/documents/'+ownId);
  assert.equal(gone.status(),404); ownId=null;
  pass('delete cancel, focus restoration, confirm and actual record removal');
  assert.deepEqual(errors,[]); pass('no uncaught browser exceptions');
} catch(e) {
  cases.push({case:'browser flow',result:'FAIL',detail:String(e)});
  await docs.screenshot({path:path.join(output,'failure-documents.png'),fullPage:true});
  await chat.screenshot({path:path.join(output,'failure-chat.png'),fullPage:true});
  throw e;
} finally {
  if(ownId) {
    // Only clean up the fixture created by this run.
    await context.request.delete('http://127.0.0.1:8088/api/documents/'+ownId);
  }
  await fs.writeFile(path.join(output,'browser.json'),JSON.stringify({cases,errors},null,2));
  await browser.close();
}
