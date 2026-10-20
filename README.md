# RAG · 第一版

两个独立 Vue 前端，共用 Java / LangChain4j 后端。MySQL 保存文档和分块，Chroma 保存向量；生成使用云端 DeepSeek，向量化使用云端 Qwen。默认不再下载或启动本地模型。

> **换电脑首次运行：** 请按 [项目启动指南](docs/项目启动指南.md) 操作，包含工具下载、环境配置、API Key、首次启动、日常启停和排错。

## 1. 填写两个 API Key

编辑 [application.yml](backend/src/main/resources/application.yml) 中的 rag 配置：

~~~yaml
rag:
  chat-base-url: https://api.deepseek.com
  chat-api-key: "" # 填写 DeepSeek API Key
  chat-model: deepseek-v4-flash
  embedding-base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
  embedding-api-key: "" # 填写阿里云百炼 API Key
  embedding-model: text-embedding-v4
  embedding-dimensions: 1024
~~~

只需在已有位置填写 Key，不要重复添加 rag 节点。Qwen 向量模型选用 text-embedding-v4。百炼地址默认北京区域，Key 必须匹配区域；如控制台提供业务空间专属地址，将 embedding-base-url 改为对应的完整兼容地址。DeepSeek 保留你指定的 deepseek-v4-flash 兼容名；截至 2026-10-20，官方说明该名称实际路由至 DeepSeek-V4.1-Flash-1017，不代表仍使用已退役的旧权重。

**模型配置以 YAML 为准**，旧 .local/app.env 中的本地模型地址、占位 Key、分块和 collection 不再加载。数据库密码仍保存在私有的 .local/app.env，模板见 [.env.example](.env.example)。前端不接收模型密钥。

两个 Key 未填写时，后端和页面仍能启动，modelConfigured=false；上传返回未配置错误；问答在空库时先提示导入文档，有就绪文档时提示未配置模型，不回退本地模型。配置齐全不等于厂商连接已验证。

## 2. 启动和访问

本机依赖已安装。在项目根目录 PowerShell 中执行：

~~~powershell
# 修改 YAML 后需要重新构建 JAR；不要用 -SkipBuild
./scripts/stop.ps1 -Service backend
./scripts/start.ps1
~~~

- 知识库问答：http://127.0.0.1:5173
- 文档管理：http://127.0.0.1:5174
- 后端存活检查：http://127.0.0.1:8088/api/health

模型 Key 已填且知识库有就绪文档时，可上传 TXT、Markdown 或文本型 PDF，查看真实分块，再到问答端提问并核对来源。样例保存在 [tests/fixtures](tests/fixtures)，例如“青禾项目的负责人是谁？”。

UC-002 曾按授权删除旧模型的两份导入记录；这不是每次启动的动作。UC-003 不清理或重切现有知识库，新规则只用于之后导入的文档。项目不保存可下载的原文件，需要重新导入时请使用自己的源文件。

### 另一台 Windows 机器

~~~powershell
./scripts/setup.ps1
# 可显式指定 Python 3.12 与 MySQL 8.4 二进制位置
# ./scripts/setup.ps1 -PythonPath C:/Python312/python.exe -MySqlBin 'C:/Program Files/MySQL/MySQL Server 8.4/bin'
./scripts/start.ps1
# 停止已核对 PID 和命令行的本项目服务，保留数据
./scripts/stop.ps1
~~~

前置工具为 Java 17、Maven 3.9、Node.js 24、Python 3.12 与 MySQL 8.4 二进制。setup 检查这些工具，安装项目内 Chroma 虚拟环境和 npm 依赖，不再下载 GGUF / llama.cpp。无 Docker 也可运行。脚本不安装或操作系统 MySQL 服务，不连接原有 3306 实例。

未修改代码或 YAML 时，可用 start.ps1 -SkipBuild 复用构建。后端正在运行时 start 会复用它，因此修改配置必须先停止后端再启动。旧 -SkipModels / -ExternalModels 仅保留命令兼容，不启用本地模型。

## 3. 第一版功能

- 单文件导入，最大 20 MiB；TXT / MD 要求 UTF-8，PDF 必须有文本层。不做 OCR。
- 异步导入，显示处理中、就绪或失败；查看分块全文、顺序和 PDF 页码，20 块一页。
- 会话内最多参考最近 5 轮完整问答，按上下文预算减少；追问先补全主题再检索，最多召回五个候选分块。
- 问答及来源快照保存到 MySQL，刷新及后端重启后可恢复当前会话；点击「新对话」隔离旧记忆，不删除旧记录。
- 新导入按标题、段落切块，长段按完整句拆分，保留原文重叠与 PDF 页码；不是模型语义聚类。
- 无依据与服务错误分开展示；验证模型引用 ID，不接受伪造来源。
- 删除文档、分块和向量；失败保留可重试记录，并先停止检索该文档。
- 就绪文档重启后保留；中断的导入转为失败，可删除后重传。

不包含账号、多知识库、跨会话长期记忆、历史会话列表、流式输出、批量导入、Word、Agent 或重排序。

## 4. 工程与数据

~~~text
frontend-chat/       独立问答前端，Vue + TypeScript + Vite
frontend-docs/       独立文档前端，Vue + TypeScript + Vite
backend/             Java 17 / Spring Boot / LangChain4j / PDFBox / Flyway
scripts/             Windows 初始化、构建、启动与停止
tests/              开发回归脚本和可上传样例
docs/               需求、接口契约、架构与验证记录
.local/              私有配置、数据库、日志、进程记录（已忽略）
~~~

| 组件 | 地址或位置 |
| --- | --- |
| MySQL | 127.0.0.1:13306 / rag_mvp；数据 .local/mysql/data |
| Chroma | 127.0.0.1:8000；数据 .local/chroma；新 collection=rag_qwen_v4_1024 |
| DeepSeek | 云端生成接口，显式关闭 thinking，返回 JSON |
| Qwen | 百炼 text-embedding-v4，1024 维，float；每请求最多 8 段 |
| 分块 | 按段落/句子选择边界；YAML 默认总上限 800 码点（含最多 100 码点重叠）；不静默截断正文 |
| 日志 | .local/logs/；启动失败先查对应 out.log / err.log |

不同向量模型、维度或 collection 不能混用。索引配置摘要在已有分块时会拒绝更换；以后再换模型，应先在旧配置下删除文档，再切换并重新导入，不会自动重建。

[compose.yaml](compose.yaml) 是未在本机验证的容器备选。若采用容器，先停止项目自带 MySQL、Chroma，再用 docker compose --env-file .local/app.env up -d，并以 start.ps1 -ExternalInfrastructure 启动应用。容器卷与本机目录数据不自动迁移。

## 5. 安全与验证边界

- Key 填入 YAML 后，源码与重新构建的 JAR 都包含凭据，**不要提交或分享**；也不要分享 .local/app.env、admin.cnf、bootstrap.sql 或整个 .local。
- 云端向量化会发送上传分块和检索问题；生成会发送问题、必要的最近问答及检索分块。有历史的追问通常增加一次问题改写请求和相应 Token 消耗。请确认文档和对话允许发送给相应厂商。
- 这是本机、单用户原型，无鉴权，服务仅监听 127.0.0.1；不要暴露到局域网或公网。Vite preview 不是生产 Web 服务器。
- UC-003 验证不调用付费模型；协议与回答使用明确的替身，不能作为真实厂商联通或回答质量证明。历史回答中的来源只是当时的快照，即使文档已删除仍可能显示，不能作为新问题的依据。

[本次优化与结果](docs/02-需求实现/UC-003-会话记忆与段落切块/2026-10-20-02-overall-plan.md) · [测试说明](tests/README.md) · [云端接入验证记录](docs/02-需求实现/UC-002-云端模型接入/2026-10-20-03-verification.md) · [历史本地模型验证](docs/02-需求实现/UC-001-最小可运行RAG/2026-10-20-03-verification.md) · [接口契约](docs/api-contract.md) · [后端配置](backend/README.md) · [系统架构](docs/01-架构设计/系统架构.md)
