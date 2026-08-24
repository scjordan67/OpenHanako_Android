package com.hanaagent.core.persona

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 默认人格护栏。
 *
 * 用户对这个移植提的硬要求是「默认人格不要变动」。人格是 markdown 资产，
 * 而 markdown 是最容易被顺手"优化"的东西 —— 换个措辞、统一个标点、删行空行，
 * 都会悄悄改变 system prompt 里那个 Agent 是谁。所以这里用 sha256 锁死：
 * 资产变了但锁文件没跟着改，构建就红。
 *
 * 要跟随上游更新时，同时更新 `assets/persona/` 下的资产与 `persona-lock.sha256`，
 * 让 diff 出现在 review 里，而不是混进某次无关提交。
 */
class PersonaAssetsLockTest {

    private data class LockedEntry(val sha256: String, val path: String)

    private fun readLock(): List<LockedEntry> {
        val text = javaClass.getResourceAsStream("/persona-lock.sha256")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
        assertNotNull(text, "persona-lock.sha256 缺失：护栏本身不能丢")
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { line ->
                val parts = line.split(Regex("\\s+"), limit = 2)
                require(parts.size == 2) { "锁文件格式错误：$line" }
                LockedEntry(parts[0], parts[1])
            }
            .toList()
    }

    @Test
    fun `每一份人格资产都与锁文件逐字节一致`() {
        val locked = readLock()
        assertTrue(locked.isNotEmpty(), "锁文件为空")

        val drifted = locked.mapNotNull { entry ->
            val actual = PersonaAssets.sha256Of(entry.path)
            when {
                actual == null -> "${entry.path}: 资产缺失"
                actual != entry.sha256 -> "${entry.path}: 内容已改动（期望 ${entry.sha256.take(12)}…，实际 ${actual.take(12)}…）"
                else -> null
            }
        }

        assertTrue(
            drifted.isEmpty(),
            "人格资产发生了未同步到锁文件的改动：\n" + drifted.joinToString("\n"),
        )
    }

    @Test
    fun `锁文件覆盖了全部打包资产，没有漏网的`() {
        val lockedPaths = readLock().map { it.path }.toSortedSet()
        val packagedPaths = PersonaAssets.listAll().toSortedSet()
        assertEquals(
            packagedPaths,
            lockedPaths,
            "打包资产与锁文件不匹配 —— 新增资产必须同时进锁文件，否则它不受护栏保护",
        )
    }

    @Test
    fun `默认源 hanako 的三层都在，且 MOOD 协议原样保留`() {
        val yuan = PersonaAssets.read(PersonaAssets.Layer.YUAN, "hanako")
        assertNotNull(yuan, "hanako 的源模板缺失")

        // 这几处是 MOOD 机制的骨架：四个池子的名字和标签形态。
        // 它们决定了 MoodParser 能不能切出内心独白，改了就是改机制。
        for (marker in listOf("## MOOD", "<mood>", "</mood>", "Vibe", "Sparks", "Reflections", "Will")) {
            assertTrue(marker in yuan, "hanako 源模板里缺少 MOOD 机制标记：$marker")
        }

        assertNotNull(
            PersonaAssets.read(PersonaAssets.Layer.IDENTITY, "hanako"),
            "hanako 的身份模板缺失",
        )
        assertNotNull(
            PersonaAssets.read(PersonaAssets.Layer.AGENTS, "hanako"),
            "hanako 的行为守则缺失",
        )
    }

    @Test
    fun `英文 locale 走 en 目录，其余语言回落中文原件`() {
        val zh = PersonaAssets.read(PersonaAssets.Layer.YUAN, "hanako", "zh-CN")
        val en = PersonaAssets.read(PersonaAssets.Layer.YUAN, "hanako", "en-US")
        assertNotNull(zh)
        assertNotNull(en)
        assertTrue(zh != en, "中英文源模板不应是同一份")

        // 上游只维护中/英两套；日韩繁体界面用的仍是中文人格。
        for (locale in listOf("ja", "ko", "zh-TW")) {
            assertEquals(
                zh,
                PersonaAssets.read(PersonaAssets.Layer.YUAN, "hanako", locale),
                "$locale 应回落到中文人格原件",
            )
        }
    }

    @Test
    fun `内置源清单与实际打包的 yuan 资产一致`() {
        val packagedYuan = PersonaAssets.listAll()
            .filter { it.startsWith("yuan/") && !it.startsWith("yuan/en/") }
            .map { it.removePrefix("yuan/").removeSuffix(".md") }
            .toSortedSet()
        assertEquals(
            PersonaAssets.BUILT_IN_YUAN.toSortedSet(),
            packagedYuan,
            "BUILT_IN_YUAN 与打包的源模板对不上",
        )
        assertTrue(PersonaAssets.DEFAULT_YUAN in PersonaAssets.BUILT_IN_YUAN)
    }
}
