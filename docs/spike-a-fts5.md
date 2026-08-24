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

**必须 bundle 一个自带 FTS5 的 SQLite。** 这条不再是推测 —— 目标平板实测结果：

```
系统 SQLite: 3.44.5
  FTS5 可用: false
```

探针的判据是直接执行 `CREATE VIRTUAL TABLE … USING fts5(…)`，而不是翻
`PRAGMA compile_options` —— 后者在某些 ROM 上会误报，前者就是最终要用的那个能力。
语句直接抛异常，所以结论是确定的，不存在假阴性。

选型经过：

| 方案 | 结论 |
|---|---|
| `requery/sqlite-android` | ✗ 不在 Maven Central 上（`com.github.requery` 是 JitPack 坐标），要额外加仓库，多一个出口依赖 |
| SQLCipher for Android | ✗ 在 Maven Central 上且自带 FTS5，但它是加密库 —— 记忆加密确实有价值，可那是独立的产品决定，牵扯密钥存放在哪，不该顺手捎上 |
| **`androidx.sqlite:sqlite-bundled`** | ✓ 官方维护，Google Maven 上有，随包带一份编好 FTS5 的 SQLite |

用的是 `androidx.sqlite:sqlite-bundled`。它走的是新的 `SQLiteDriver` / `SQLiteConnection`
API（不是 `android.database.sqlite`），FactStore 还没落地，现在换成本最低。

**「随包的那份有 FTS5」同样不靠假设 —— 已在真机上验完。** 探针对两个引擎各跑一遍
完整链路，2026-08-24 目标平板的结果：

```
系统 SQLite: 3.44.5          随包 SQLite: 3.46.0
  FTS5 可用: false             FTS5 可用: true
  schema 建立: false           schema 建立: true
  中文子串检索: false          中文子串检索: true
  触发器同步索引: false        触发器同步索引: true
  可用于 FactStore: 否         可用于 FactStore: 是
```

中间两项是这次验证的重点，光有 FTS5 并不够：

- **中文子串检索**：探针查的「传送带」是「记忆传送带每天凌晨四点滚动」的**中间
  子串**，`unicode61` 分词器自己切不出来，能命中完全依赖写入时铺的 3-gram。
  这条过了才说明 [FactSearchText] 那套 n-gram 方案在设备上真的成立。
- **触发器同步索引**：删掉一条 fact 之后再搜，不能还搜得到。外部内容表的索引不会
  自动跟随主表，全靠三个触发器；漏了会留下"搜得到但已经删了"的幽灵记录。

诊断页把两条并排显示，随包那条不达标会标成 ⚠ —— 那种情况下记忆搜索是**静默**
失效的，不会有任何报错。

## 待设备验证

这个 spike 用的是 `sqlite-jdbc`（自带 SQLite，开了 FTS5），**不能代表 Android**。
以下必须在真机上再确认一次：

- [x] ~~系统 SQLite 是否够用~~ —— 实测 3.44.5 无 FTS5，必须 bundle（2026-08-24）
- [x] ~~随包 SQLite 的完整链路在设备上全绿~~ —— 3.46.0，四项全过（2026-08-24）
- [ ] `unicode61` 分词器在 Android 的 ICU 环境下行为一致（尤其日文假名、韩文）
- [ ] WAL 模式在 Android 的存储路径下可用（内存库跳过了这项）
- [ ] `mmap_size = 30MB` 在低端设备上的实际表现（可能要下调）
- [ ] 从桌面导出的角色卡里的 `facts.db` 能被直接打开（版本兼容性）
