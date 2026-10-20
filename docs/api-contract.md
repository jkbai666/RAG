# 第一版 HTTP 契约

所有 API 以 `/api` 为前缀，JSON 字段采用 camelCase。两个前端通过 Vite `/api` 代理请求 Java 服务。成功返回直接对象，不包装额外 data。错误返回 `{ "code": "ERROR_CODE", "message": "中文原因" }`，不得包含凭据或异常堆栈。

## 文档

文档对象：`{id: string, fileName: string, format: string, sizeBytes: number, status: "PROCESSING"|"READY"|"FAILED", chunkCount: number, createdAt: ISO8601 string, errorMessage: string|null}`。

分块对象：`{id: string, documentId: string, index: number, text: string, pageStart: number|null, pageEnd: number|null}`。index 从 1 开始。

- `GET /api/documents` → `{items: Document[], total: number}`（按创建时间降序）。
- `POST /api/documents`，multipart 字段 `file` → 202 Document，上传后异步处理。客户端据列表 PROCESSING 状态每 1.5 秒轮询。
- `GET /api/documents/{id}` → Document。
- `GET /api/documents/{id}/chunks?page=1&pageSize=20` → `{items: Chunk[], total: number, page: number, pageSize: number}`。仅 READY；page 从 1 开始，pageSize 1～100。
- `DELETE /api/documents/{id}` → 204；PROCESSING 返回 409；失败不假装成功，前端保留记录并显示原因。

## 知识库与问答

- `GET /api/knowledge/status` → `{readyDocuments: number, totalDocuments: number, modelConfigured: boolean}`。modelConfigured 只表示必要配置齐全，不宣称模型服务可达。
- `POST /api/chat`，JSON `{question: string}` → `Answer`，保留原有无记忆单轮兼容接口。
- `Answer`：`{answer: string, grounded: boolean, sources: Source[]}`。
- Source：`{chunkId: string, documentId: string, fileName: string, chunkIndex: number, text: string, pageStart: number|null, pageEnd: number|null}`。只包含回答实际引用且已校验的分块。
- 无相关证据：200、grounded=false、sources=[]，answer 明确说明依据不足。空知识库直接请求返回 409（EMPTY_KNOWLEDGE_BASE）。
- 技术异常：不返回 grounded=false 伪装无答案；配置缺失 503、模型/Chroma 失败 502 或超时 504；非法输入 400/413/422；未找到 404。
- `GET /api/health` → `{status: "UP"}`，仅存活检查，不泄露配置。

## 会话与记忆（UC-003）

会话 ID 和 requestId 均为 UUID。成功响应均为 200：

- `POST /api/conversations` → `{id: string, exchanges: []}`，不调用模型。
- `GET /api/conversations/{id}` → `{id: string, exchanges: Exchange[]}`，按成功完成顺序返回完整历史，不调用模型。
- `Exchange`：`{requestId: string, question: string, response: Answer, createdAt: ISO8601 string}`。
- `POST /api/conversations/{id}/messages`，JSON `{question: string, requestId: string}` → `Answer`。

问题非空且最多 2000 个 Unicode 码点；非法 UUID 或问题返回 400。会话不存在返回 404 / `CONVERSATION_NOT_FOUND`。同会话串行处理；同一 requestId 与相同规范化问题重放返回已有成功结果，不重复调用模型。相同标识换问题返回 409 / `REQUEST_ID_CONFLICT`。标识按会话隔离。

模型或保存失败不产生已完成问答；数据库可保留请求占位以绑定原问题，同标识可重试。浏览器断线不保证取消服务器任务，服务端若已完成则刷新恢复；重试成功结果属于历史快照，不保证重新校验当下文档状态。新消息始终重新检索、复核当前可用文档，不能引用历史答案中的失效来源。

模型最多使用最近 5 轮完整问答；超预算时移除最旧整轮。追问通过生成模型输出独立检索问题，再向量化和检索；改写结构无效返回 502 / `INVALID_REWRITE_RESPONSE`，不静默退回错误结果。上下文无法容纳当前问题与完整来源时返回 422 / `CONTEXT_LIMIT`。

服务端保存完整成功历史，5 轮仅限制模型上下文，不限制页面恢复。前端本地存储仅保存当前会话标识和待确认请求，不保存成功历史；恢复失败需显式重试恢复或选择新对话，不自动换会话。未提供会话历史列表、删除接口、身份鉴权或跨会话记忆。

## 切块行为（UC-003）

分块 HTTP 结构和分页不变。仅新导入采用标题、自然段和完整句子边界；`chunk-size` 是包含重叠的总长度上限，`chunk-overlap` 是原文重叠上限，默认 800 / 100 个 Unicode 码点。首块无重叠，前文不足时按实际长度取用；PDF 页码包含重叠范围。不能安全分割的超长句子或代码围栏使异步导入变为 FAILED，记录 `UNSPLITTABLE_TEXT` 对应的可读原因；上传接口已返回的 202 不会变成同步 422。既有文档不重切。

## 前端约束

问答端默认 5173，文档端默认 5174；Vite host 固定 127.0.0.1，代理默认 8088，可通过 `VITE_API_TARGET` 修改。互相链接由 `VITE_DOCUMENTS_URL` / `VITE_CHAT_URL` 配置，默认新标签页打开并设 noopener。不做跨应用内存同步。中文界面，纯文本安全渲染，不使用 v-html 展示不可信内容。每个工程必须有独立 package.json、锁文件、安装启动构建命令；Vue 3.5.44，Vite 8.3.4，TypeScript 选择兼容版本，提交锁文件。
