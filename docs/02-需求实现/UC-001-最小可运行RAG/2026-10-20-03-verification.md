# 第一版 RAG 本地研发验证记录

| 项目 | 内容 |
| --- | --- |
| 需求 | UC-001 / v2 已批准规格 |
| 日期 | 2026-10-20 |
| 结论 | 第一版已实现，核心链路在真实本地服务上通过研发回归；不是完整正式验收或生产就绪结论 |
| 数据边界 | 只使用项目隔离实例与测试自建文档，不操作原有 3306 MySQL；未提交、推送或发布 |

## 1. 环境与实际配置

- Windows、PowerShell；Java 17.0.20.1、Maven 3.9.16、Node.js 24.19.0、npm 11.17.0、Python 3.12.14。
- Vue 3.5.44、TypeScript、Vite 8.3.4，问答和文档为两个独立工程及构建产物，端口分别为 5173、5174。
- Spring Boot 3.5.16、LangChain4j core / OpenAI 1.22.0、Chroma 集成 1.22.0-beta32，显式使用 Chroma V2；PDFBox 3.0.7。
- 项目 MySQL 8.4.9 监听 13306，Chroma 1.5.9 监听 8000；数据分别位于项目 .local/mysql/data 和 .local/chroma。
- llama.cpp b11669 CPU 服务：Qwen3-1.7B Q8 生成模型（8091）、BGE-small-zh-v1.5 Q8 向量模型（8092，512 维）。版本与下载 SHA256 见 scripts/model-manifest.json。
- 本机 app.env 使用 300 码点分块 / 50 码点重叠，以适配 BGE 的 512-token 输入上限；代码缺省 800 / 100 保留。模型超限明确报错，不静默截断。
- 所有服务默认回环监听，无鉴权。真实数据库密码未写入本文、前端或测试报告；本地推理不需要外部 API Key。

## 2. 命令与结果

在项目根目录运行。下列 python 指本机可用的 Python 3.12；若 PATH 是 WindowsApps 别名，应使用 setup 指定的 Python 完整路径。

| 命令或操作 | 实际结果 |
| --- | --- |
| mvn -f backend/pom.xml package | 32 项：31 通过，1 项真实服务集成因未显式启用而跳过，0 失败；JAR 构建成功 |
| npm test --prefix frontend-chat | 19 / 19 通过，局部状态机与契约测试 |
| npm test --prefix frontend-docs | 14 / 14 通过，局部状态机与契约测试 |
| npm run build --prefix frontend-chat；npm run build --prefix frontend-docs | 两个独立生产构建均成功 |
| 设置 REAL_API_TEST_URL=http://127.0.0.1:8088 后，mvn -f backend/pom.xml -Dtest=RealServicesIntegrationTest test | 真实服务测试 1 / 1 通过，0 跳过，33.42 秒；使用实际模型及两类数据库 |
| python tests/integration.py；最终复跑加 --keep-demo | 真实 API 回归 16 项通过；测试自建记录清理，最终仅额外保留青禾体验样例 |
| npm run test:ui --prefix tests | Playwright 驱动本机 Chrome，9 个场景组通过，无未捕获页面异常 |
| python tests/resilience.py | 真实停机与恢复 6 项通过；之后将就绪数断言调整为相对起始值，以兼容已有用户文档，该小幅断言修改未再次完整复跑 |
| python tests/security-check.py | 22 个被检查的构建产物及日志文件未出现本机配置的私密凭据；不是完整安全审计 |
| scripts/setup.ps1 重复执行 | 通过，复用已安装依赖，不重置已有密码与数据；另一台全新机器的首次分支未实测 |
| scripts/start.ps1 / 重复执行 / -SkipBuild | 构建启动及复用成功，两个前端的 /api/health 代理实际返回 200 |
| 依次 stop.ps1 -Service chat-web、docs-web，并各自 start.ps1 -SkipBuild 恢复 | 问答端停机时文档端 HTTP 200；文档端停机时问答端 HTTP 200，两个前端可独立运行 |
| 最终浏览器冒烟（.local/final-smoke.mjs） | 示例回答为“青禾项目的负责人是林溪。”且有真实原文；新标签页打开文档端、返回后消息保留、刷新后消息清空而文档保留，均通过 |
| validate_docs.py --area-dir docs/02-需求实现 --kind uc --strict；本地链接检查 | 1 个需求包严格校验通过，12 个 Markdown 文件的相对链接均可解析 |

## 3. 验收基线覆盖

“真实覆盖”指表内列出的观察，不表示穷尽该标准的所有输入、故障或浏览器组合。

| 标准 | 本轮观察与结果 | 边界 |
| --- | --- | --- |
| AC-001 | 两端独立构建、实际启动、互不依赖；共用后端知识库。逐一停止前端后另一端仍 200。 | 异机首次安装、独立生产部署未实测。 |
| AC-002 | 空库页面禁用发送并显示导入入口；直接问答 API 返回 409。 | 真实覆盖。 |
| AC-003 | TXT、MD、中文两页文本 PDF 实际导入就绪，页面轮询并显示分块；持久化由重启回归检查。 | 页面上传重点验证 TXT，MD/PDF 经真实 API 验证。 |
| AC-004 | 超过 20 块的分页、300 码点上限、50 码点重叠、含 emoji 全文无损重建；PDF 页码范围为 1..2；UI 查看分块全文。 | 只针对测试夹具，不代表所有 PDF 排版都可准确还原。 |
| AC-005 | 不支持扩展名 422、空文件 422、20 MiB + 1 字节 413；损坏 PDF、无文本层 PDF、非法 UTF-8 均异步 FAILED 且无有效块。 | 真实覆盖。 |
| AC-006 | 真实模型回答青禾负责人为“林溪”，grounded=true；逐一比对来源 ID、文档、序号、原文与后端存储，UI 展开来源。 | 不能由单个问题推导通用准确率。 |
| AC-007 | 文档未记载预算，真实返回 grounded=false、sources=[]，页面正常显示依据不足。 | “零召回不调用生成模型”有后端单元覆盖；未用真实模型另造零召回场景。 |
| AC-008 | 真正停止生成模型得到技术失败 502；真正停止 Chroma 得到检索/写入/删除失败，不伪装无依据；页面模拟传输失败时保留输入可重试。 | 远程凭据无效、真实超时未专项注入；浏览器中断不等于模型停机。 |
| AC-009 | 同名文件两次上传产生独立文档 ID 与互不重叠的块 ID，关联不会混用。 | 真实 API 覆盖，非浏览器重复上传专项。 |
| AC-010 | 删除后 API 404；故障恢复后直接调用 Chroma V2，按 documentId 查询向量为空，关系记录删除。 | 未穷举与问答同时发生的所有时序。 |
| AC-011 | 实际停止并重启后端、项目 MySQL、Chroma，分块响应完全一致，向量问答仍返回林溪；202 后中断后端，重启后 PROCESSING 转 FAILED。 | 单实例本机验证，不含进程外分布式一致性。 |
| AC-012 | 空白/超长 Unicode 问题被服务端拒绝；Enter 提交、加载禁重、Shift+Enter 换行经页面验证；最终冒烟确认新标签页链接、往返保留消息、刷新清空消息而文档仍保留。 | 导入新文档后返回原问答标签页自动刷新状态的完整时序未专项串行验收；有前端局部测试及实现检查。 |
| AC-013 | 分块及来源含 script、img onerror、引号包裹的“忽略系统指令”原文；未产生脚本 DOM 或执行标记；提示词分隔文档和系统规则，凭据扫描通过。 | 这是纯文本渲染与单一样例检查，不承诺抵御所有提示注入。 |
| AC-014 | Chrome 1440×900 / 390×844 检查两端，无页面级横向溢出；键盘发送、换行、删除取消、Escape 关闭和焦点恢复通过，空/加载/错误状态可见。 | 主要业务在桌面走通，移动视口检查布局；非完整移动键盘、读屏或跨浏览器验收。 |
| AC-015 | Chroma 真停机时导入 FAILED；删除失败保留 FAILED 可重试记录，并从 readyCount 排除；恢复后重试清理真实向量和关系记录。 | 未注入网络半写入、全部清理竞态或所有崩溃时点。 |

## 4. 证据与测试材料

- 可复跑源码：[测试说明](../../../tests/README.md)、tests/integration.py、tests/browser.mjs、tests/resilience.py、tests/security-check.py。
- 本机报告（已忽略，不作为公开源码提交）：.local/test-results/integration.json、browser.json、resilience.json、frontend-independence.json、final-smoke.json。
- 页面截图：.local/test-results/chat-desktop.png、documents-desktop.png、chat-mobile.png、documents-mobile.png；实际看过桌面与移动布局。交付前另查看 final-chat.png 与 final-documents.png 两张视口截图，使用干净示例。全页截图可能将 sticky 输入区或屏幕外跳转链接绘制在滚动捕获位置，不能仅凭该表现判定页面遮挡。
- Java 单元及真实集成报告：backend/target/surefire-reports/；服务日志：.local/logs/。这些文件可能包含文档内容，不应公开共享整个目录。
- 测试夹具：tests/fixtures/青禾项目说明.txt、导入说明.md、两页文本.pdf、扫描型.pdf。扫描型 PDF 为实际仅含图片的文件，不是损坏 PDF 替代品。
- 最初页面回归有一次测试定位器错误：将 aria-hidden 装饰点算入标题，修正定位器后通过，未把该次失败改写成产品故障。最新 JSON 是完成回归的结果。

## 5. 残余限制与交付状态

1. 默认本地小模型主要验证闭环，答案仍需核对来源；问题业务上限 2000 码点不等于本地 BGE 可接受任意该长度输入，超出 512 tokens 会报技术错误。
2. Flyway 对 MySQL 8.4 给出高于其声明支持版本 8.1 的警告；实际迁移、启动和重启校验通过，本轮未为消除警告升级整套依赖。
3. Docker/compose 仅提供备选配置，本机无 Docker，容器路径未实测；不据此声称容器版已验收。
4. 未覆盖多用户、公网、生产安全审计、压力/性能基准、跨浏览器、异机全新安装和远程模型认证/超时等组合。
5. 常规停止保留知识库；中断导入不自动恢复原文件，应删除失败记录后重新上传。测试只清理自己创建的 ID。
6. 本轮不创建 Git 提交、推送或 PR；当前目录不是 Git 仓库。全部功能仅按本机原型交付。

[需求规格](2026-10-20-01-overall-spec.md) · [实施计划](2026-10-20-02-overall-plan.md) · [实际架构](../../01-架构设计/系统架构.md) · [运行说明](../../../README.md)
