# UC-003 开发验证记录

| 项目 | 内容 |
| --- | --- |
| 日期 | 2026-10-20 |
| 状态 | 开发验证完成；本机新版本已启动；真实云端质量未验证 |
| 来源 | [v3 批准规格](2026-10-20-01-overall-spec.md)、[实施计划](2026-10-20-02-overall-plan.md) |
| 环境 | Windows；Java 17、Node 24、Maven；MySQL 8.4、Chroma 1.5.9 |
| 边界 | 不调用付费云端模型，不导入或删除真实知识库；不是正式生产验收或回答质量评测 |

## 1. 修改前后与数据保护

| 项目 | 修改前 | 部署并重启后 | 结果 |
| --- | --- | --- | --- |
| 已有文档 | 1 份 | 1 份 | 行内容摘要一致 |
| MySQL 分块 | 46 块 | 46 块 | 全部行内容摘要一致 |
| rag_settings | 1 行 | 1 行 | 内容摘要一致，未改变索引 profile |
| 当前 Chroma collection | 46 条 | 46 条 | ID、向量、正文和元数据的内容摘要一致 |
| 旧 Chroma collection | 0 条 | 0 条 | 保持不变 |
| application.yml | 用户现有配置 | 原字节保留 | SHA-256 一致，未改动两个 API Key |
| 会话表 | 不存在 | V2 新增两表 | 不修改 V1 或资料表；仅留 1 个空会话作重启检查，0 条问答 |

原始摘要位于已忽略的 `.local/test-results/uc003-baseline.json`，比对结果为 `uc003-integrity.json`。未将 Key、原文或原始向量写入本报告。

另通过现有 HTTP 文档与分页接口读回全部 46 块，与 Chroma 保存的同 ID 正文逐一比对，全部相同。该检查证明既有资料仍可读取，不是新规则真实导入或云端检索验证。

## 2. 代码测试、协议与构建

以下命令由主线程在两项实现合并后独立执行，均从项目根目录运行。Java 测试默认使用 H2 和模型/向量替身；SDK 协议测试使用本机 HTTP 服务，不向真实厂商发请求。

| 命令 | 实际结果 |
| --- | --- |
| `mvn -f backend/pom.xml test` | 共 103 项：102 通过、1 显式跳过；0 失败、0 错误 |
| `npm test --prefix frontend-chat` | 34 项通过 |
| `npm test --prefix frontend-docs` | 14 项通过 |
| `npm run build --prefix frontend-chat` | 类型检查及 Vite 生产构建通过 |
| `npm run build --prefix frontend-docs` | 类型检查及 Vite 生产构建通过 |
| `mvn -f backend/pom.xml -DskipTests package` | 已在前述测试通过后打包成功；此命令本身不重复运行测试 |
| `node --check tests/browser.mjs`、`node --check tests/memory-ui.mjs` | 语法通过 |
| Python 编译 `tests/integration.py` | 语法通过，未执行真实云端流程 |
| `python tests/security-check.py --self-test` | 6 项通过 |
| `python tests/security-check.py` | 新构建及运行日志 26 个文件未检出已配置私密凭据；不扫描作为凭据存放位置的 YAML/JAR，不代表完整安全审计 |

后端分项：段落解析 45、会话集成 18、既有服务契约 23、云端 SDK 协议 13、配置 2、隔离启动 1，共 102 项通过。`RealServicesIntegrationTest` 因未设置 `REAL_API_TEST_URL` 显式跳过，不计为真实模型验证通过。

证据：`backend/target/surefire-reports/`、`.local/test-results/uc003-maven-test.log`、`uc003-package.log`。构建产物可能含用户 Key，不得分享 JAR 或整个私有目录。

## 3. 真实页面、隔离 API 检查

命令：`npm run test:ui:memory --prefix tests`。真实 Chrome 打开构建后的 Vue 页面，所有 `/api/**` 请求由测试拦截；不写真实数据库、不调用模型。最终 9 组全部通过：

1. 初次创建一个独立会话，页面不再显示“不记忆上下文”的旧提示。
2. Enter 发送、发送中防重复、禁止切换新对话、来源按纯文本展示，不执行 script 形状正文。
3. 刷新恢复服务端历史，追问继续使用相同会话标识。
4. 新对话不显示旧消息，刷新仍保持新会话，替身中的旧记录未被删除。
5. 模拟服务端已成功但浏览器断线，重试复用原 requestId，不重复成功记录。
6. 模拟未确认结果后刷新，原问题及标识保留，重试仍使用相同 requestId。
7. 恢复失败禁用发送，不偷偷新建；点击“重试恢复会话”后恢复原记录。
8. 390×844 无水平溢出，Shift+Enter 正常换行；另检查 1440×900 桌面视口。
9. 所有 API 请求均被拦截，无未捕获页面异常。

证据：`.local/test-results/uc003-ui/result.json`，模式明确标为 `mocked-api-real-browser`；`chat-desktop.png`、`chat-mobile.png` 已目视复核。这不是完整真实后端/模型端到端验证。

## 4. 本机迁移与重启检查

1. 使用现有进程记录只停止托管后端，等待旧 PID 退出，避免覆盖仍被占用的 JAR；未停止或清理 MySQL / Chroma 数据。
2. 打包后用 PowerShell 7 完整路径执行 `scripts/start.ps1 -SkipBuild`，复用已有数据库和两个前端。
3. Flyway 历史中 V1 `initial`、V2 `conversations` 均为成功；只新增会话表。
4. 用 curl 访问 `/api/health` 返回 UP，知识库状态为 1 份就绪文档、模型配置齐全。配置齐全不代表厂商可达。
5. `POST /api/conversations` 创建 1 个空会话，GET 返回相同 UUID 和空 exchanges。
6. 再次仅重启后端，GET 仍返回同一空会话。该检查证明真实 MySQL 会话记录跨后端重启保留；成功问答内容恢复由前述 H2 测试覆盖，未在真实库写入测试问答。
7. 两个前端均 HTTP 200；再次比对既有资料、向量和 YAML 摘要，全部一致。

空会话响应保存在 `.local/test-results/uc003-empty-conversation.json` 与 `uc003-empty-restored.json`。该空记录保留，未引入历史删除接口。最终本机服务仍在运行：问答 5173、文档 5174、后端 8088。

## 5. 集成时发现并修正的问题

- 旧服务契约测试使用连续 1600/1700 个无句界字符来制造多块，新规则会在到达索引/引用逻辑前拒绝。首次全量出现 2 个失败和 1 个错误；仅把四处多块 fixture 改成带句界的等价长文本，保留原断言。不可分超长句仍由解析器 422 测试约束，未放松切块规则。
- 审查发现“早先失败 A、随后成功 B、A 重试成功”的记录按请求占位序号会排错。改为成功完成时间加序号排序，并加入覆盖用例。
- 页面初次自动检查通过后，截图发现两处旧单轮提示；已修正并加入文案断言，最终 9 组重跑通过。

## 6. 未验证与限制

未调用 DeepSeek / Qwen 真实接口，未验证真实模型追问理解、召回质量、收费或厂商可达性；未向真实知识库导入新规则样例。因此 AC-001 的真实云端效果、AC-004/008 的真实新导入双库链路仍由使用者后续体验，不能把替身测试当作完整效果验收。没有性能提升或回答质量提升结论。

未做复杂 PDF 版式、全量 Markdown 语法、超大文档性能压测或多实例验证。段落切块是规则化实现，不是 embedding 语义聚类；有限英文缩写与 PDF 文本提取可能仍需人工核对。超长且不能安全拆分的句子/代码块会明确失败。

历史问答持久保存，5 轮只限制模型上下文；前端没有历史列表或清理入口。浏览器中断不保证取消服务器工作或厂商费用。现有 PowerShell 5.1 启停兼容问题未在本次修改，部署使用已有 PowerShell 7 完整路径，未修改系统 PATH、脚本或数据库密码。

文档收尾检查：需求目录 3 个文档包结构校验通过；本轮修改及新增的 11 份 Markdown 相对文件链接无缺失；`git diff --check`（排除用户已有的凭据 YAML）通过。

功能实现及本机验证阶段未提交、推送或创建 PR。相关规格、计划、架构、HTTP 契约、启动与测试说明已经同步。

## 7. Git 交付前复验（2026-10-20）

用户另行授权将本次修改和文档提交到 GitHub，并明确要求不提交 API Key。提交范围仅为 UC-003 实现、测试及配套文档，分支为 `codex/session-memory-paragraph-chunks`；不合并主分支。

- 重新执行后端测试：103 项中 102 通过、1 跳过；两个前端测试及构建、9 组浏览器隔离 API 检查通过。真实云端验证仍未执行。
- 用户填写 Key 的 `backend/src/main/resources/application.yml` 不暂存，保留原字节；仓库中的无密钥配置版本不变。`.local`、JAR、日志及构建产物不纳入提交。
- 排除既有 BUG-001 草稿及其索引改动，保留在本机；只暂存本次功能涉及的 30 个文件。
- 对 Git 暂存内容及既有可达历史执行本机凭据精确匹配和常见密钥格式检查；检查过程不输出凭据。审计结果保存于忽略目录 `.local/test-results/uc003-git-security-audit.json`。
- 提交前复验日志位于 `.local/test-results/uc003-precommit-*`；包含真实配置的构建 JAR 不得分发。
