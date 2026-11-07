package cn.embeddedquiz

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.IdentityHashMap
import java.util.Locale
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.*
import org.commonmark.parser.Parser
import org.commonmark.renderer.NodeRenderer
import org.commonmark.renderer.html.HtmlRenderer

object QuickNotePaths {
    fun decode(value: String): String = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    fun normalize(value: String): String {
        require(value.isNotBlank() && !value.startsWith('/') && '\\' !in value && '\u0000' !in value) { "资源路径无效" }
        require(!Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(value)) { "不支持该资源地址" }
        val parts = mutableListOf<String>()
        for (part in value.split('/')) when (part) {
            "", "." -> Unit
            ".." -> { require(parts.isNotEmpty()) { "资源路径超出文档包" }; parts.removeAt(parts.lastIndex) }
            else -> parts.add(part)
        }
        require(parts.isNotEmpty()) { "资源路径无效" }
        return parts.joinToString("/")
    }

    fun resolve(markdownPath: String, destination: String): String {
        val path = decode(destination.substringBefore('#').substringBefore('?'))
        val directory = markdownPath.substringBeforeLast('/', "")
        require(!path.startsWith('/') && !Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(path)) { "图片必须是包内相对路径" }
        return normalize(if (directory.isEmpty()) path else "$directory/$path")
    }

    fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
}

/** CommonMark + GFM tables/strikethrough, producing semantic HTML, not a Compose page. */
class QuickNoteMarkdown {
    private val extensions = listOf(TablesExtension.create(), StrikethroughExtension.create())
    private val parser = Parser.builder().extensions(extensions).build()

    fun render(markdown: String, documentId: String = "preview", markdownPath: String = "document.md", images: Set<String> = emptySet()): QuickNoteRendered {
        val root = parser.parse(markdown)
        val outline = mutableListOf<QuickNoteHeading>()
        val ids = IdentityHashMap<Node, String>()
        val code = linkedMapOf<String, String>()
        val warnings = linkedSetOf<String>()
        val used = mutableSetOf<String>()
        fun visit(node: Node) {
            if (node is Heading) {
                val title = plain(node)
                val id = headingId(title, used)
                ids[node] = id
                outline.add(QuickNoteHeading(id, node.level, title))
            }
            val literal = when (node) {
                is FencedCodeBlock -> node.literal
                is IndentedCodeBlock -> node.literal
                else -> null
            }
            if (literal != null) {
                val id = "code-${code.size}"
                ids[node] = id
                code[id] = literal
            }
            var child = node.firstChild
            while (child != null) { visit(child); child = child.next }
        }
        visit(root)
        if (markdown.contains("embedded content:")) warnings.add("原文的嵌入内容仅有占位文字，未包含对应图形。")
        // Exclude code when reporting unsupported math; the actual sample has no math.
        val outsideCode = markdown.replace(Regex("(?s)```.*?```|~~~.*?~~~|`[^`]*`"), "")
        if (Regex("""\$\$|\$[^\n$]+\$|\\\(|\\\[""").containsMatchIn(outsideCode))
            warnings.add("第一版不排版数学公式，公式语法按原文显示。")
        val renderer = HtmlRenderer.builder().escapeHtml(true).sanitizeUrls(true)
            .nodeRendererFactory { context -> object : NodeRenderer {
                override fun getNodeTypes(): Set<Class<out Node>> = setOf(Heading::class.java, FencedCodeBlock::class.java, IndentedCodeBlock::class.java, TableBlock::class.java, Image::class.java, Link::class.java)
                private val html = context.writer
                private fun children(node: Node) {
                    var child = node.firstChild
                    while (child != null) { context.render(child); child = child.next }
                }
                override fun render(node: Node) {
                    when (node) {
                        is Heading -> {
                            html.tag("h${node.level}", mapOf("id" to ids.getValue(node)))
                            children(node); html.tag("/h${node.level}"); html.line()
                        }
                        is TableBlock -> {
                            html.tag("div", mapOf("class" to "qn-table-scroll", "role" to "region", "aria-label" to "表格，可横向查看"))
                            html.tag("table"); children(node); html.tag("/table"); html.tag("/div"); html.line()
                        }
                        is FencedCodeBlock, is IndentedCodeBlock -> {
                            val id = ids.getValue(node)
                            html.tag("div", mapOf("class" to "qn-code-block"))
                            html.tag("a", mapOf("href" to "quicknote-copy:$id", "class" to "qn-copy", "role" to "button"))
                            html.text("复制代码"); html.tag("/a")
                            html.tag("div", mapOf("class" to "qn-code-scroll", "role" to "region", "aria-label" to "代码，可横向查看"))
                            html.tag("pre"); html.tag("code"); html.text(code.getValue(id))
                            html.tag("/code"); html.tag("/pre"); html.tag("/div"); html.tag("/div"); html.line()
                        }
                        is Image -> {
                            val path = runCatching { QuickNotePaths.resolve(markdownPath, node.destination) }.getOrNull()
                            if (path != null && path in images) {
                                html.tag("img", mapOf("src" to "https://appassets.androidplatform.net/quicknotes/$documentId/assets/${QuickNotePaths.encode(path)}", "alt" to plain(node)), true)
                            } else {
                                val message = "图片未载入：${plain(node).ifBlank { node.destination }}（${node.destination}）。本地图片请连同 Markdown 打包 ZIP 导入；网络或 SVG 图片第一版不自动载入。"
                                warnings.add(message)
                                html.tag("span", mapOf("class" to "qn-missing-image", "role" to "note")); html.text(message); html.tag("/span")
                            }
                        }
                        is Link -> {
                            val dest = node.destination
                            val fragment = if (dest.startsWith('#')) runCatching { QuickNotePaths.decode(dest.drop(1)) }.getOrNull() else null
                            val safe = fragment != null || Regex("(?i)^(https?://|mailto:)").containsMatchIn(dest)
                            if (fragment != null && fragment !in used) warnings.add("未找到文档内锚点：$fragment")
                            if (safe) {
                                val href = if (fragment != null) "#${QuickNotePaths.encode(fragment)}" else dest
                                html.tag("a", mapOf("href" to href, "rel" to "noopener noreferrer")); children(node); html.tag("/a")
                            } else {
                                warnings.add("不支持的链接：$dest")
                                html.tag("span"); children(node); html.tag("/span")
                            }
                        }
                    }
                }
            } }.extensions(extensions).build()
        val body = renderer.render(root)
        return QuickNoteRendered(body, outline, code, warnings.toList())
    }

    private fun headingId(title: String, used: MutableSet<String>): String {
        val base = boundedAiText(title.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}_ -]"), "")
            .trim().replace(Regex("\\s+"), "-").ifBlank { "section" }, 2000)
        var id = base
        var suffix = 1
        while (!used.add(id)) id = "$base-${suffix++}"
        return id
    }

    internal fun aiParagraphs(markdown: String): List<QuickNoteAiParagraph> {
        val used = mutableSetOf<String>()
        val paragraphs = mutableListOf<QuickNoteAiParagraph>()
        var heading = QuickNoteHeading("", 0, "文档开头")
        var ordinal = 0
        fun add(text: String) {
            if (text.isNotBlank()) paragraphs += QuickNoteAiParagraph(heading, ++ordinal, text.trim())
        }
        fun visit(node: Node) {
            when (node) {
                is Heading -> {
                    val title = plain(node)
                    heading = QuickNoteHeading(headingId(title, used), node.level, title)
                    ordinal = 0
                }
                is Paragraph, is TableBlock -> add(aiPlain(node))
                is FencedCodeBlock -> add(node.literal)
                is IndentedCodeBlock -> add(node.literal)
                else -> {
                    var child = node.firstChild
                    while (child != null) { visit(child); child = child.next }
                }
            }
        }
        visit(parser.parse(markdown))
        return paragraphs
    }

    private fun aiPlain(node: Node): String = buildString {
        fun visit(n: Node) {
            when (n) {
                is Text -> append(n.literal)
                is Code -> append(n.literal)
                is SoftLineBreak, is HardLineBreak -> append(' ')
                is Image, is HtmlInline, is HtmlBlock -> Unit
                else -> {
                    var child = n.firstChild
                    while (child != null) {
                        visit(child)
                        if (n is TableRow || n is TableHead || n is TableBody) append(' ')
                        child = child.next
                    }
                }
            }
        }
        visit(node)
    }

    private fun plain(node: Node): String {
        val value = StringBuilder()
        fun collect(n: Node) {
            when (n) {
                is Text -> value.append(n.literal)
                is Code -> value.append(n.literal)
                is SoftLineBreak, is HardLineBreak -> value.append(' ')
            }
            var child = n.firstChild
            while (child != null) { collect(child); child = child.next }
        }
        collect(node)
        return value.toString()
    }
}
