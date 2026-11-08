package cn.embeddedquiz

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class QuickNoteAiSelectionTest {
    private val parser = QuickNoteMarkdown()
    private val document = parser.aiDocument("""
        # 课程笔记

        ## 第一章 UDP
        UDP 使用数据报。

        ```c
        sendto(fd);
        ```

        下一段是 UDP 示例的说明。

        ## 第二章 DMA
        DMA 使用描述符环。

        ## 第三章 定时器
        定时器提供周期触发。

        ## 第四章 中断处理
        中断处理应快速清除标志。
    """.trimIndent())
    private val section = document.headings.first { "UDP" in it.title }.id

    @Test fun questionScopeBeatsReadingLocationAndCoversNamedChapters() {
        val remote = selectQuickNoteAiContext(document, section, "解释中断处理")
        assertEquals(listOf("第四章 中断处理"), remote.references.map { it.headingTitle }.distinct())
        val compare = selectQuickNoteAiContext(document, section, "比较第二章与第四章")
        assertEquals(setOf("第二章 DMA", "第四章 中断处理"), compare.references.map { it.headingTitle }.toSet())
        val local = selectQuickNoteAiContext(document, section, "这段代码怎么运行？")
        assertEquals(QuickNoteAiScope.SECTION, local.coverage.scope)
        assertTrue(local.excerpts.any { "sendto(fd)" in it.text })
        assertTrue(local.references.all { it.headingId == section })
        val inferred = selectQuickNoteAiContext(document, section, "它有什么限制？",
            QuickNoteAiTurn("解释中断处理", remote.references))
        assertEquals(remote.references, inferred.references)
    }

    @Test fun wholeDocumentUsesCompleteShortTextAndRepresentativeLongSections() {
        val short = selectQuickNoteAiContext(document, section, "总结整份文档")
        assertEquals(QuickNoteAiScope.WHOLE_DOCUMENT, short.coverage.scope)
        assertFalse(short.coverage.partial)
        assertEquals(4, short.coverage.includedSections)
        assertEquals(3, short.references.first().lastParagraph)
        val long = parser.aiDocument((1..16).joinToString("\n\n") { i -> "## 章节 $i\n\n" + "远程段落 $i ".repeat(1400) })
        val sampled = selectQuickNoteAiContext(long, null, "这份笔记都讲了什么？")
        assertTrue(sampled.coverage.partial)
        assertEquals(16, sampled.coverage.totalSections)
        assertEquals(16, sampled.outline.size)
        assertTrue(sampled.coverage.includedSections in 2..6)
        assertTrue(AiJson.encodedBytes(AiJson.array(sampled.excerpts.map { it.json() })) <= QUICK_NOTE_CONTEXT_BYTES)
    }
}
