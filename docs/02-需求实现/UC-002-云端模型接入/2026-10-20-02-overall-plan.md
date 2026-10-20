# 云端模型接入实施计划

| 项目 | 内容 |
| --- | --- |
| 依据 | UC-002 已批准规格 |
| 状态 | 本地实现与验证完成；真实厂商联通需 Key |
| 日期 | 2026-10-20 |

## 1. 任务与边界

| 任务 | 可独立验证行为 | 执行者 / 修改范围 | 关联 | 状态 |
| --- | --- | --- | --- | --- |
| T-01 | YAML 云端默认值、空 Key 防误配、DeepSeek / Qwen 真实 HTTP 请求序列化与错误语义；本机协议替身验证 | 后端子 agent，仅 backend/src/**；不改 backend/README.md | AC-001～003、005 | 已完成 |
| T-02 | 启动 / 安装不再依赖本地模型，旧 env 模型配置不覆盖 YAML；脚本可解析及可复现局部检查 | 脚本子 agent，仅 scripts/Common.ps1、scripts/setup.ps1、scripts/start.ps1、.env.example、tests/cloud-startup.ps1、tests/security-check.py | AC-001、004、005 | 已完成 |

两个任务基于固定现有 rag 属性契约独立验证；不修改 HTTP API。主 agent 负责来源核对、按追加授权清理旧导入记录、停启集成、文档和最终验证，不并发改写两项任务的文件。

## 2. 固定共享输入

- application.yml：rag.chat-base-url=https://api.deepseek.com，chat-model=deepseek-v4-flash；embedding-base-url=https://dashscope.aliyuncs.com/compatible-mode/v1，embedding-model=text-embedding-v4，embedding-dimensions=1024；两个 api-key 是空字符串。
- 新 collection=rag_qwen_v4_1024，分块 800 / 100。模型和索引配置以 YAML 为准，不再从旧 app.env 中的 CHAT_*、EMBEDDING_*、CHUNK_* 或 CHROMA_COLLECTION 读取。
- scripts 只从 app.env 导入数据库、Chroma 地址等基础设施配置；默认不下载/启动本地模型，旧命令参数可兼容但不得恢复本地模型。
- 不改用户私有文件、不打印其内容、不擅自清库、不调用真实云端接口、不提交或推送。没有 Key 的云端真实问答明确不计为通过。

## 3. 验证与回滚

后端 mvn test/package、PowerShell 解析与启动局部检查、两个前端构建、真实本机启动与空 Key 上传 503、空库问答 409。只停止本项目核对过的进程。常规停止不清数据；回滚需恢复原代码、模型配置和对应 collection，不能混用不同向量。当前无 Git 元数据，不宣称已提供版本快照；用户授权删除的文档只能用源文件重新导入。

## 4. 执行记录

- T-01 由后端子 agent Euclid 执行；修改 application.yml、LangChainGateway、新增 3 份云端测试并补充 ServiceContractTest。mvn test 49 项，48 通过、1 真实集成跳过。
- T-02 由脚本子 agent Fermat 执行；修改 Common / setup / start、.env.example、cloud-startup.ps1 和 security-check.py。PowerShell 5.1 与 7 各 68 项断言，安全扫描自测 6 项通过。
- 主线审查实现后运行默认 start：mvn package 再次通过，两个 Vue 构建成功，新 JAR 启动；两前端测试分别 19、14 项通过。
- 实际 curl 本机自检 9 项通过，Chrome 1440×900 配置缺失自检 3 项通过，真实资产 / 日志扫描 22 文件通过；8091/8092 无监听。
- 用户追加授权删除原有文档，主线在旧服务上按已核实 ID 执行两次 DELETE，均 204；列表和旧 Chroma count 均为 0。没有直接清表或改私有配置。
- 集成审查确认原契约为空库问答优先 409，有就绪文档但缺 Key 才 503；保留行为并修正规格描述，没有为改接云端扩大接口变更。
- 已同步根 / 后端 / 测试 README、文档和架构索引、系统架构。正式结果及未覆盖边界见 [验证记录](2026-10-20-03-verification.md)。
- 没有云端 Key，不进行真实厂商联通、回答质量或计费验证；没有提交、推送或发布。
