# Spike D — 搜索链路

**结论：三档全部可移植。浏览器档的提取脚本逐字可用，是这次移植里性价比最高的一块。**

## 三档的可移植性

| 档位 | provider | 移植方式 | 风险 |
|---|---|---|---|
| API（需 key） | anysearch / tavily / brave / serper | Ktor 直译 | 无 |
| 免费 API | anysearch_free | Ktor 直译 | 无 |
| 浏览器 | bing / google / duckduckgo | WebView + **逐字复用上游提取脚本** | 见下 |

`auto` 的降级阶梯：已配 key 的 API 档 → 免费 API → bing → google → duckduckgo。
除了报错，**空结果和低质量结果也会继续往下走**——这是它体验好的原因之一，已移植。

## 浏览器档：Electron → Android 的逐项映射

桌面端用的是一次性隐藏视图（`desktop/main.cjs` 的 `browserSearch` 分支），
刻意不注册进 `_browserViews`、永不挂到可见窗口，和用户看得见的浏览器是两回事。

| Electron | Android |
|---|---|
| `new WebContentsView({sandbox:true, nodeIntegration:false})` | 不 attach 到布局的 `WebView` |
| `session.fromPartition("hana-search")` | 独立实例，用完 `clearCookies()` + `destroy()` |
| `setUserAgent(DESKTOP_UA)` | `WebSettings.setUserAgentString(...)` |
| `loadURL(url, {extraHeaders})` | `loadUrl(url, Map<String,String>)` |
| `waitForBrowserState("stable")` | `onPageFinished` + 稳定性轮询 |
| `executeJavaScript(script)` | `evaluateJavascript(script, callback)` |
| `setWindowOpenHandler(deny)` | `WebChromeClient.onCreateWindow → false` |
| `setAudioMuted(true)` | `WebSettings` 关自动播放 |
| `webContents.close()`（finally） | `webView.destroy()`（finally） |

## 提取脚本按资产处理，不重写

`assets/search/<provider>.js` 是用 `tools/search-extractors/generate.mjs` 从上游
`buildBrowserSearchExtractionScript()` **逐字导出**的，只把 `maxResults` 换成占位符。

理由：这段脚本写死了三家的 DOM 选择器、CAPTCHA 信号（`/sorry/`、unusual traffic、
verify you are human…）和多语言"无结果"文案（`没有与此相关的结果`、
`did not match any documents`、`no results found`…）。搜索引擎一改版它就失效，
是整条链路里最需要跟随上游更新的部分。存成资产就能直接 diff 同步，不用逐条对照选择器。

`BrowserSearchTest` 用 sha256 锁住这三个文件，并断言脚本自包含（不含 `require(`、
`module.exports`、`process.`、`electron`）——WebView 里这些都不存在。

## 两个容易踩的坑

**必须伪装桌面 UA。** WebView 默认报 Android，搜索引擎会给移动版页面，
而选择器是照着桌面版写的，会全部落空。已写成断言。

**限流表原样抄，不要调。** 这些数字是上游拿真实封禁换来的：

| provider | 最小间隔 | 抖动 | 限流后退避 | 最长冷却 |
|---|---|---|---|---|
| google_browser | 6s | 8s | 30s | 10min |
| bing / duckduckgo | 3s | 4s | 10s | 5min |
| tavily | 650ms | 350ms | 2s | 5min |

调小会很快撞上验证页，而那时提取脚本只返回 `blocked`，用户看到的是"搜索用不了"。

## Android 特有的约束

**WebView 必须在主线程创建和调用**，且需要 Context。搜索若发生在后台
（记忆编译顺带的调研），要 post 到 main looper。

**App 被回收时正在飞的搜索会断。** 所以 `web_search` 在 Android 上需要一层
"进程存活期内有效"的语义：超时或被杀时如实返回失败，让模型知道，
而不是静默返回空结果——后者会让模型以为"搜过了，没有"。

## 待设备验证

- [ ] 隐藏 WebView 在无 Activity（仅 Application context）下能否正常 `evaluateJavascript`
- [ ] bing / duckduckgo 的提取脚本在真机上能拿到结果（google 反爬最凶，放最后验）
- [ ] Cookie 隔离是否足够（Android 的 CookieManager 是进程级单例，不像 Electron 有 partition）
- [ ] 后台执行时 WebView 的生命周期表现
- [ ] `Accept-Language` 头能否通过 `loadUrl` 的 additionalHttpHeaders 稳定生效（部分 WebView 版本会丢重定向后的头）
