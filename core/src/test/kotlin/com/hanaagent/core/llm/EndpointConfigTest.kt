package com.hanaagent.core.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 端点地址归一。
 *
 * 这里每一条都对应一种真实的手滑。拼错的结果是 404，而界面只会说"接口地址或模型名
 * 不存在" —— 用户没法从那句话里看出自己多了或少了一个 /v1。
 */
class EndpointConfigTest {

    private fun full(raw: String) = EndpointConfig.resolve(raw)?.fullUrl

    @Test
    fun `Anthropic 官方地址`() {
        val resolved = EndpointConfig.resolve("https://api.anthropic.com")!!
        assertEquals(ChatPayload.Api.ANTHROPIC_MESSAGES, resolved.api)
        assertEquals("https://api.anthropic.com", resolved.baseUrl)
        assertEquals("https://api.anthropic.com/v1/messages", resolved.fullUrl)
    }

    @Test
    fun `OpenAI 兼容端点自动补上版本段`() {
        // path() 给的是 /chat/completions，baseUrl 必须自带 /v1，
        // 否则拼出来是 https://api.openai.com/chat/completions —— 404
        val resolved = EndpointConfig.resolve("https://api.openai.com")!!
        assertEquals(ChatPayload.Api.OPENAI_COMPLETIONS, resolved.api)
        assertEquals("https://api.openai.com/v1", resolved.baseUrl)
        assertEquals("https://api.openai.com/v1/chat/completions", resolved.fullUrl)
    }

    @Test
    fun `已经带了版本段就不重复加`() {
        assertEquals("https://api.deepseek.com/v1/chat/completions", full("https://api.deepseek.com/v1"))
        // 非 v1 的版本段也认
        assertEquals("https://x.example.com/v2/chat/completions", full("https://x.example.com/v2"))
    }

    @Test
    fun `把文档里那行完整地址整个粘进来也认`() {
        // 最常见的一种：用户从文档里复制了完整的请求地址
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            full("https://api.openai.com/v1/chat/completions"),
        )
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            full("https://api.anthropic.com/v1/messages"),
        )
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            full("https://api.deepseek.com/chat/completions"),
        )
    }

    @Test
    fun `Anthropic 地址里多写的 v1 被剥掉 —— 否则会拼成两个 v1`() {
        // https://api.anthropic.com/v1 + /v1/messages = .../v1/v1/messages
        assertEquals("https://api.anthropic.com/v1/messages", full("https://api.anthropic.com/v1"))
    }

    @Test
    fun `漏写协议时补 https`() {
        assertEquals("https://api.openai.com/v1/chat/completions", full("api.openai.com"))
        assertEquals("https://api.anthropic.com/v1/messages", full("api.anthropic.com"))
    }

    @Test
    fun `多余的空白与尾斜杠被清掉`() {
        assertEquals("https://api.anthropic.com/v1/messages", full("  https://api.anthropic.com///  "))
        assertEquals("https://api.openai.com/v1/chat/completions", full("https://api.openai.com/"))
    }

    @Test
    fun `自建或代理地址按 OpenAI 兼容处理`() {
        // 绝大多数第三方与自建网关都是 OpenAI 兼容形态
        val resolved = EndpointConfig.resolve("https://my-proxy.internal:8080")!!
        assertEquals(ChatPayload.Api.OPENAI_COMPLETIONS, resolved.api)
        assertEquals("https://my-proxy.internal:8080/v1/chat/completions", resolved.fullUrl)
    }

    @Test
    fun `带端口号时主机名判定不受影响`() {
        val resolved = EndpointConfig.resolve("https://api.anthropic.com:443")!!
        assertEquals(ChatPayload.Api.ANTHROPIC_MESSAGES, resolved.api, "端口号不该影响主机名判定")
    }

    @Test
    fun `显式指定形态时不再按主机名猜`() {
        // 有人把 Anthropic 兼容层架在自己的域名上，或者反过来
        val resolved = EndpointConfig.resolve(
            "https://my-gateway.example.com",
            apiOverride = ChatPayload.Api.ANTHROPIC_MESSAGES,
        )!!
        assertEquals(ChatPayload.Api.ANTHROPIC_MESSAGES, resolved.api)
        // 走 Anthropic 形态就不该被补 /v1
        assertEquals("https://my-gateway.example.com/v1/messages", resolved.fullUrl)
    }

    @Test
    fun `空白或不成地址时返回 null`() {
        assertNull(EndpointConfig.resolve(""))
        assertNull(EndpointConfig.resolve("   "))
        assertNull(EndpointConfig.resolve("https://"))
        assertNull(EndpointConfig.resolve("/v1/messages"))
    }

    @Test
    fun `校验缺什么说什么`() {
        assertEquals("还没填接口地址", EndpointConfig.validate("", "k", "m"))
        assertEquals("还没填 API key", EndpointConfig.validate("api.openai.com", "", "m"))
        assertEquals("还没填模型名", EndpointConfig.validate("api.openai.com", "k", ""))
        assertNull(EndpointConfig.validate("api.openai.com", "k", "gpt-4o"))
    }

    @Test
    fun `整理结果能直接喂给 ChatTurn`() {
        val resolved = EndpointConfig.resolve("api.anthropic.com")!!
        val endpoint = ChatTurn.Endpoint(
            api = resolved.api,
            baseUrl = resolved.baseUrl,
            model = "claude-test",
            apiKey = "sk-test",
        )
        // ChatTurn 内部拼的地址应当与这里显示给用户的一致
        assertEquals(
            resolved.fullUrl,
            endpoint.baseUrl.trimEnd('/') + ChatPayload.path(endpoint.api),
            "显示给用户的地址与实际请求的地址不一致，排查问题时会互相误导",
        )
        assertNotNull(ChatPayload.headers(resolved.api, "sk-test")["x-api-key"])
    }
}
