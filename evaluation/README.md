# UC-004 独立配对评测

本目录的 runner 运行在 Maven test classpath，所有新增 Java 类均为 `package local.rag`。它直接构造生产服务，使用独立 H2 内存库及 V1/V2/V3 迁移，不启动生产 Web 应用，也不连接生产 MySQL。

## 离线运行

从项目根目录执行：

```powershell
./evaluation/run.ps1
```

脚本先执行完整 `mvn test` 和汇总器自测，再用生产 RULE chunker 校验冻结数据。它临时清除 `REAL_API_TEST_URL`，保证现有日常知识库集成测试保持禁用，结束时恢复变量。离线 runner 不读取模型配置，不连接 Chroma，不调用模型。现有 Spring 启动测试使用测试自己的 H2 配置。

只运行本轮 Java 单测：

```powershell
Push-Location backend
mvn '-Dtest=EvaluationBudgetTest,EvaluationMetricsTest,EvaluationConfigTest' test
Pop-Location
& 'C:\Users\Administrator\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' evaluation/report.py --self-test
```

`run.ps1` 的 `-PythonPath` 可覆盖 bundled Python 路径，不使用旧 `.local/chroma-venv`。数据生成与校验脚本由数据集模块维护；runner 只读取冻结文件。

## 真实运行

以下命令会产生真实费用。**本轮已完成一次真实配对运行**：[正式指标](runs/20261118091558914565500-67f90528/metrics.json) · [具体案例](runs/20261118091558914565500-67f90528/failure-analysis.md) · [完整验证说明](../docs/02-需求实现/UC-004-AI切块与量化评测/2026-11-18-03-verification.md)。24 次导入、240 次问答及独立盲审全部完成，最高费率估算 0.81500794 元。验证集召回 57.42% → 68.88%，答案正确率 59.38% → 64.06%；AI 成功率 75% 未达到 90%，整体 `complete=true, status=fail`，生产默认仍为 RULE。不能把功能交付写成门禁通过，也不要为挑结果重跑验证集。

采集结束的 `run.json` 原样保留 `awaiting_review`；最终评分状态看 `metrics.json`。`runs/` 中其他离线记录只证明结构校验，不是效果证据。

每次 live 前先核对官方当前最高费率，再维护价格文件的 `verifiedAt`、`sources`、`maxRates` 和 `confirmed`。runner 不会自动确认价格。日期按 UTC 校验，只接受当天或前一天，拒绝未来日期；数据集的固定日期不受此规则影响。

```powershell
./evaluation/run.ps1 -Live `
  -PriceFile ./evaluation/pricing-2026-11-18.json `
  -ChromaUrl http://127.0.0.1:18000 `
  -Preflight
```

`-Preflight` 可省略；启用时先发一次短 JSON completion 和一次 embedding，都计入同一账本。免费 `/models` 列表没有 alias 不代表 alias 不可用，runner 不以此拒绝实际请求。

Chroma 必须已在隔离的 loopback 端口运行，默认 `18000`，拒绝日常 `8000`。每个 split/arm 用随机新 collection，显式创建时 `get_or_create=false`，结束只删除本进程确认创建成功的 collection；不遍历或清理其他 collection。使用 cosine，与当前 LangChain4j 生产默认一致。创建结果不明或清理失败会保留 incomplete 记录。

生产模型参数来自 `backend/src/main/resources/application.yml`，以 SnakeYAML SafeConstructor 读取并拒绝重复键，仅解析 YAML 明确引用的本地 `.local/app.env` 占位符，不启动 Spring、不绑定数据库配置、不打印配置值。两臂沿用生产阈值、top5、上下文预算、提示、生成和引用校验。只允许已确认的 `api.deepseek.com` 根地址或 `/v1`、北京/新加坡 DashScope compatible-mode/v1，以及配置中的 `deepseek-v4-flash`、`text-embedding-v4`。保留用户 alias，另记 response model；本轮生成返回 `deepseek-flash`。向量 SDK 未在此暴露响应模型身份，embedding 账本该字段来自配置而非独立版本观测，限制见运行内 `model-observations.json`。

顺序固定为 dev A/B，再 validation A/B。A=RULE，B=LLM；每个 split 先导入其全部文档，随后在整个 split 库检索。同一 question 字符串共享 query embedding cache；分块索引 embedding 不走该 cache。没有历史轮次，调用生产 `chat(question, List.of(), traceObserver)`；金标准与证据不会进入模型请求。

## 费用约束

- 所有付费 HTTP 串行，embedding 每批至多 8 段；实际请求前，生产 observer 提供 UTF-8 字节上界及最大输出，账本预留后 `force(true)` 落盘才允许发送。
- 当前确认最高费率为 DeepSeek 输入 cache miss 2 元/百万、输出 8 元/百万，Qwen 输入按两区域最高 0.514 元/百万。使用更低费率会被拒绝，不假定 cache 折扣。
- 本轮目录内所有历史 live 账本共用累计 scope。独占文件锁同时保护历史重建与新请求。`runAccountedRmb` 是本次费用，`priorAccountedRmb` 是历史费用，`accountedRmb` 是累计值；只累计各 run 自身部分，避免重复计算。
- 累计预留达到或超过 16 元时停止新请求，20 元总上限保留 4 元余量；DeepSeek 的 chat/chunk 合计最多 400 请求，Qwen 最多 600 请求，付费预检也计入。
- usage 完整时按实际 token 结算；usage 缺失、请求失败时保留最坏预留。超过预留的异常 usage 如实入账并锁止。chunker 回退不能绕过 blocked 状态。
- 历史账本损坏会拒绝继续。没有自动重置、新预算或删除账本选项；不要通过删除/移动 runs 目录规避累计预算，新增 scope 需要另外明确授权。

账本仅保存阶段、split/arm/document/question 标签、token、模型名和费用，不保存请求/响应内容、URL、密钥或异常消息。问答结果另存评测证据文件。

## 输出与复现

每次运行写入新的 `evaluation/runs/<runid>/`：

| 文件 | 内容 |
| --- | --- |
| `run.json` | 状态、安全参数、预期数量、历史与本轮费用、临时 collection 清理状态 |
| `manifest.json` / `freeze.json` / `pages.json` | 冻结原始字节快照，保持原 SHA-256 |
| `prompts.json` | chunk / answer / rewrite 固定 system 文本及 SHA-256，不含用户配置 |
| `pricing.json` | 当次确认价格快照，仅 live 写入 |
| `cost-ledger.jsonl` | 每次预留、结算、失败留存、blocked、close 事件 |
| `<split>/<arm>/documents/*.json` / `documents.jsonl` | 原始 chunks、正文和重叠 spans、策略、回退原因、完整覆盖、导入耗时 |
| `questions.jsonl` | 问题和 gold、回答、实际 retrieved/context/cited、原文区间及耗时；技术错误独立标记 |
| `blind-review.json` | 随机 token 顺序、问题、gold、完整 sourceText、回答及实际引用文本、冻结 rubric |
| `private-mapping.json` | 单独保存 token 到 split/arm/question/document 的映射，不能交给盲评 agent |
| `regression.json` | 当次完整 Maven 回归计数、suite 哈希；真实 API 集成测试保持跳过 |
| `review-inputs/` / `review-output-*.json` / `review-assignments.json` | 独立盲审分包、原始评分与实际 agent ID；不把 private-mapping 交给评分者 |
| `reviews.json` / `review-results.json` | 合并复核、输入/输出哈希及逐项理由；原始判定保留 |
| `metrics.json` | 离线/待评分为 incomplete；完整后才决定 pass/fail |
| `post-analysis.json` / `failure-analysis.md` | 真实改善/退步案例与问题向量耗时近似扣除；不调用模型 |

`run.json.productionSourceSha256` 绑定 ChunkingService、DocumentParser、RagService、LangChainGateway、ModelGateway 五个工作树源码文件；不依赖未提交工作树的 HEAD。`prompts.json` 保存固定提示文本和指纹。

runner 强制 12 文档、120 题，dev=4/40、validation=8/80，每文档 8 可答、2 不可答，不可答 evidence 必须为空。严格验证 freeze 的 `manifestSHA256`、全部 `files` 哈希和文件集合、`validatorSHA256`，再验证生产提取文本与原文、UTF-16 证据。成功导入还必须验证正文连续且精确拼接、大小、原文重叠、Unicode 边界与 PDF 页码；任一 split 导入不完整就不在缩减库上答题。

## 独立盲评与汇总

本轮用 `prepare_blind_review.py` 按 source 哈希去重并生成四个独立包，每位 60 条；四名评分 agent 不继承实现历史，只读取分配包、`blind-review-instructions.md` 和冻结 rubric，不读取组别映射、其他包、指标或账本。每条答案都对照完整原文和必要事实判断；一位评分者负责一组文档的两臂答案，并非双人重复评分，可能仍有评分偏差。

后续独立运行可先打包（`$python` 为本机 Python 3.12，当前使用 `.local/eval-venv/Scripts/python.exe`）：

```powershell
$python = '.local/eval-venv/Scripts/python.exe' # 或本机 Python 3.12 完整路径
& $python evaluation/prepare_blind_review.py --run evaluation/runs/<runid>
```

将四个包分别交给独立 Codex agent，并保存实际 agent ID、输入路径、输出路径到 `review-assignments.json`（格式见本轮运行）。按 `blind-review-instructions.md` 输出原始评分，然后合并及评分：

```powershell
& $python evaluation/prepare_blind_review.py --run evaluation/runs/<runid> --merge
& $python evaluation/report.py --run evaluation/runs/<runid> --reviews evaluation/runs/<runid>/reviews.json
& $python evaluation/analyze_run.py --run evaluation/runs/<runid>
```

打包和合并拒绝覆盖既有文件，保留初始判定；若 gold 或判定有歧义，另记分歧与独立复核，不静默修改冻结数据、原始输出或重跑挑选分数。以上脚本均不发模型请求。

也可把 `blind-review.json` 单独交给独立 Codex agent，按其中冻结 rubric 逐题判定。该流程是 AI 辅助复核，不冒充人工评分。当前 agent 工具不公开精确底层 model ID 或温度，应保留 null 并注明不可获取，不编造模型标识。若另行使用付费评分 HTTP，它也必须经过相同累计账本，不能从 report.py 旁路付费。

单包流程先生成无分数模板；不要对已经完成的本轮运行执行此模板命令：

```powershell
$python = '.local/eval-venv/Scripts/python.exe' # 或本机 Python 3.12 完整路径
& $python evaluation/report.py --run evaluation/runs/<runid> --review-template evaluation/runs/<runid>/reviews.json
```

完成独立复核后，将模板顶层 `independent` 改为 true，填写 `reviewer`，保留 `reviewerKind=independent_codex_agent` 和 `blindPackageSha256`。每项需要 token、可答题的 `answerCorrect` 布尔值或不可答题的 `refusalCorrect` 布尔值、逐引用 `citationSupported` 布尔列表及非空 `rationale`。允许额外 `dimensions`、`atomicFacts` 等诊断字段。引用列表长度须与实际引用一致；未评分保持 null，不能补零或补通过。保留评分器原始输出与分歧记录，汇总器另存 `review-results.json`。

```powershell
& $python evaluation/report.py --run evaluation/runs/<runid> --reviews evaluation/runs/<runid>/reviews.json
```

汇总器退出码：pass=0，完整但门禁未过=1，incomplete=2。缺少评测文件、技术错误、账本/来源区间/冻结绑定异常、清理失败、缺盲评或缺完整回归均不能通过。离线结构校验不代表真实回答质量通过。

主召回指标对每题计算“被 top5 原文区间并集完整覆盖的证据项比例”，不可答题不进入召回分母；另报全部必要证据共同覆盖率和 UTF-16 覆盖比例。引用重复、重叠和其他文档区间不能膨胀召回。逐题结果先在文档内平均，再对文档平均；配对 bootstrap 每次同时抽取两臂的整个文档簇，固定 seed=20261118、5000 次、95% percentile CI。CI 用于描述，门禁使用配对点差。

validation 门禁：EvidenceRecall@5 的 B−A 至少 0.05；独立复核的答案、拒答、引用支持与严格通过率不退步；**正常 validation 八份文档 LLM 成功率至少 90%**，因此 7/8 不通过。overall 十二份成功率只作说明。严格引用指标同时要求独立语义支持与所有必要 gold 证据区间覆盖，字符串/数值匹配仅作辅助。另报块数量、codepoint/UTF-16 长度 p50/p95/max、导入/答题延迟、按阶段费用及已知实际 token 与缺 usage 请求数。

## 本轮结果的限制

数据与 gold 均是 AI 构造的虚构金融规则，盲审是 AI 辅助复核，不是人工验收。验证只有 8 文档，未测试真实敏感业务文件。开发/验证文档族隔离，冻结后不调参；分组为“AI 加回退”，不能把回退样本算 AI 成功。三个真实长窗样本全部因超长分组回退，是本轮主要未通过项。

A 先计算并缓存问题向量，B 共享缓存；原始回答延迟不能当作 B 提速证据。`analyze_run.py` 仅事后减去实际 query-embedding 请求时长；固定 A→B 顺序、厂商缓存和网络仍可能造成混淆。答案、引用差异也有模型生成波动，不能全部归因于切块。证据区间以完整段落为主，其完整覆盖指标比“语义上足够回答”更严格，需与独立语义复核一起看。

工具自测（全部离线）：

```powershell
& $python evaluation/report.py --self-test
& $python evaluation/prepare_blind_review.py --self-test
& $python evaluation/analyze_run.py --self-test
& $python evaluation/validate_dataset.py --self-test
```
