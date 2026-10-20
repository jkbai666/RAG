# RAG Java 后端

本服务实现 UC-001 v2 的 HTTP 契约，并按 UC-002 使用云端生成与向量模型。仅用于本机、单用户、共享知识库；不包含登录鉴权，不应直接暴露到公网。监听地址固定为 `127.0.0.1`，默认端口 8088。

## 构建和启动

要求 Java 17、Maven 3.9，以及已经创建的 MySQL schema 和可用的 Chroma 服务。

~~~powershell
mvn -f D:\BYS\RAG\backend\pom.xml test
mvn -f D:\BYS\RAG\backend\pom.xml package
java -jar D:\BYS\RAG\backend\target\rag-backend-0.0.1-SNAPSHOT.jar
# 或在后端目录：mvn spring-boot:run
~~~

模型配置和两个 API Key 直接在 src/main/resources/application.yml 中填写；修改后重新构建 JAR 并重启，不能只复用旧 JAR。数据库连接等基础设施配置由进程环境提供，见下表。Spring Boot 不会自动读取项目根目录的 `.local/app.env`；根目录启动脚本负责加载。不要把随机数据库密码写进本目录，也不要打印凭据。Flyway 只在指定的数据库内创建和迁移本应用的表；它不会创建 MySQL 用户或 schema。

数据库不可用时启动会失败；模型未配置不会阻止启动，health 和 knowledge/status 仍可使用。上传只依赖向量模型配置，问答需要生成和向量模型配置。modelConfigured 只检查配置是否齐全，不表示连接已验证。

## 环境变量

根启动脚本仅从 app.env 导入 DB_*、CHROMA_BASE_URL、CHROMA_TIMEOUT_SECONDS 和 PORT。下表模型/检索调优变量仅适用于直接 java 启动；根脚本会清理遗留模型调优环境，日常使用请在 YAML 修改相应参数。

| 变量 | 默认或说明 |
| --- | --- |
| DB_URL | jdbc:mysql://127.0.0.1:13306/rag_mvp?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC |
| DB_USERNAME | rag |
| DB_PASSWORD | 无默认密码，必须由进程环境提供 |
| CHROMA_BASE_URL | http://127.0.0.1:8000 |
| RETRIEVAL_MIN_SCORE | 0.6；LangChain4j 归一化相关性分数，0～1，需结合实际模型验证 |
| MODEL_TIMEOUT_SECONDS | 90；模型 HTTP 请求超时，禁用应用级模型重试 |
| CHROMA_TIMEOUT_SECONDS | 20；单次 Chroma 请求超时 |
| CHAT_MAX_OUTPUT_TOKENS | 700 |
| CHAT_CONTEXT_BUDGET | 7000；使用 UTF-8 字节数保守估算输入 token，包含输出预留和消息开销；只选入完整分块 |
| PORT | 8088 |

## YAML 中的模型配置

| 属性（rag 下） | 值或用途 |
| --- | --- |
| chat-base-url / chat-model | https://api.deepseek.com / deepseek-v4-flash |
| chat-api-key | 空字符串，填写 DeepSeek Key |
| embedding-base-url / embedding-model | https://dashscope.aliyuncs.com/compatible-mode/v1 / text-embedding-v4 |
| embedding-api-key | 空字符串，填写北京区域百炼 Key；其他区域须同步修改地址 |
| embedding-dimensions | 1024，验证返回维度；float 编码，每个 HTTP 请求最多 8 段 |
| chroma-collection | rag_qwen_v4_1024；显式 V2、default_tenant/default_database |
| chunk-size / chunk-overlap | 800 / 100；Unicode code point 计数，重叠小于大小 |

DeepSeek 请求显式 thinking.type=disabled，并使用 response_format.type=json_object。兼容名称 deepseek-v4-flash 在 2026-10-20 官方文档中路由至 DeepSeek-V4.1-Flash-1017。百炼新账号如需业务空间专属域名，在 YAML 替换完整 base URL。

根目录脚本不再加载旧 app.env 的模型、collection、分块变量；未配置 Key 不发起云端请求、不回退本地模型。Key 进入源码和构建 JAR，请勿提交或分享；上传分块、问题与检索上下文将发送给相应厂商。问题最多 2000 code points 是业务上限，不静默截断输入。

同一 collection 必须使用相同向量模型及维度。服务会持久化模型地址、名称、配置维度和 collection 的摘要，存在分块时拒绝混用不同配置。更换索引配置前，应在原配置下删除所有文档，不能直接换模型后沿用旧向量。模型服务以同一名称替换底层权重无法由此摘要检测，部署方必须避免这种操作。

## 行为与失败处理

- 所有成功和错误响应遵守共享契约，不增加外层 data。
- 上传只接受一个 file，最大 20 MiB；保存的文件名仅作展示，不作为磁盘路径。TXT/MD 严格 UTF-8 解码，拒绝损坏编码和二进制 NUL；保留 HTML 原文，前端必须按纯文本展示。
- 导入在单工作线程中执行，最多五个运行或排队的文件，超出返回 503。使用 PDFBox 3 提取文本层；跨页连续分块并记录实际页码范围，不为每页另起一个块。提取正文超过 400 万 UTF-16 单元会明确失败，不做静默截断。
- MySQL 先保存全部稳定分块 ID 和正文，再分批向量化、写入真实 Chroma。全部完成后才切换到 READY。失败和 PROCESSING 文档均不会进入问答上下文。
- 重启将中断的 PROCESSING 标为 FAILED。清理任务在启动 10 秒后开始，每 30 秒重试残留索引；清理未完成时仍保留稳定 ID 供重试。
- 删除 PROCESSING 返回 409。其他文档先在 MySQL 中变为不可检索，再删除向量，最后删除 MySQL 数据。失败保留 FAILED 记录和错误，重复 DELETE 可重试；后台清理不会自动删掉失败文档记录。
- 问答最多召回五个候选，使用 MySQL READY 状态和正文重新核对。完整分块无法放入上下文时返回 422，不截断分块，不假装没有依据。
- 生成请求要求 JSON 对象：answer、grounded、citationIds。只允许引用实际上下文中的 ID，并在返回前重新读取 MySQL 核对来源。坏 JSON、坏引用、空回答和矛盾的 grounded 标记返回 502。
- 无有效召回或模型声明证据不足时返回 grounded=false、sources=[]，使用统一的依据不足文案。技术异常不会伪装成此状态。
- 问答持有读锁直到生成和最后校验结束，删除通过写锁串行化。已经开始的问答可以先于删除完成；删除成功后开始的问答不会使用已删文档。
- 上游异常统一转换成不含 URL、密钥或响应体的中文信息；超时返回 504。HTTP 请求和模型响应日志默认关闭。

## 测试

新增云端 HTTP 协议测试通过本机服务捕获真实 LangChain4j 请求，验证路径、鉴权、模型名、维度、float、批次、thinking 和 JSON 参数；不调用真实云端厂商。

默认单元/契约测试使用真实 PDFBox、H2 中的 SQL 和明确标注的替身模型/向量网关。它们不替代真实 MySQL、Chroma 或模型验证。Surefire 报告在 `D:\BYS\RAG\backend\target\surefire-reports`。

真实服务测试连接一个**已经运行**的后端，不自动启动第二个应用或触发启动恢复。它上传自己生成的 TXT、MD、PDF，核对正文与页码、真实回答引用、依据不足，并在 finally 中删除它创建的文档。

~~~powershell
$env:REAL_API_TEST_URL='http://127.0.0.1:8088'
mvn -f D:\BYS\RAG\backend\pom.xml '-Dtest=RealServicesIntegrationTest' test
Remove-Item Env:REAL_API_TEST_URL
~~~

未设置 REAL_API_TEST_URL 时，该测试明确跳过。真实服务集成测试应在本项目隔离环境执行，不对生产环境运行。并发测试或其他人在同一知识库写入数据可能影响问答相关性，应使用安静的演示知识库。

## 固定依赖

Spring Boot 3.5.16、LangChain4j core/OpenAI 1.22.0、LangChain4j Chroma 1.22.0-beta32、PDFBox 3.0.7。MySQL Connector/J 和 Flyway 版本由 Spring Boot BOM 固定。使用 JdbcTemplate，不引入 JPA、Redis、消息队列或 Python RAG 服务。
