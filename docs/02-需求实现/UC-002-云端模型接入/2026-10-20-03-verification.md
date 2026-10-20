# 云端模型接入验证记录

| 项目 | 事实 |
| --- | --- |
| 日期 | 2026-10-20，Asia/Shanghai |
| 范围 | UC-002 云端配置、实际 SDK 请求协议、启动隔离、缺 Key 门禁 |
| 环境 | Windows；Java 17、Maven 3.9；本项目 MySQL :13306、Chroma :8000 |
| 凭据 | 两个云端 Key 均为空；没有请求真实厂商接口 |
| 结论 | 本地实现与下述自检通过；真实云端联通和回答质量未实测 |

## 1. 实现与核对

- application.yml 使用 deepseek-v4-flash、text-embedding-v4、1024 维、新 collection=rag_qwen_v4_1024，两个 Key 都留为空字符串。
- LangChain4j 实际序列化请求由本机 HTTP 服务捕获：检查生成 /chat/completions、向量 /compatible-mode/v1/embeddings、Bearer 鉴权、模型名、dimensions、float 编码、批次不超过 8、thinking.type=disabled 和 response_format.type=json_object。
- 不再默认下载、启动或等待本地模型；旧 app.env 和父进程中的模型、分块及索引变量不能替代 YAML 默认配置。DB 私有配置不改写、不打印。
- 保留旧索引 profile 防混用、结构化回答和引用 ID 验证；没有自动迁移或本地回退。

## 2. 实际命令与结果

| 命令 / 检查 | 结果 |
| --- | --- |
| mvn -f backend/pom.xml test（子任务），start.ps1 内再次 package（主线） | 两次均成功；49 项：48 通过、1 真实集成跳过，0 失败、0 错误；JAR 已重建 |
| npm test --prefix frontend-chat | 19 项通过 |
| npm test --prefix frontend-docs | 14 项通过 |
| npm run build --prefix frontend-chat / frontend-docs | 类型检查与构建均通过；start 中再次构建成功 |
| pwsh -NoProfile -File tests/cloud-startup.ps1 | PowerShell 7，68 项断言通过 |
| powershell.exe -NoProfile -ExecutionPolicy Bypass -File tests/cloud-startup.ps1 | Windows PowerShell 5.1，68 项断言通过 |
| python tests/security-check.py --self-test | 6 项隔离测试通过 |
| python tests/security-check.py | 实际 22 个前端产物 / 日志文件扫描通过，不输出凭据 |
| validate_docs.py --area-dir docs/02-需求实现 --kind uc --strict | 2 个需求包通过；9 份本轮文档的 33 个相对链接、编码与空白检查通过 |
| scripts/start.ps1 | 复用 MySQL / Chroma / 双前端，重建并启动新 Java 后端；不拉起本地模型 |

Python 使用本机 Codex 依赖目录中的 Python 3.12，不要求依赖 WindowsApps 别名。启动脚本隔离测试使用临时配置和命令替身；后端启动测试使用 H2；二者均不作为真实 MySQL / Chroma / 厂商调用证据。

## 3. 真实本机运行自检

通过 curl 调用当前新 JAR，结果在 .local/test-results/cloud-smoke.json，共 9 项检查：

| 检查 | 实际结果 |
| --- | --- |
| GET /api/health | 200，status=UP |
| GET /api/knowledge/status | 200，modelConfigured=false，totalDocuments=0 |
| GET /api/documents | 200，空列表 |
| POST /api/documents，上传非空青禾 TXT | 503，MODEL_NOT_CONFIGURED；没有新增记录 |
| POST /api/chat，非空合法问题 | 409，EMPTY_KNOWLEDGE_BASE，保留空库优先契约 |
| 两个前端 HTTP 访问 | :5173 / :5174 均为 200 |
| 本地模型端口 | 8091 / 8092 均无监听 |
| 拒绝上传后文档列表 | 仍为空，没有写入副作用 |

“有 READY 文档但缺 Key 的问答 503”由 CloudModelStartupTest 使用隔离数据验证；该测试验证模型网关没有被调用。真实库已清空，没有为验证而手工写入 READY 记录。

通过现有 Playwright 驱动本机 Chrome，1440×900，完成 3 项页面自检：

1. 问答页显示“模型未配置”，填入问题后发送按钮仍禁用。
2. 文档页显示空列表。
3. 在文档页选择青禾 TXT 并点击上传，收到 503，页面显示“向量模型尚未配置”，列表仍为空。

页面结果：.local/test-results/cloud-ui-smoke.json；截图：cloud-chat-missing-key.png、cloud-docs-missing-key.png。浏览器已关闭。本轮不改前端源码，上述仅是配置缺失路径的开发自检，不是云端完整业务验收。

## 4. 数据清理与授权

首次核实有两份 READY 文档：用户导入的供应商中心说明（129 块）和上轮青禾示例（1 块）。发现用户文档后先保留；用户随后明确表示“为了方便起见，我导入的文档可以直接删掉”。

据此，在切换配置前通过仍运行的旧服务，分别 DELETE 两个已核实的文档 ID，均返回 204。随后列表 total=0；旧 Chroma rag_mvp collection 的 count=0。没有直接清表，没有触及原有 3306 MySQL，也没有删除源文件、数据库目录或本地模型文件。

常规停启不会删数据。已获授权删除的记录不承诺可撤销，恢复需重新上传用户自己的源文件。测试夹具仍保留。

## 5. 官方来源与兼容说明

2026-10-20 核对以下官方文档：

- [DeepSeek 首次调用](https://api-docs.deepseek.com/quick_start)：兼容名称 deepseek-v4-flash 仍接受，实际路由至 DeepSeek-V4.1-Flash-1017，旧权重已退役。
- [DeepSeek thinking](https://api-docs.deepseek.com/guides/thinking_mode)、[JSON 输出](https://api-docs.deepseek.com/guides/json_mode)。
- [百炼向量模型](https://help.aliyun.com/zh/model-studio/embedding)：text-embedding-v4 属于 Qwen3-Embedding，单请求最多 10 段，每段上限 8192 tokens；本项目使用 8。
- [百炼同步向量接口](https://help.aliyun.com/zh/model-studio/text-embedding-synchronous-api)：1024 维与 float 编码。
- [百炼地域与域名](https://help.aliyun.com/zh/model-studio/regions)：默认使用北京 DashScope 兼容地址；官方提供业务空间专属域名，DashScope 旧域名自 2026-09-30 起不支持新特性。如账号要求专属地址，在 YAML 修改完整 base URL，Key 需匹配地域。

## 6. 未覆盖与残余风险

- 没有真实 API Key，没有真实云端向量写入、检索、生成回答、账单或配额验证，不以本机协议替身代替厂商联通。
- 未重新运行需要有效模型的完整 API / 浏览器回归；历史 resilience.py 依赖本地生成进程，不适用于当前默认云端模式。
- 检索阈值仍沿用原值，Qwen 下的召回效果和 DeepSeek 回答质量需有效 Key 与实际资料验证。
- 填 Key 后源码及 JAR 都含凭据，不应提交或分享；云端会接收问题和文档分块，需要使用者确认资料允许外发。
- 更换向量配置仍需先删除旧文档再重新导入；无自动重建。本项目无鉴权，只适用于本机单用户。

[规格](2026-10-20-01-overall-spec.md) · [计划](2026-10-20-02-overall-plan.md) · [运行说明](../../../README.md)
