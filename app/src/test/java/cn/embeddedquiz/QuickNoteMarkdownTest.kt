package cn.embeddedquiz

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class QuickNoteMarkdownTest {
    private val renderer = QuickNoteMarkdown()
    private fun fixture(name: String) = javaClass.getResource("/quicknotes/$name.md")!!.readText(Charsets.UTF_8)

    @Test
    fun actualLinuxDocumentRendersChineseTablesCodeAndDirectoryWithoutLosingEscapes() {
        val source = fixture("linux")
        val result = renderer.render(source)
        assertEquals(7, result.headings.size)
        assertEquals("嵌入式 Linux 面试复习：启动流程、根文件系统与驱动", result.headings.first().title)
        assertEquals(5, Regex("<table>").findAll(result.htmlBody).count())
        assertEquals(1, result.codeBlocks.size)
        assertTrue(result.codeBlocks.values.single().contains("    if (copy_from_user(kbuf, buf, cnt))"))
        assertTrue(result.codeBlocks.values.single().contains("        return -EFAULT;"))
        assertTrue(result.htmlBody.contains("MINOR_START"))
        assertTrue(result.htmlBody.contains("&lt;类&gt;"))
        assertTrue(result.htmlBody.contains("<strong>一句话结论：</strong>"))
        assertTrue(result.warnings.any { it.contains("占位文字") })
        assertFalse(result.warnings.any { it.contains("数学公式") || it.contains("图片未载入") })
        result.headings.forEach { assertTrue(result.htmlBody.contains("id=\"${it.id}\"")) }
        File("build/quicknotes-preview").mkdirs()
        File("build/quicknotes-preview/linux-body.html").writeText(result.htmlBody, Charsets.UTF_8)
    }

    @Test
    fun syntaxFixtureCoversNestedListsFormattingLinksTablesImagesAndCopyData() {
        val result = renderer.render(fixture("syntax"), images = setOf("assets/示例图.png"))
        listOf("<em>", "<strong>", "<del>", "<blockquote", "<ol>", "<ul>", "<hr", "<table>", "<code>", "<pre>").forEach {
            assertTrue("missing $it", result.htmlBody.contains(it))
        }
        assertEquals(listOf("语法验收", "重复标题", "重复标题-1"), result.headings.map { it.id })
        assertTrue(result.htmlBody.contains("qn-table-scroll"))
        assertTrue(result.htmlBody.contains("qn-code-scroll"))
        assertTrue(result.htmlBody.contains("quicknote-copy:code-0"))
        assertTrue(result.codeBlocks.values.any { it.contains("        return 42;") })
        assertTrue(result.codeBlocks.values.any { it == "indented code\n    more indentation\n" })
        assertTrue(result.htmlBody.contains("<img "))
        assertTrue(result.warnings.any { it.contains("missing.png") })
        assertFalse(result.warnings.any { it.contains("未找到文档内锚点") })
        File("build/quicknotes-preview").mkdirs()
        File("build/quicknotes-preview/syntax-body.html").writeText(result.htmlBody, Charsets.UTF_8)
    }

    @Test
    fun dangerousLinksAndRawHtmlCannotBecomeExecutableContent() {
        val result = renderer.render("<script>alert(1)</script>\n\n[x](javascript:alert) ![img](https://remote.test/a.png)\n\n[mail](mailto:user@example.com)")
        assertFalse(result.htmlBody.contains("<script>"))
        assertFalse(result.htmlBody.contains("href=\"javascript:"))
        assertFalse(result.htmlBody.contains("<img "))
        assertTrue(result.htmlBody.contains("mailto:user@example.com"))
        assertTrue(result.warnings.any { it.contains("不支持的链接") })
        assertTrue(result.warnings.any { it.contains("图片未载入") })
    }

    @Test
    fun mathAndMissingAnchorsAreExplicitlyReportedInsteadOfClaimingSupport() {
        val result = renderer.render("# 章节\n\n公式：${'$'}x^2${'$'}\n\n[不存在](#missing)")
        assertTrue(result.warnings.any { it.contains("不排版数学公式") })
        assertTrue(result.htmlBody.contains("${'$'}x^2${'$'}"))
        assertTrue(result.warnings.any { it.contains("未找到文档内锚点") })
        assertFalse(renderer.render("```sh\necho ${'$'}x\n```\n").warnings.any { it.contains("数学公式") })
    }

    @Test
    fun chineseResourcePathsAndPlusSignsDecodeWithoutTraversal() {
        assertEquals("assets/图+片.png", QuickNotePaths.resolve("docs/main.md", "../assets/%E5%9B%BE+%E7%89%87.png"))
        assertTrue(runCatching { QuickNotePaths.resolve("main.md", "../outside.png") }.isFailure)
        assertTrue(runCatching { QuickNotePaths.normalize("C:/private.png") }.isFailure)
        assertTrue(runCatching { QuickNotePaths.resolve("main.md", "file:///private.png") }.isFailure)
        assertEquals("# 中文", QuickNotePaths.utf8(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "# 中文".toByteArray()))
    }

    @Test
    fun veryLongHeadingsStillProduceStablePersistableUniqueAnchors() {
        val title = "中".repeat(3000)
        val result = renderer.render("# $title\n\n# $title")
        assertEquals(2, result.headings.size)
        assertNotEquals(result.headings[0].id, result.headings[1].id)
        assertTrue(result.headings.all { it.id.length <= 2048 })
        assertEquals(result.headings, renderer.render("# $title\n\n# $title").headings)
    }
}
