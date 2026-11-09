package cn.embeddedquiz

import org.junit.Assert.*
import org.junit.Test

/** The editor's block model (PLAN-1.7.5): only the blocks the user changed are rewritten. */
class QuickNoteBlocksTest {
    private fun resource(name: String) =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream("quicknotes/$name")).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun edit(markdown: String, match: (QuickNoteBlock) -> Boolean, change: (QuickNoteBlock) -> QuickNoteBlock): String {
        val blocks = QuickNoteBlocks.parse(markdown)
        assertEquals(1, blocks.count(match))
        return QuickNoteBlocks.compose(markdown, blocks.map { if (match(it)) change(it).copy(edited = true) else it })
    }

    @Test
    fun untouchedDocumentsComeBackByteForByte() {
        val syntax = resource("syntax.md")
        listOf(
                syntax,
                resource("linux.md"),
                syntax.replace("\n", "\r\n"),
                syntax.trimEnd('\n'),
                "",
                "只有一行",
            )
            .forEach { md -> assertEquals(md, QuickNoteBlocks.compose(md, QuickNoteBlocks.parse(md))) }
    }

    @Test
    fun complexMarkdownStaysRawAndCommonBlocksAreEditable() {
        val blocks = QuickNoteBlocks.parse(resource("syntax.md"))
        val raw = blocks.filter { it.kind == QuickNoteBlockKind.RAW }.map { it.label }
        assertTrue(raw.containsAll(listOf("引用", "表格", "分隔线", "代码", "图片", "列表")))
        val code = blocks.single { it.kind == QuickNoteBlockKind.CODE }
        assertEquals("c", code.language)
        assertTrue(code.text.contains("        return 42;"))
        assertEquals(listOf("有序第一项", "有序第二项"), blocks.filter { it.kind == QuickNoteBlockKind.ORDERED }.map { it.text })
        assertEquals(listOf("语法验收", "重复标题", "重复标题"), blocks.filter { it.kind == QuickNoteBlockKind.HEADING }.map { it.text })
    }

    @Test
    fun editingOneParagraphChangesOnlyThatParagraph() {
        val original = resource("syntax.md")
        val result = edit(original, { it.kind == QuickNoteBlockKind.PARAGRAPH && it.text == "第一段。" }) { it.copy(text = "第一段（已改）。") }
        assertEquals(original.replace("第一段。", "第一段（已改）。"), result)
        // Also with Windows line endings: the edited line keeps them.
        val crlf = original.replace("\n", "\r\n")
        assertEquals(crlf.replace("第一段。", "第一段（已改）。"), edit(crlf, { it.text == "第一段。" }) { it.copy(text = "第一段（已改）。") })
    }

    @Test
    fun headingsListItemsToDosAndCodeAreRewrittenInPlace() {
        val md = "# 标题\n\n- 甲\n- [ ] 买笔\n- [x] 带鼠标\n\n1) 一\n\n```\nold\n```\n"
        assertEquals(md.replace("# 标题", "### 新标题"), edit(md, { it.kind == QuickNoteBlockKind.HEADING }) { it.copy(text = "新标题", level = 3) })
        assertEquals(md.replace("- 甲", "- 乙"), edit(md, { it.kind == QuickNoteBlockKind.BULLET }) { it.copy(text = "乙") })
        assertEquals(md.replace("- [ ] 买笔", "- [x] 买笔"), edit(md, { it.kind == QuickNoteBlockKind.TODO && !it.checked }) { it.copy(checked = true) })
        assertEquals(md.replace("1) 一", "1) 二"), edit(md, { it.kind == QuickNoteBlockKind.ORDERED }) { it.copy(text = "二") })
        // Code that contains a fence gets a longer one, so the block stays closed.
        assertEquals(
            md.replace("```\nold\n```", "````kotlin\nval s = \"```\"\n````"),
            edit(md, { it.kind == QuickNoteBlockKind.CODE }) { it.copy(text = "val s = \"```\"", language = "kotlin") },
        )
    }

    @Test
    fun newBlocksAreSetApartAndDeletedOnesLeaveNoGap() {
        val md = "甲\n\n乙\n\n丙\n"
        val blocks = QuickNoteBlocks.parse(md)
        val added = blocks.toMutableList().apply { add(1, QuickNoteBlock(99, QuickNoteBlockKind.PARAGRAPH, "新段")) }
        assertEquals("甲\n\n新段\n\n乙\n\n丙\n", QuickNoteBlocks.compose(md, added))
        val dropped = blocks.map { if (it.text == "乙") it.copy(deleted = true) else it }
        assertEquals("甲\n\n丙\n", QuickNoteBlocks.compose(md, dropped))
        // A new item right after a list item joins the list without a blank line.
        val list = "- 一\n- 二\n"
        val items = QuickNoteBlocks.parse(list)
        assertEquals("- 一\n- 二\n- 三\n", QuickNoteBlocks.compose(list, items + QuickNoteBlock(99, QuickNoteBlockKind.BULLET, "三")))
        // Empty new blocks add nothing.
        assertEquals(md, QuickNoteBlocks.compose(md, blocks + QuickNoteBlock(98, QuickNoteBlockKind.PARAGRAPH, "")))
    }

    @Test
    fun aNewNoteIsWrittenFromItsBlocks() {
        val blocks =
            listOf(
                QuickNoteBlock(1, QuickNoteBlockKind.PARAGRAPH, "中断上半部要快。"),
                QuickNoteBlock(2, QuickNoteBlockKind.HEADING, "要点", level = 2),
                QuickNoteBlock(3, QuickNoteBlockKind.TODO, "看 tasklet"),
                QuickNoteBlock(4, QuickNoteBlockKind.CODE, "irq_handler();", language = "c"),
            )
        assertEquals("中断上半部要快。\n\n## 要点\n\n- [ ] 看 tasklet\n\n```c\nirq_handler();\n```\n", QuickNoteBlocks.compose("", blocks))
    }

    @Test
    fun theReadingPageMarksEveryBlockWithItsFirstSourceLine() {
        val md = resource("syntax.md")
        val html = QuickNoteMarkdown().render(md, "doc", "syntax.md", setOf("assets/示例图.png")).htmlBody
        // Every block the editor knows can be found from a tap on the page.
        QuickNoteBlocks.parse(md).filter { it.label != "HTML" }.forEach { b ->
            assertTrue("${b.kind} ${b.label} at ${b.lines}", html.contains("data-qn-line=\"${b.lines!!.first}\""))
        }
        // The page script reports taps on text only: links, buttons and selections keep their behavior.
        val page = quickNoteHtml(html, QuickNoteColors(0, 0, 0, 0, 0, 0, 0, 0, false), "n")
        assertTrue(page.contains("quicknote-edit:"))
        assertTrue(page.contains("closest('a,button,input,textarea,select')"))
        assertTrue(page.contains("sel.isCollapsed"))
        assertEquals(12, quickNoteEditLine("12"))
        assertNull(quickNoteEditLine("-1"))
        assertNull(quickNoteEditLine("x"))
    }
}
