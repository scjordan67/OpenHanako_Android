package com.hanaagent.core.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest

/**
 * 会话存储 —— 上游 `lib/session-jsonl.ts` 的移植。
 *
 * 会话是一棵**树**而不是一条链：用户可以从任意一条消息处分叉，重新问一遍。
 * 文件形态仍是追加式 JSONL（一行一条 entry），树结构靠每条 entry 的 `parentId`
 * 表达。所以"当前对话内容"不是"文件里的所有行"，而是**从某个叶子回溯到根的那条路径**。
 *
 * 这个类只负责：把 JSONL 读成树、挑出当前分支、投影成消息列表。写入是纯追加。
 */
object SessionJsonl {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** 一条会话条目。`type` 为 `"message"` 的才是对话内容，其余是元数据。 */
    data class Entry(
        val id: String,
        val parentId: String?,
        val type: String?,
        val timestamp: String?,
        val role: String?,
        val content: JsonElement?,
        /** 原始 JSON，写回时保真。 */
        val raw: JsonObject,
    )

    /** 投影出来的一条消息。 */
    data class Message(
        val role: String,
        val content: JsonElement?,
        val timestamp: String?,
        val entryId: String,
        val lineageIndex: Int,
    )

    /**
     * 上次选定的分支头。
     *
     * 刻意区分「没有这条记录」和「记录里 leafId 为 null」：前者是旧会话（从没显式
     * 选过分支），后者是显式选择了"在第一条 entry 之前"。两者行为不同。
     */
    data class BranchHead(
        val leafId: String?,
        val observedTailLeafId: String?,
    )

    /** 分支头是怎么定下来的 —— 出问题时能直接看出走了哪条路径。 */
    enum class HeadResolution {
        /** 没有持久化的分支头，取文件物理末尾（旧会话）。 */
        LEGACY_TAIL,

        /** 用持久化记录里的叶子。 */
        PERSISTED_HEAD,

        /**
         * 文件末尾变了，且新末尾是记录叶子的后代 —— 说明是正常追加，
         * 把头前移到新末尾，而不是把新消息当成"另一条分支"丢掉。
         */
        APPEND_RECOVERY,
    }

    data class Projection(
        val messages: List<Message>,
        val lastTimestamp: String?,
        val selectedLeafId: String?,
        val physicalTailLeafId: String?,
        val headResolution: HeadResolution,
        val legacySyntheticIds: Boolean,
        val recommendedHead: BranchHead,
        val lineage: List<LineageNode>,
        val lineageHash: String,
        val rootLineageHash: String,
        val prefixHashes: Map<String, String>,
    )

    data class LineageNode(
        val id: String,
        val parentId: String?,
        val type: String?,
        val lineageHash: String,
    )

    class BranchError(val code: String, message: String) : Exception(message)

    // ── 读写 ─────────────────────────────────────────────────

    fun parseEntries(text: String): List<Entry> =
        text.lineSequence()
            .filter { it.isNotBlank() }
            .mapIndexed { index, line ->
                val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrElse {
                    throw BranchError(
                        "session_branch_invalid_json",
                        "会话 JSONL 第 ${index + 1} 行不是合法 JSON：${it.message}",
                    )
                }
                toEntry(obj)
            }
            .toList()

    fun readFile(file: File): List<Entry> =
        if (file.exists()) parseEntries(file.readText()) else emptyList()

    /** 追加一条 entry。JSONL 是追加式的，任何"修改"都是新增一条以旧条目为父的记录。 */
    fun append(file: File, entry: JsonObject) {
        file.parentFile?.mkdirs()
        file.appendText(json.encodeToString(JsonObject.serializer(), entry) + "\n")
    }

    private fun toEntry(obj: JsonObject): Entry {
        val message = obj["message"] as? JsonObject
        return Entry(
            id = obj["id"]?.stringOrNull().orEmpty(),
            parentId = obj["parentId"]?.stringOrNull(),
            type = obj["type"]?.stringOrNull(),
            timestamp = obj["timestamp"]?.stringOrNull(),
            role = message?.get("role")?.stringOrNull(),
            content = message?.get("content"),
            raw = obj,
        )
    }

    private fun JsonElement.stringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    // ── 校验与索引 ───────────────────────────────────────────

    private data class Index(
        val entries: List<Entry>,
        val byId: Map<String, Entry>,
        val legacySyntheticIds: Boolean,
    )

    /**
     * 校验并建立 id 索引。
     *
     * 四类结构性错误直接抛：id 缺失、id 重复、父节点不存在、存在环。会话文件损坏时
     * 宁可明确报错，也不要静默读出一条错的分支 —— 后者会让用户看到一段不属于这个
     * 会话的对话，而且完全没有提示。
     *
     * 另有一条兼容路径：早期版本的 entry 没有 id。如果**全部**条目都没有 id，
     * 就按行号合成一条线性链（旧会话本来也不支持分叉）；但**部分**有部分没有，
     * 说明文件被两个版本交替写过，这种情况没法安全推断，直接报错。
     */
    private fun buildIndex(entries: List<Entry>): Index {
        var sessionEntries = entries.filter { it.type != "session" }
        val withIds = sessionEntries.filter { it.id.isNotEmpty() }
        var legacySynthetic = false

        if (sessionEntries.isNotEmpty() && withIds.isEmpty()) {
            legacySynthetic = true
            sessionEntries = sessionEntries.mapIndexed { index, entry ->
                entry.copy(
                    id = "legacy-line-${index + 1}",
                    parentId = if (index == 0) null else "legacy-line-$index",
                )
            }
        } else if (withIds.size != sessionEntries.size) {
            throw BranchError(
                "session_branch_invalid_id",
                "会话条目混用了稳定 id 与旧版无 id 格式，无法安全推断分支结构。",
            )
        }

        val byId = LinkedHashMap<String, Entry>()
        for (entry in sessionEntries) {
            if (entry.id.isEmpty()) {
                throw BranchError("session_branch_invalid_id", "会话条目缺少稳定 id。")
            }
            if (byId.containsKey(entry.id)) {
                throw BranchError("session_branch_duplicate_id", "会话条目 id 重复：${entry.id}")
            }
            byId[entry.id] = entry
        }

        for (entry in sessionEntries) {
            val parentId = entry.parentId
            if (parentId != null && !byId.containsKey(parentId)) {
                throw BranchError(
                    "session_branch_dangling_parent",
                    "会话条目 ${entry.id} 指向不存在的父节点 $parentId。",
                )
            }
        }

        // 环检测：0=未访问 1=访问中 2=已完成
        val state = HashMap<String, Int>()
        fun visit(start: Entry) {
            var current: Entry? = start
            val path = mutableListOf<Entry>()
            while (current != null) {
                when (state[current.id]) {
                    1 -> throw BranchError("session_branch_cycle", "会话谱系存在环：${current.id}")
                    2 -> break
                }
                state[current.id] = 1
                path += current
                current = current.parentId?.let { byId[it] }
            }
            for (node in path) state[node.id] = 2
        }
        for (entry in sessionEntries) visit(entry)

        return Index(sessionEntries, byId, legacySynthetic)
    }

    // ── 谱系 ─────────────────────────────────────────────────

    private fun lineageToRoot(leafId: String?, byId: Map<String, Entry>): List<Entry> {
        if (leafId == null) return emptyList()
        val reversed = mutableListOf<Entry>()
        var current = byId[leafId]
        while (current != null) {
            reversed += current
            current = current.parentId?.let { byId[it] }
        }
        return reversed.reversed()
    }

    private fun isDescendantOf(candidate: String?, ancestor: String?, byId: Map<String, Entry>): Boolean {
        if (candidate == null) return ancestor == null
        if (ancestor == null) return true
        var current = byId[candidate]
        while (current != null) {
            if (current.id == ancestor) return true
            current = current.parentId?.let { byId[it] }
        }
        return false
    }

    /**
     * 逐节点累积的 sha256 链，用于校验"读到的这条分支还是不是当时那条"。
     *
     * 注意这是**本机**的完整性机制，不是跨设备契约：哈希输入里含任意 JSON 内容，
     * 而 JS 与 Kotlin 的 JSON 序列化在数字格式和转义上未必逐字节相同。角色卡迁移
     * 带的是人格与记忆，不带会话谱系哈希，所以不需要与桌面一致。
     */
    fun computeLineageMetadata(lineage: List<Entry>): Triple<List<LineageNode>, String, Map<String, String>> {
        val rootHash = sha256("")
        var current = rootHash
        val prefixHashes = LinkedHashMap<String, String>()
        val nodes = mutableListOf<LineageNode>()
        for (entry in lineage) {
            current = sha256(current + "\n" + normalizedEntryIdentity(entry))
            prefixHashes[entry.id] = current
            nodes += LineageNode(entry.id, entry.parentId, entry.type, current)
        }
        return Triple(nodes, current, prefixHashes)
    }

    private fun normalizedEntryIdentity(entry: Entry): String {
        val obj = buildJsonObject {
            put("id", JsonPrimitive(entry.id))
            put("parentId", entry.parentId?.let { JsonPrimitive(it) } ?: JsonNull)
            put("type", entry.type?.let { JsonPrimitive(it) } ?: JsonNull)
            if (entry.type == "message" && entry.role != null) {
                put("timestamp", entry.timestamp?.let { JsonPrimitive(it) } ?: JsonNull)
                put(
                    "message",
                    buildJsonObject {
                        put("role", JsonPrimitive(entry.role))
                        put("content", entry.content ?: JsonNull)
                    },
                )
            }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    // ── 分支投影 ─────────────────────────────────────────────

    /**
     * 解析出"当前分支"并投影成消息列表。
     *
     * ## 分支头怎么定
     *
     * 没有持久化记录时取文件物理末尾（旧会话，本来也不分叉）。
     *
     * 有记录时要区分两种情况，这是整个类最微妙的地方：
     *
     * - **正常追加**：文件末尾变了，而且新末尾是记录叶子的**后代** —— 说明用户就是
     *   在这条分支上继续说话。把头前移到新末尾，否则刚说的话会读不出来。
     * - **切换过分支**：记录叶子不是当时观察到的末尾（用户主动切到了别的分支），
     *   而现在的物理末尾是**那个被放弃的末尾**的后代 —— 说明这些新条目长在另一条
     *   分支上，不该把用户拽回去。保持记录里的叶子。
     *
     * 分不清这两种情况的后果是：要么用户新说的话消失，要么用户被莫名其妙拉回一条
     * 已经放弃的对话。两种都是"没有报错但明显不对"。
     */
    fun projectCurrentBranch(
        entries: List<Entry>,
        branchHead: BranchHead? = null,
        since: String? = null,
    ): Projection {
        val index = buildIndex(entries)
        val physicalTailLeafId = index.entries.lastOrNull()?.id
        val persistedLeafId = branchHead?.leafId

        if (branchHead != null && persistedLeafId != null && !index.byId.containsKey(persistedLeafId)) {
            throw BranchError(
                "session_branch_head_missing",
                "持久化的分支叶子不存在：$persistedLeafId",
            )
        }

        var selectedLeafId = physicalTailLeafId
        var resolution = HeadResolution.LEGACY_TAIL

        if (branchHead != null) {
            val observedTail = branchHead.observedTailLeafId
            val physicalTailChanged = physicalTailLeafId != observedTail
            val continuesDiscardedTail = observedTail != null &&
                persistedLeafId != observedTail &&
                isDescendantOf(physicalTailLeafId, observedTail, index.byId)

            if (physicalTailChanged &&
                isDescendantOf(physicalTailLeafId, persistedLeafId, index.byId) &&
                !continuesDiscardedTail
            ) {
                selectedLeafId = physicalTailLeafId
                resolution = HeadResolution.APPEND_RECOVERY
            } else {
                selectedLeafId = persistedLeafId
                resolution = HeadResolution.PERSISTED_HEAD
            }
        }

        val rawLineage = lineageToRoot(selectedLeafId, index.byId)
        val (nodes, lineageHash, prefixHashes) = computeLineageMetadata(rawLineage)
        val lineageIndexById = rawLineage.withIndex().associate { (i, e) -> e.id to i }

        val sinceMillis = since?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
        val messages = mutableListOf<Message>()
        var lastTimestamp: String? = null

        for (entry in rawLineage) {
            if (entry.type != "message") continue
            val role = entry.role
            if (role != "user" && role != "assistant") continue
            if (sinceMillis != null) {
                val ts = entry.timestamp?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
                if (ts == null || ts <= sinceMillis) continue
            }
            messages += Message(
                role = role,
                content = entry.content,
                timestamp = entry.timestamp,
                entryId = entry.id,
                lineageIndex = lineageIndexById.getValue(entry.id),
            )
            entry.timestamp?.let { lastTimestamp = it }
        }

        return Projection(
            messages = messages,
            lastTimestamp = lastTimestamp,
            selectedLeafId = selectedLeafId,
            physicalTailLeafId = physicalTailLeafId,
            headResolution = resolution,
            legacySyntheticIds = index.legacySyntheticIds,
            recommendedHead = BranchHead(selectedLeafId, physicalTailLeafId),
            lineage = nodes,
            lineageHash = lineageHash,
            rootLineageHash = sha256(""),
            prefixHashes = prefixHashes,
        )
    }
}
