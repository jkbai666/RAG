# UC-004 离线评测集

数据冻结日期为2026年11月18日，种子为20261118。12份文档为本轮独立编写的虚构金融业务规则，每份8道可答、2道不可答，共120道。开发集4份40题，留出集8份80题。没有复用项目已有`test/`文档，也没有从真实客户资料取样。

| 分割 | 文档 | 规则族 | 格式 |
| --- | --- | --- | --- |
| dev | dev-01 | 预售监管专户节点与留存 | 长Markdown，含英文办理札记 |
| dev | dev-02 | 农机循环授信占额和计息 | TXT |
| dev | dev-03 | 商户结算留存与拒付争议 | 三页文本PDF，英文正文 |
| dev | dev-04 | 航段延误保险触发与年度限额 | Markdown |
| validation | val-01 | 回购抵押品折扣、补仓和换券 | 三页文本PDF，英文正文 |
| validation | val-02 | 基金申购费、未知价与登记 | Markdown |
| validation | val-03 | 进口信用证交单、不符与承兑 | TXT |
| validation | val-04 | 保费融资退保清算 | Markdown |
| validation | val-05 | 碳配额远期交收与差额平仓 | 长TXT，含英文业务附录 |
| validation | val-06 | 设备信托现金瀑布与触发修复 | 多页长文本PDF，英文正文 |
| validation | val-07 | 留学跨境汇款费与召回 | Markdown |
| validation | val-08 | 保理池集中度、稀释与追索 | TXT |

规则族按文档分割，不将同一条规则改写或换数字后放到另一分割。dev-01、val-05和val-06的提取正文各至少22000字节UTF-8、超过16000个UTF-16单元，覆盖保守预算下的真实分窗；其余短文保留。三份长文的办理、对账、清算和版本案例交错插入原规则，原题目、答案与数值不变。问题包括运算、包含边界、跨段及跨页证据。部分题需要英文正文到中文问题的术语映射。拒答题询问该文档确实未披露的信息，不能由演练数值外推。表面重复检测只能排除长片段复制，不能证明全部语义独立；规则族与逐题业务逻辑须一并审查。

## 文件与契约

`manifest.json`严格采用已约定的document/question字段，便于Java/Jackson读取。每条证据都是提取文本中的原始半开区间，使用Java UTF-16代码单元。多条证据全部必需，不代表备选答案。非BMP字符在dev-01中用于实际偏移验证。

`documents/`为真正导入的MD、TXT和PDF，`extracted/`为生产解析口径正文。MD/TXT无BOM且逐字节保持UTF-8原文。PDF由标准Helvetica文本绘制，含独立页面、文本层和标准交叉引用表，不依赖OCR、系统中文字体或网络库。`PdfText.java`使用后端缓存中的PDFBox 3.0.7逐页提取；`--verify-parser`还实例化已编译的生产DocumentParser，逐字比较正文与页区间，不启动Spring应用。

`pages.json`保存PDF页区间；`coverage.json`保存独立规则族、各文档长度、页数与标签统计。`corpus.py`是AI构造的虚构来源与金标准，参考答案仍需独立复核，不是真实金融建议；`generate.py`确定性生成PDF、文本、manifest和冻结记录。`rubric.md`规定评分及盲AI辅助复核；本轮仅交付离线结构与完整性验证，不宣称模型回答或金标准语义已获独立验收。

本次提取基线为Windows、Java 17、PDFBox 3.0.7，PDF提取正文保留CRLF。切换操作系统若导致PDFBox换行变化，验证应报告不一致，不能静默归一化冻结正文或偏移。MD/TXT源文件使用LF，按生产UTF-8解码逐字保留。

## 只读验证

在项目根目录运行，Python使用3.10或以上版本，脚本只依赖标准库；PDF复提取另外需要Java 17及已缓存的依赖。

```powershell
$datasetPython = 'C:\Users\Administrator\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
& $datasetPython evaluation/validate_dataset.py
& $datasetPython evaluation/validate_dataset.py --self-test
& $datasetPython evaluation/validate_dataset.py --verify-parser
& $datasetPython evaluation/dataset/generate.py --check --verify-parser
```

Java从PATH定位；默认从用户`.m2/repository`读取pdfbox、fontbox、pdfbox-io的3.0.7及commons-logging缓存，也可显式设置`M2_REPO`或`PDFBOX_CLASSPATH`。缺少依赖就失败，不联网下载。生成和只读重现检查需要`backend/target/classes`中已编译的生产解析器，coverage中的`parserUnits800_100`是其800码点块上限、100码点重叠下的实际unit数量。普通结构校验不需要Java。只有PDFBox缓存时可先运行`--verify-pdf`，但不能把它表述为生产解析器比对已通过。

首次构建使用`generate.py`，已有freeze时默认拒绝重建。只读`--check`重新生成所有文档字节、重跑提取并与磁盘产物及冻结哈希比较。`--rebuild-frozen`仅为正式评测开始前显式重建提供，live开始后禁止用它调样本。新修订应另建版本。生成器和验证器不会读取应用配置、连接数据库、操作向量库、创建会话或调用模型；不使用`.local/chroma-venv`。

冻结覆盖整个dataset中的持久文件及外部`validate_dataset.py`的字节哈希。`freeze.json`自身、Python缓存与`.work`临时目录不纳入递归哈希；不得在这两个临时位置存放评测输入。最终运行记录应另外保存freeze文件的SHA-256。长度统计是实际提取正文，不含问题、提示及两臂重复开销；20元预算须由运行器对所有付费阶段统一限额。

仓库的`.gitattributes`将`evaluation/dataset/**`、`evaluation/runs/**`及`evaluation/validate_dataset.py`设为`-text`，即使本机`core.autocrlf=true`也保留冻结字节，防止换行转换破坏文件哈希、PDF二进制或证据偏移。临时`.work/`与costlock由仓库忽略规则处理；它们不能替代冻结输入或正式运行记录。
