package cn.embeddedquiz

import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class QuickNoteReadToolsTest {
    private fun tools(text: String) = QuickNoteReadTools(text, QuickNoteMarkdown().aiDocument(text, rawMarkdown = true))
    private fun call(name: String, args: JSONObject = JSONObject(), id: String = "call_1") = QuickNoteToolCall(id, name, args.toString())
    private fun read(id: String, offset: Int = 0, limit: Int = 600) = call("read_section",
        JSONObject().put("id", id).put("offset", offset).put("limit", limit))

    @Test fun exactlyThreeReadOnlyToolsAndHeadingTree() = runBlocking {
        assertEquals(setOf("list_sections", "read_section", "search_note"), QuickNoteReadTools.names)
        val source = tools("# 主节\n\n第一段\n\n## 子节\n\n第二段")
        val result = source.invoke(call("list_sections"))
        val sections = result.getJSONArray("sections")
        assertEquals(2, sections.length())
        assertEquals("主节", sections.getJSONObject(1).getString("parentId"))
        assertEquals(1, sections.getJSONObject(1).getInt("paragraphs"))
        assertTrue(runCatching { source.invoke(call("write_note")) }.isFailure)
        assertTrue(runCatching { source.invoke(call("read_section", JSONObject().put("id", "../other"))) }.isFailure)
    }

    @Test fun longDocumentSearchScansOutsideOldExcerptsAndRawLinkDestinations() = runBlocking {
        val markdown = "# 长文\n\n" + (1..100).joinToString("\n\n") { "普通段落$it" } +
            "\n\n唯一秘密句子：青色河马。\n\n[链接](https://example.test/raw-token-829)"
        val old = selectQuickNoteAiContext(QuickNoteMarkdown().aiDocument(markdown), "长文", "这一节")
        assertFalse(old.excerpts.any { "青色河马" in it.text })
        val source = tools(markdown)
        for (query in listOf("青色河马", "raw-token-829")) {
            val result = source.invoke(call("search_note", JSONObject().put("query", query)))
            assertEquals(1, result.getInt("matches"))
            val excerpt = result.getJSONArray("excerpts").getJSONObject(0)
            assertTrue(excerpt.getString("text").contains(query))
            assertEquals("长文", excerpt.getString("headingTitle"))
            assertTrue(excerpt.getInt("firstParagraph") > 6)
        }
        val miss = source.invoke(call("search_note", JSONObject().put("query", "蓝色河马")))
        assertEquals(0, miss.getInt("matches"))
        assertEquals(0, miss.getJSONArray("excerpts").length())
        assertEquals(QuickNoteReadTools.NO_EVIDENCE, miss.getString("note"))
    }

    @Test fun paginationReadsLongParagraphCompletelyWithoutSplittingEmoji() = runBlocking {
        val body = "开始😀".repeat(450)
        val source = tools("# 一节\n\n$body")
        var offset = 0
        val received = StringBuilder()
        do {
            val result = source.invoke(read("一节", offset, 599))
            val excerpt = result.getJSONArray("excerpts").optJSONObject(0)
            excerpt?.let { received.append(it.getString("text")); AiJson.utf8(it.getString("text")) }
            val next = result.getInt("nextOffset")
            assertTrue(next > offset)
            offset = next
        } while (!result.getBoolean("done"))
        assertEquals(body, received.toString().trim())
        assertTrue(runCatching { source.invoke(read("一节", limit = 601)) }.isFailure)
    }

    @Test fun searchCountsMatchesAcrossWholeSourceEvenAfterResultCap() = runBlocking {
        val result = tools("# 文档\n\n" + "命中\n\n".repeat(1000)).invoke(call("search_note", JSONObject().put("query", "命中")))
        assertEquals(1000, result.getInt("matches"))
        assertEquals(2, result.getJSONArray("excerpts").length())
        assertTrue(result.getBoolean("truncated"))
        assertTrue(AiJson.encodedBytes(result) <= QuickNoteReadTools.RESULT_BYTES)
    }

    @Test fun newEnvelopeChecksPayloadAndExecutionHashAndNeverContainsBodyInitially() = runBlocking {
        val summary = QuickNoteSummary(UUID.randomUUID().toString(), "测试", "test.md", "a".repeat(64), 1)
        val source = tools("# 一节\n\n绝不在首跳上传的正文")
        val owner = QuestionOwner(summary.id, summary.contentHash, AiOwnerKind.QUICK_NOTE)
        val snapshot = AiSnapshotCodec.documentAgentRequest(AiSnapshotCodec.document(summary), DeepSeekClient.FLASH,
            "只按笔记回答", emptyList(), source.initialContext(), "一节")
        assertEquals(5, JSONObject(snapshot).getInt("version"))
        assertFalse(AiSnapshotCodec.payload(snapshot).contains("绝不在首跳上传的正文"))
        AiSnapshotCodec.validateRequest(snapshot, owner, DeepSeekClient.FLASH, AiRequestType.FOLLOW_UP.name)
        val execution = QuickNoteAgentProtocol.empty().put("dispatches", 1)
        execution.getJSONArray("usage").put(QuickNoteAgentProtocol.usageJson(AiUsageEvidence(10, 2, 1, UsageState.KNOWN)))
        val tool = read("一节")
        val normalized = QuickNoteAgentProtocol.readCall(tool.json())
        execution.getJSONArray("steps").put(JSONObject().put("call", normalized.json()).put("result", source.invoke(normalized)).put("hop", 1))
        val after = QuickNoteAgentProtocol.withExecution(snapshot, execution)
        AiSnapshotCodec.validateRequest(after, owner, DeepSeekClient.FLASH, AiRequestType.FOLLOW_UP.name)
        assertEquals(QuickNoteAgentProtocol.digest(snapshot), QuickNoteAgentProtocol.digest(after))
        assertEquals(snapshot, QuickNoteAgentProtocol.initial(after))
        val hop = JSONObject(QuickNoteAgentProtocol.continuation(snapshot, execution, false))
        assertEquals("tool", hop.getJSONArray("messages").getJSONObject(3).getString("role"))
        val tampered = JSONObject(after).put("executionHash", "0".repeat(64))
        assertTrue(runCatching { AiSnapshotCodec.validateRequest(AiJson.canonical(tampered), owner, DeepSeekClient.FLASH, "FOLLOW_UP") }.isFailure)
        val badPayload = JSONObject(snapshot).put("payload", AiSnapshotCodec.payload(snapshot) + " ")
        assertTrue(runCatching { AiSnapshotCodec.validateRequest(AiJson.canonical(badPayload), owner, DeepSeekClient.FLASH, "FOLLOW_UP") }.isFailure)
    }

    @Test fun emptyToolFieldsAreOrdinaryRepliesButMalformedCallsFailClosed() {
        fun decode(calls: Any, allow: Boolean = false, finish: String = "stop", key: String = ""): AiChatResult {
            val message = JSONObject().put("role", "assistant").put("content", "普通文本").put("tool_calls", calls)
            val choice = JSONObject().put("index", 0).put("finish_reason", finish).put("message", message)
            return AiChatTransport.decode(JSONObject().put("choices", JSONArray().put(choice)).toString(), key, allowTools = allow)
        }
        for (empty in listOf(JSONObject.NULL, JSONArray())) {
            for (allow in listOf(false, true)) {
                val result = decode(empty, allow)
                assertTrue(result.complete)
                assertEquals("普通文本", result.text)
                assertTrue(result.toolCalls.isEmpty())
            }
            assertEquals("INVALID_RESPONSE", decode(empty, true, "tool_calls").error)
        }
        for (malformed in listOf(JSONObject(), "[]", JSONArray().put(JSONObject()))) {
            val result = decode(malformed, true)
            assertEquals("INVALID_RESPONSE", result.error)
            assertEquals("", result.text)
        }
        val call = JSONObject().put("id", "valid-call").put("type", "function").put("extra", 1.25)
            .put("function", JSONObject().put("name", "search_note")
                .put("arguments", JSONObject().put("query", "关键词").put("unused", "ignored")))
        val response = decode(JSONArray().put(call), true)
        assertTrue(response.complete)
        assertEquals("", response.text)
        assertEquals("关键词", JSONObject(response.toolCalls.single().arguments).getString("query"))
        assertEquals(1, JSONObject(response.toolCalls.single().arguments).length())
        assertEquals("INVALID_RESPONSE", decode(JSONArray().put(call), false).error)
        assertEquals("INVALID_RESPONSE", decode(JSONArray().put(call).put(call), true).error)
        val escapedKey = "fixture\\part"
        call.getJSONObject("function").put("arguments", JSONObject().put("query", escapedKey))
        assertEquals("INVALID_RESPONSE", decode(JSONArray().put(call), true, key = escapedKey).error)
    }

    @Test fun planningOnlyRequiresEveryClauseToBeProcedural() {
        for (plan in listOf("我先去看一下原文。", "好的，我会先查阅相关段落，再给出回答。",
            "我会先查看文档的全部内容，然后再找到相关内容回复。", "I'll first check the document, then provide the answer.")) {
            assertTrue(plan, QuickNoteAgentProtocol.planningOnly(plan))
        }
        for (answer in listOf("我查到 DMA 用于传输数据。", "我先查了目录，DMA 用于传输数据。",
            "我先查阅目录，原文说 DMA 用于传输数据。", "先配置 DMA 通道，然后启用中断。", "笔记没有介绍 TCP。")) {
            assertFalse(answer, QuickNoteAgentProtocol.planningOnly(answer))
        }
    }

    @Test fun olderAgentPromptsAreValidatedUsingStoredBytes() {
        val doc = QuickNoteSummary(UUID.randomUUID().toString(), "测试", "test.md", "a".repeat(64), 1)
        val owner = QuestionOwner(doc.id, doc.contentHash, AiOwnerKind.QUICK_NOTE)
        val current = AiSnapshotCodec.documentAgentRequest(AiSnapshotCodec.document(doc), DeepSeekClient.FLASH,
            "查原文", emptyList(), tools("# 标题\n\n原文").initialContext(), null)
        val frozen = JSONObject(current).put("systemVersion", "1").put("system", "旧版速记提示")
            .put("template", "旧版查阅指令")
        val body = JSONObject(frozen.getString("payload"))
        body.getJSONArray("messages").getJSONObject(0).put("content", frozen.getString("system"))
        body.getJSONArray("messages").getJSONObject(1).put("content",
            frozen.getString("template") + "\n" + AiJson.canonical(frozen.getJSONObject("data")))
        val payload = AiJson.canonical(body)
        val old = AiJson.canonical(frozen.put("payload", payload).put("payloadHash", DeepSeekClient.hash(payload)))
        AiSnapshotCodec.validateRequest(old, owner, DeepSeekClient.FLASH, "FOLLOW_UP")
        assertEquals(old, QuickNoteAgentProtocol.initial(old))
        assertEquals(payload, AiSnapshotCodec.payload(old))
    }

    @Test fun aggregateUsagePreservesCacheTokensAndDoesNotPriceUnknownInFlightHop() {
        val execution = QuickNoteAgentProtocol.empty().put("dispatches", 2)
        execution.getJSONArray("usage").put(QuickNoteAgentProtocol.usageJson(AiUsageEvidence(100, 20, 10, UsageState.KNOWN)))
            .put(QuickNoteAgentProtocol.usageJson(AiUsageEvidence(200, 30, 50, UsageState.KNOWN)))
        val sum = QuickNoteAgentProtocol.usage(execution, AiChatPrices.estimatePolicy(DeepSeekClient.FLASH))
        assertEquals(300L, sum.input); assertEquals(50L, sum.output); assertEquals(60L, sum.hit)
        assertEquals(UsageState.KNOWN, sum.state)
        execution.put("dispatches", 3)
        val partial = QuickNoteAgentProtocol.usage(execution, sum)
        assertEquals(300L, partial.input); assertNull(partial.output)
        assertEquals(UsageState.PARTIAL, partial.state)
    }
}
