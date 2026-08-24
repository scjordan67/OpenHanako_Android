package com.hanaagent.android

import com.hanaagent.android.search.unwrapJsEvaluationResult
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `WebView.evaluateJavascript` 回传值的剥壳。
 *
 * 这段逻辑出错时的表现是"搜索永远返回空结果"，没有任何报错 —— 属于最难查的一类
 * 故障，所以单独覆盖。这个测试跑在 JVM 上（`testDebugUnitTest`），不需要设备。
 */
class JsEvaluationUnwrapTest {

    @Test
    fun `剥掉外层引号并还原转义`() {
        // 提取脚本返回对象时，WebView 把整个 JSON 当字符串再包一层：
        // 真实回传形如   "{\"status\":\"ok\",\"results\":[]}"
        val fromWebView = "\"{\\\"status\\\":\\\"ok\\\",\\\"results\\\":[]}\""
        assertEquals("""{"status":"ok","results":[]}""", unwrapJsEvaluationResult(fromWebView))
    }

    @Test
    fun `还原换行制表符与反斜杠`() {
        assertEquals("a\nb\tc\\d", unwrapJsEvaluationResult("\"a\\nb\\tc\\\\d\""))
    }

    @Test
    fun `还原 unicode 转义 —— 中文标题会走这条路`() {
        assertEquals("记忆", unwrapJsEvaluationResult("\"\\u8bb0\\u5fc6\""))
    }

    @Test
    fun `非字符串返回值原样给出`() {
        // 脚本抛异常或返回 undefined 时 WebView 会回 "null"
        assertEquals("", unwrapJsEvaluationResult("null"))
        assertEquals("", unwrapJsEvaluationResult(null))
        // 已经是裸 JSON 的情况（某些 WebView 实现）不应被破坏
        assertEquals("{\"a\":1}", unwrapJsEvaluationResult("{\"a\":1}"))
    }

    @Test
    fun `残缺的转义不吞字符`() {
        // 末尾孤立反斜杠不应导致越界或丢字符
        assertEquals("abc\\", unwrapJsEvaluationResult("\"abc\\\""))
    }
}
