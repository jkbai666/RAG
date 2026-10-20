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
- `POST /api/chat`，JSON `{question: string}` → `{answer: string, grounded: boolean, sources: Source[]}`。
- Source：`{chunkId: string, documentId: string, fileName: string, chunkIndex: number, text: string, pageStart: number|null, pageEnd: number|null}`。只包含回答实际引用且已校验的分块。
- 无相关证据：200、grounded=false、sources=[]，answer 明确说明依据不足。空知识库直接请求返回 409（EMPTY_KNOWLEDGE_BASE）。
- 技术异常：不返回 grounded=false 伪装无答案；配置缺失 503、模型/Chroma 失败 502 或超时 504；非法输入 400/413/422；未找到 404。
- `GET /api/health` → `{status: "UP"}`，仅存活检查，不泄露配置。

## 前端约束

问答端默认 5173，文档端默认 5174；Vite host 固定 127.0.0.1，代理默认 8088，可通过 `VITE_API_TARGET` 修改。互相链接由 `VITE_DOCUMENTS_URL` / `VITE_CHAT_URL` 配置，默认新标签页打开并设 noopener。不做跨应用内存同步。中文界面，纯文本安全渲染，不使用 v-html 展示不可信内容。每个工程必须有独立 package.json、锁文件、安装启动构建命令；Vue 3.5.44，Vite 8.3.4，TypeScript 选择兼容版本，提交锁文件。
