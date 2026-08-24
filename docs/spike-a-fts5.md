# Spike A — FTS5 schema 与检索契约

**结论：schema 可原样移植；但 Android 必须自带 SQLite，不能用系统的。**

## 测到了什么

`FactStoreFts5Test` 在真的 SQLite 上建表、插入、检索、增删改，9 项全绿：

| 验证项 | 结果 |
|---|---|
| `search_text` 构造与上游逐字一致 | ✅ 12/12 用例 |
| FTS 查询串与上游逐字一致 | ✅ 15/15 用例 |
| FTS5 可用、schema 建得起来 | ✅ |
| 中文子串检索（n-gram 生效） | ✅ 「传送带」能命中「记忆传送带每天凌晨四点滚动」 |
| 英文与标识符按词检索 | ✅ |
| 带引号查询不破坏 FTS 语法 | ✅ 双引号按 `""` 转义 |
| 触发器同步增删改 | ✅ `facts_ai` / `facts_ad` / `facts_au` |
| 按 session 删除连带清索引 | ✅ |

## 为什么这条链路可以做到「逐字一致」

FactStore 的检索文本**不依赖分词器**：NFKC 归一 + CJK 2/3-gram，纯确定性。
这和会话搜索（Spike B，依赖 jieba，两个实现有 8.7% 差异）完全不同。

意味着：从桌面导出的角色卡带过来，记忆检索行为不会有任何偏移。

## 两条要记住的性质

**① 归一时不小写。** 与上游一致。FTS5 的 `unicode61` 自己会做大小写折叠，
在写入侧再折一次会让 `search_text` 和原文对不上。

**② n-gram + OR 的召回极宽。** 写入时把每段 CJK 铺成全部 2/3-gram，查询时所有
token 用 OR 连接 —— **只要共享一个二字片段就会命中**。实测「第一个会话」会同时
搜出「第二个会话产生的事实」，因为共享「个会」「会话」。

好处是几乎不会漏搜；代价是结果混入低相关度条目，排序完全压在 FTS5 的 `rank` 上。
**上层不能把「有结果」当成「找到了」**，必须带 limit 并尊重 rank 顺序。

## Android 侧的硬性结论

**不能用 `android.database.sqlite`。** 系统自带的 SQLite 版本随 OS 版本走，
FTS5 是编译期选项，可用性无法假设 —— 而 FactStore 的整个检索方案建立在它之上。

**必须 bundle 一个自带 FTS5 的 SQLite**，候选：

| 方案 | 说明 |
|---|---|
| `requery/sqlite-android` | 自带编译好的 SQLite，确定开了 FTS5，API 兼容 `android.database` |
| SQLCipher for Android | 也自带 SQLite，附带加密（记忆是隐私数据，这点有额外价值） |

选型建议：先用 `requery/sqlite-android` 打通；如果后面要给记忆库加密，
再评估换 SQLCipher（两者 API 面接近，切换成本可控）。

## 待设备验证

这个 spike 用的是 `sqlite-jdbc`（自带 SQLite，开了 FTS5），**不能代表 Android**。
以下必须在真机上再确认一次：

- [ ] bundle 的 SQLite 确实报告 `ENABLE_FTS5`（跑同一份 `PRAGMA compile_options` 断言）
- [ ] `unicode61` 分词器在 Android 的 ICU 环境下行为一致（尤其日文假名、韩文）
- [ ] WAL 模式在 Android 的存储路径下可用（内存库跳过了这项）
- [ ] `mmap_size = 30MB` 在低端设备上的实际表现（可能要下调）
- [ ] 从桌面导出的角色卡里的 `facts.db` 能被直接打开（版本兼容性）
