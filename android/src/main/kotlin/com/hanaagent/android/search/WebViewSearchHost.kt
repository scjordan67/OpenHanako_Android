package com.hanaagent.android.search

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.hanaagent.core.search.BrowserSearch
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 浏览器搜索的 Android 宿主 —— 对应桌面端 `desktop/main.cjs` 的 `browserSearch` 分支。
 *
 * 桌面端用的是一次性隐藏 `WebContentsView`：不注册进可见浏览器列表、永不挂到窗口、
 * 独立 session 分区、静音、拒绝弹窗、`finally` 里关掉。这里是同一套语义的 Android 版：
 * 一个**不 attach 到任何布局**的 WebView，用完立刻销毁。
 *
 * 页内提取脚本不在这里写，来自 [BrowserSearch.extractionScript] —— 那是从上游逐字
 * 导出并用 sha256 锁住的资产。这个类只负责"把页面加载出来，把脚本喂进去，把 JSON 拿回来"。
 *
 * ## 两个 Android 特有的约束
 *
 * **WebView 必须在主线程创建和调用。** 搜索可能发生在后台（记忆编译顺带的调研），
 * 所以所有 WebView 操作都 post 到 main looper。
 *
 * **进程被回收时正在飞的搜索会断。** 调用方必须把超时/失败如实报给模型，
 * 而不是静默返回空结果 —— 后者会让模型以为"搜过了，确实没有"。
 */
class WebViewSearchHost(private val appContext: Context) {

    /** 原始结果：JSON 文本 + 最终 URL。解析交给调用方（契约在 :core）。 */
    data class RawResult(val json: String, val finalUrl: String?)

    sealed interface Outcome {
        data class Success(val raw: RawResult) : Outcome
        /** 页面加载失败、超时，或进程状态不允许 —— 必须如实上报，不能当成"没搜到"。 */
        data class Failure(val reason: String) : Outcome
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun search(
        provider: BrowserSearch.Provider,
        query: String,
        maxResults: Int = 5,
        locale: String? = null,
        navigationTimeoutMs: Long = NAVIGATION_TIMEOUT_MS,
        settleDelayMs: Long = SETTLE_DELAY_MS,
    ): Outcome {
        val url = BrowserSearch.searchUrl(provider, query, maxResults, locale)
        val loadOptions = BrowserSearch.loadOptions(locale)
        val script = BrowserSearch.extractionScript(provider, maxResults)

        val outcome = withTimeoutOrNull(navigationTimeoutMs) {
            suspendCancellableCoroutine { continuation ->
                mainHandler.post {
                    var webView: WebView? = null
                    try {
                        val view = WebView(appContext)
                        webView = view
                        view.settings.javaScriptEnabled = true
                        view.settings.domStorageEnabled = true
                        view.settings.blockNetworkImage = true      // 只要文字，别浪费流量
                        view.settings.loadsImagesAutomatically = false
                        view.settings.mediaPlaybackRequiresUserGesture = true
                        // 关键：不改 UA 的话 WebView 报的是 Android，搜索引擎会给
                        // 移动版页面，而提取脚本的选择器是照着桌面版写的，会全部落空。
                        view.settings.userAgentString = loadOptions.userAgent
                        // 拒绝任何新开窗口，与桌面端 setWindowOpenHandler(deny) 对齐
                        view.webChromeClient = object : WebChromeClient() {
                            override fun onCreateWindow(
                                v: WebView?, isDialog: Boolean,
                                isUserGesture: Boolean, resultMsg: android.os.Message?,
                            ): Boolean = false
                        }
                        view.webViewClient = ExtractOnFinish(
                            script = script,
                            settleDelayMs = settleDelayMs,
                            continuation = continuation,
                            onDone = { destroyQuietly(view) },
                        )
                        view.loadUrl(url, loadOptions.extraHeaders)
                    } catch (error: Throwable) {
                        destroyQuietly(webView)
                        if (continuation.isActive) {
                            continuation.resume(Outcome.Failure("WebView 创建或加载失败: $error"))
                        }
                    }

                    continuation.invokeOnCancellation { mainHandler.post { destroyQuietly(webView) } }
                }
            }
        }

        return outcome ?: Outcome.Failure("搜索页加载超时（${navigationTimeoutMs}ms）：$url")
    }

    /**
     * 页面加载完成后跑提取脚本。
     *
     * `onPageFinished` 之后再等一小段：搜索结果页普遍靠 JS 二次渲染，
     * 立刻抓会拿到半成品。桌面端的 `waitForBrowserState("stable")` 做的是同一件事。
     */
    private class ExtractOnFinish(
        private val script: String,
        private val settleDelayMs: Long,
        private val continuation: CancellableContinuation<Outcome>,
        private val onDone: () -> Unit,
    ) : WebViewClient() {

        private var extracted = false

        override fun onPageFinished(view: WebView, url: String?) {
            if (extracted) return
            extracted = true
            view.postDelayed({
                if (!continuation.isActive) { onDone(); return@postDelayed }
                view.evaluateJavascript(script) { json ->
                    if (continuation.isActive) {
                        continuation.resume(Outcome.Success(RawResult(unwrapJsEvaluationResult(json), url)))
                    }
                    onDone()
                }
            }, settleDelayMs)
        }

        override fun onReceivedError(
            view: WebView?,
            request: android.webkit.WebResourceRequest?,
            error: android.webkit.WebResourceError?,
        ) {
            // 只关心主文档的失败；子资源（图片、统计脚本）失败不影响提取
            if (request?.isForMainFrame != true) return
            if (continuation.isActive) {
                continuation.resume(Outcome.Failure("页面加载错误: ${error?.description}"))
            }
            onDone()
        }
    }

    private companion object {
        const val NAVIGATION_TIMEOUT_MS = 30_000L
        const val SETTLE_DELAY_MS = 1_200L

        val mainHandler = Handler(Looper.getMainLooper())

        fun destroyQuietly(webView: WebView?) {
            runCatching {
                webView?.stopLoading()
                webView?.webChromeClient = null
                webView?.destroy()
            }
        }

    }
}

/**
 * `WebView.evaluateJavascript` 的返回值剥壳。
 *
 * 它把结果按 **JS 字面量**回传：脚本返回对象时拿到的是一个 JSON 字符串，
 * 而这个字符串整体又被再包一层引号并转义。不剥掉这层，JSON 解析必然失败。
 *
 * 提成 internal 顶层函数是为了能在 JVM 单测里覆盖 —— 转义处理很容易写错，
 * 而它出错时的表现是"搜索永远返回空"，没有任何报错。
 */
internal fun unwrapJsEvaluationResult(raw: String?): String {
    val value = raw ?: return ""
    if (value == "null") return ""
    if (value.length < 2 || !value.startsWith("\"") || !value.endsWith("\"")) return value
    val body = value.substring(1, value.length - 1)
    val out = StringBuilder(body.length)
    var i = 0
    while (i < body.length) {
        val c = body[i]
        if (c != '\\' || i == body.length - 1) {
            out.append(c); i++; continue
        }
        when (val next = body[i + 1]) {
            '"' -> { out.append('"'); i += 2 }
            '\\' -> { out.append('\\'); i += 2 }
            'n' -> { out.append('\n'); i += 2 }
            'r' -> { out.append('\r'); i += 2 }
            't' -> { out.append('\t'); i += 2 }
            'b' -> { out.append('\b'); i += 2 }
            'f' -> { out.append('\u000C'); i += 2 }
            '/' -> { out.append('/'); i += 2 }
            'u' -> {
                val hex = body.getOrNull(i + 2)?.let { _ -> body.substring(i + 2, minOf(i + 6, body.length)) }
                val code = hex?.takeIf { it.length == 4 }?.toIntOrNull(16)
                if (code != null) { out.append(code.toChar()); i += 6 } else { out.append(c); i++ }
            }
            else -> { out.append(c).append(next); i += 2 }
        }
    }
    return out.toString()
}
