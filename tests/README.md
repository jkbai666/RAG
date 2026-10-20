# 第一版研发验证

当前默认模型已切为云端 Qwen / DeepSeek，两个 Key 在 backend/src/main/resources/application.yml 填写。测试对象为本项目隔离数据库和本机服务。以下是开发回归，不代替生产安全审计或正式业务验收；替身测试与真实服务结果分别记录。

## 1. 单元、契约与构建

在项目根目录运行：

~~~powershell
mvn -f backend/pom.xml test
npm test --prefix frontend-chat
npm test --prefix frontend-docs
npm run build --prefix frontend-chat
npm run build --prefix frontend-docs
~~~

后端包含云端 HTTP 协议、H2 会话持久化、记忆预算、追问改写、幂等与并发、段落重叠和引用复核测试。当前数量及结果以 [UC-003 验证记录](../docs/02-需求实现/UC-003-会话记忆与段落切块/2026-10-20-03-verification.md) 为准；旧迭代统计不代表当前版本，也不证明真实云端联通。

## 2. 真实 API 回归

先按根 README 填写两个有效云端 Key、启动所有服务，并确保知识库与当前向量配置一致。以下回归会发送测试文本给厂商并可能消耗额度，填写 Key 后由使用者按需运行；UC-003 为避免付费与改动现有知识库没有执行，不计为通过。下面的 python 要指向可用的 Python 3.12；本机若 PATH 中只有 WindowsApps 别名，可使用 setup.ps1 -PythonPath 指定的可执行文件。

~~~powershell
python tests/integration.py
# 如修改过 YAML 分块参数，传入相同值
# python tests/integration.py --chunk-size 800 --chunk-overlap 100
# 完成后额外留下青禾样例，供页面体验
python tests/integration.py --keep-demo
# 后端附带的真实服务回归，也可独立执行
$env:REAL_API_TEST_URL = 'http://127.0.0.1:8088'
mvn -f backend/pom.xml '-Dtest=RealServicesIntegrationTest' test
Remove-Item Env:REAL_API_TEST_URL
~~~

integration.py 只删除该次创建的文档 ID，不清空用户知识库；--keep-demo 明确保留同名样例。覆盖文件类型、大小、编码、扫描件、同名记录、PDF 页码、Unicode 重叠与全文重建、分页、真实回答、来源逐块核对、依据不足、删除。空库检查仅在起始为空时执行。检索和生成的结果仍受所配置模型影响，不应把失败改写为通过。

## 3. 真实页面回归

此完整流程同样要求两个有效云端 Key，会发送测试资料并可能消耗额度，本轮没有运行。安装本机 Chrome 和测试依赖；两个前端保持运行：

~~~powershell
npm ci --prefix tests
npm run test:ui --prefix tests
~~~

browser.mjs 使用 Playwright 驱动 Chrome，覆盖真实上传/轮询/分块/提问/来源/删除，1440×900 与 390×844，Enter、Shift+Enter、加载禁重、删除取消与焦点恢复、脚本形状正文的纯文本渲染。输入保留/重试另用浏览器请求中断检查，**这一项是模拟网络失败，不是模型故障证据**。错误、结果 JSON 和截图写入 .local/test-results。

## 4. 历史本地模型故障回归（不适用于当前默认配置）

~~~powershell
python tests/resilience.py
~~~

resilience.py 依赖旧本地生成模型进程控制，尚未适配云端默认配置，本轮不运行。不要将其结果解释为云端故障恢复已通过。

**旧模式下会短暂停止并重启本项目的生成模型、Chroma、后端和 13306 MySQL。请勿在正在导入真实资料时执行，也不要与其他回归并行执行。** 停止脚本依据项目记录核对 PID，不操作既有的 3306。

验证真实模型停机错误、Chroma 检索/写入/删除失败、恢复后实际向量清理、三服务重启持久化、处理中断恢复。仅清理自己创建的文档；finally 尽量恢复服务。结果在 .local/test-results/resilience.json。没有注入网络半写入/超时等所有可能的跨库故障。

检查当前构建产物和日志未出现真实私密凭据：

~~~powershell
python tests/security-check.py
~~~

## 5. 样例与证据

fixtures 下提供青禾 TXT、导入说明 MD、两页文本 PDF、无文本层扫描型 PDF。已有 PDF 可直接用，不依赖 Python 的 PDF 库；重新生成可运行 make-pdf-fixture.py，需要 reportlab 和 Pillow（仅测试夹具生成依赖）。

[云端接入验证](../docs/02-需求实现/UC-002-云端模型接入/2026-10-20-03-verification.md) 记录本轮结果和限制；[UC-001 历史验证](../docs/02-需求实现/UC-001-最小可运行RAG/2026-10-20-03-verification.md) 属于旧本地模型配置。测试生成的数据、截图与日志不是源代码，均放在已忽略的 .local 中。

## 6. 会话页面隔离验证（不调用模型）

先构建问答前端并启动 5173 页面，在项目根目录运行：

~~~powershell
npm ci --prefix tests
npm run test:ui:memory --prefix tests
~~~

`memory-ui.mjs` 复用 Playwright 与本机 Chrome，以真实 Vue 页面触发操作，但拦截所有 `/api/**` 请求。覆盖创建/恢复会话、追问、新对话隔离、发送中防重复、断线后同 ID 重试、刷新保留待确认请求、恢复失败不偷偷换会话、来源纯文本以及桌面/手机布局。所有测试状态留在浏览器上下文及内存替身中，不写真实知识库、不使用真实 Key。

它不能证明 MySQL 持久化或真实模型理解能力；后端持久化由 H2 集成测试与记录在 UC-003 中的本机 MySQL 空会话重启检查分别验证。浏览器证据在 `.local/test-results/uc003-ui/`，结果 JSON 标明 `mocked-api-real-browser`。不要把此命令与会真实导入/提问的 `npm run test:ui --prefix tests` 混淆。

既有 `browser.mjs` 已适配会话消息路由，`integration.py` 已适配可变段落边界、重叠重建和参数；本轮仅语法检查，未执行它们的真实云端链路。`resilience.py` 依赖旧本地模型服务，不适用于当前云端流程，不直接运行。

## 7. 云端启动隔离检查

~~~powershell
pwsh -NoProfile -File tests/cloud-startup.ps1
~~~

使用临时配置验证环境变量白名单、旧模型值隔离、脚本语法和默认无本地模型依赖。不启动数据库，不修改私有配置，不调用云端接口。后端 CloudModelProtocolTest 用本机 HTTP 替身检查实际 SDK 发出的协议，不替代厂商联通验收。
