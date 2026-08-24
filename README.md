# OpenHanako Android

把 [HanaAgent](https://github.com/liliMozi/openhanako)（原 OpenHanako）搬到 Android 平板上，
保留聊天、记忆、人格与联网能力，砍掉"操作电脑"那一半。

## 边界

**保留**：对话 · 记忆（传送带 + FactStore）· 三层人格与 MOOD 机制 · 技能注入 ·
联网搜索 · 网页抓取 · 图片输入（原生多模态）· 日记 · 外貌自述 · 活动记录

**砍掉**：文件读写 · 命令执行 · 沙盒 · 可见浏览器 · Computer Use · 插件系统 ·
Bridge 外部平台接入 · `/xing` 技能提炼

**默认人格不做任何改动。** 人格资产从上游逐字复制，sha256 锁住，改了构建就红。

## 结构

```
core/      纯 Kotlin/JVM —— 不依赖 Android SDK，任何装了 JDK 的机器都能编译和测试
android/   Compose 界面 / WebView 搜索宿主 / SQLite / WorkManager / SAF（需 Android SDK）
tools/     生成期工具：从上游导出 ground truth 与资产，不进 APK
docs/      各 spike 的结论与待设备验证清单
```

把可移植的那半边做成纯 JVM 模块是刻意的：记忆传送带、prompt 装配、分词、
搜索降级这些纯逻辑因此可以在 CI 容器里真跑真测，只有界面和平台能力需要设备。

## 构建

```bash
gradle :core:test           # 45 个测试，不需要 Android SDK
gradle :android:assembleDebug -Pandroid.enabled=true   # 需要 ANDROID_SDK_ROOT
```

## Stage 0 已完成

| 交付 | 状态 |
|---|---|
| Gradle 骨架（`:core` 纯 JVM + `:android` 可选） | ✅ |
| 人格三层 26 份资产逐字移植 + sha256 护栏 | ✅ |
| Spike A：FTS5 schema 与检索契约 | ✅ 见 `docs/spike-a-fts5.md` |
| Spike B：中文分词跨设备一致性 | ✅ 召回 91.3%，报告 `build/reports/spike-b-tokenizer.md` |
| Spike C：逻辑日与补偿式调度 | ✅ |
| Spike D：搜索 URL / 降级 / 限流 / 提取脚本 | ✅ 见 `docs/spike-d-search.md` |

### 几个必须记住的结论

**Android 不能用系统自带的 SQLite。** FTS5 是编译期选项，系统版本随 OS 走，
而 FactStore 的整个检索方案建立在它之上。必须 bundle（`requery/sqlite-android`）。

**记忆调度要"欠账补偿"而不是"到点触发"。** App 可能被关几天，doze 下 WorkManager
会被推迟，04:00 那一刻大概率没有代码在跑。补账有上限（默认 3 天），超出的明确
报出被跳过天数，不静默丢弃。

**`compileDaily` 必须先于 `compileToday`。** 破坏这条约束会让昨天的记忆被静默清空
且无法恢复。已固化成常量顺序 + 断言。

**搜索的提取脚本按资产处理，不重写。** 它写死了三家搜索引擎的 DOM 选择器，
是最需要跟随上游更新的部分；存成逐字资产才能直接 diff 同步。

## 与上游的关系

上游是 Apache-2.0，本项目同样遵循。上游 `close-prs.yml` 会自动关闭非 owner 的 PR，
因此这是一个独立项目，不回上游。人格模板与 prompt 是 markdown，可定期 diff 同步。

重新生成从上游导出的资产（需要上游仓库在本地）：

```bash
node tools/tokenizer-truth/generate.mjs      > core/src/test/resources/tokenizer-truth.json
node tools/factstore-truth/generate.mjs      > core/src/test/resources/factstore-truth.json
node tools/search-extractors/generate.mjs <上游仓库路径>
```
