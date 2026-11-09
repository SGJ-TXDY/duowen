package cn.embeddedquiz

import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.*
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

/*
 * The 速记 editor's view of a Markdown document (PLAN-1.7.5). Common blocks — paragraphs, ATX
 * headings, single-line list items, to-dos and fenced code — are edited as text; everything else
 * (tables, quotes, images, HTML, nested or multi-line items) stays as its original source and can be
 * opened as raw text. Each block remembers the lines it came from, and [QuickNoteBlocks.compose]
 * rewrites only the blocks the user changed: every other line, blank line and line ending is kept
 * byte for byte.
 */

enum class QuickNoteBlockKind { PARAGRAPH, HEADING, BULLET, ORDERED, TODO, CODE, RAW }

data class QuickNoteBlock(
    /** Stable within one draft; new blocks take fresh keys. */
    val key: Long,
    val kind: QuickNoteBlockKind,
    /** Paragraph text, heading or item text without its marker, code, or the raw source. */
    val text: String,
    val level: Int = 1,
    val checked: Boolean = false,
    val language: String = "",
    /** The bullet ("-", "*", "+") or the ordered number with its delimiter ("1.", "2)"). */
    val marker: String = "-",
    val indent: String = "",
    val fence: String = "```",
    /** What a raw block holds, e.g. 表格, 引用, 图片. */
    val label: String = "",
    /** The original lines, inclusive; null for a block added in this draft. */
    val lines: IntRange? = null,
    val edited: Boolean = false,
    val deleted: Boolean = false,
) {
    val listLike: Boolean
        get() = kind == QuickNoteBlockKind.BULLET || kind == QuickNoteBlockKind.ORDERED || kind == QuickNoteBlockKind.TODO

    /** A raw block that came from a heading (setext): still counts as a heading for positions. */
    val heading: Boolean
        get() = kind == QuickNoteBlockKind.HEADING || (kind == QuickNoteBlockKind.RAW && label == "标题")

    fun lines(): List<String> {
        fun oneLine(s: String) = s.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')
        return when (kind) {
            QuickNoteBlockKind.PARAGRAPH -> if (text.isBlank()) emptyList() else text.split('\n')
            QuickNoteBlockKind.HEADING -> listOf("#".repeat(level.coerceIn(1, 6)) + " " + oneLine(text).trim())
            QuickNoteBlockKind.BULLET -> listOf("$indent$marker " + oneLine(text))
            QuickNoteBlockKind.ORDERED -> listOf("$indent$marker " + oneLine(text))
            QuickNoteBlockKind.TODO -> listOf("$indent$marker [" + (if (checked) "x" else " ") + "] " + oneLine(text))
            QuickNoteBlockKind.CODE -> {
                // A fence longer than any run inside the code keeps the block closed.
                val char = fence.first()
                val longest = Regex(Regex.escape(char.toString()) + "+").findAll(text).maxOfOrNull { it.value.length } ?: 0
                val f = char.toString().repeat(maxOf(fence.length, 3, longest + 1))
                listOf(f + language.trim()) + text.split('\n') + listOf(f)
            }
            QuickNoteBlockKind.RAW -> if (text.isEmpty()) emptyList() else text.split('\n')
        }
    }

    /** Whether a block added in this draft holds anything worth saving. */
    val hasContent: Boolean
        get() = text.isNotBlank() || kind == QuickNoteBlockKind.CODE && language.isNotBlank()
}

object QuickNoteBlocks {
    private val parser =
        Parser.builder()
            .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
            .includeSourceSpans(IncludeSourceSpans.BLOCKS)
            .build()

    private val itemLine = Regex("^(\\s*)([-*+]|\\d{1,9}[.)])([ \\t]+)(\\[[ xX]\\][ \\t]+)?(.*)$")

    /** One source line: its text and the line ending after it ("" for the last line without one). */
    data class Line(val text: String, val end: String)

    /** Lines as CommonMark counts them: "\r\n", "\r" and "\n" each end a line. */
    fun split(text: String): List<Line> {
        val out = mutableListOf<Line>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n' || c == '\r') {
                val end = if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') "\r\n" else c.toString()
                out += Line(text.substring(start, i), end)
                i += end.length
                start = i
            } else i++
        }
        if (start < text.length || out.isEmpty()) out += Line(text.substring(start), "")
        return out
    }

    fun parse(markdown: String, firstKey: Long = 1): List<QuickNoteBlock> {
        val source = split(markdown)
        val root = parser.parse(markdown)
        val blocks = mutableListOf<QuickNoteBlock>()
        var key = firstKey
        fun range(node: Node): IntRange? {
            val spans = node.sourceSpans
            if (spans.isEmpty()) return null
            return spans.first().lineIndex..spans.last().lineIndex
        }
        fun raw(r: IntRange, label: String) =
            QuickNoteBlock(key++, QuickNoteBlockKind.RAW, r.joinToString("\n") { source[it].text }, label = label, lines = r)

        var node = root.firstChild
        while (node != null) {
            val r = range(node)
            if (r != null) {
                when (node) {
                    is Heading -> {
                        val line = source[r.first].text
                        val atx = Regex("^ {0,3}(#{1,6})(?:[ \\t]+(.*?))?[ \\t]*$").matchEntire(line)
                        blocks +=
                            if (atx != null && r.first == r.last)
                                QuickNoteBlock(key++, QuickNoteBlockKind.HEADING, atx.groupValues[2], level = atx.groupValues[1].length, lines = r)
                            else raw(r, "标题")
                    }
                    is Paragraph ->
                        blocks += if (contains(node) { it is Image }) raw(r, "图片") else QuickNoteBlock(key++, QuickNoteBlockKind.PARAGRAPH, r.joinToString("\n") { source[it].text }, lines = r)
                    is FencedCodeBlock -> {
                        val open = source[r.first].text
                        if (node.fenceIndent > 0 || r.last == r.first || !open.trimStart().startsWith(node.fenceCharacter.toString().repeat(node.openingFenceLength)))
                            blocks += raw(r, "代码")
                        else
                            blocks +=
                                QuickNoteBlock(
                                    key++, QuickNoteBlockKind.CODE, node.literal.removeSuffix("\n").removeSuffix("\r"),
                                    language = node.info.orEmpty(),
                                    fence = node.fenceCharacter.toString().repeat(node.openingFenceLength),
                                    lines = r,
                                )
                    }
                    is BulletList,
                    is OrderedList -> {
                        var item = node.firstChild
                        while (item != null) {
                            val ir = range(item)
                            if (ir != null) {
                                val m = itemLine.matchEntire(source[ir.first].text)
                                val simple = ir.first == ir.last && m != null && item.firstChild.let { it == null || (it is Paragraph && it.next == null) }
                                blocks +=
                                    if (!simple || m == null) raw(ir, "列表")
                                    else {
                                        val (indent, marker, _, box, rest) = m.destructured
                                        val ordered = node is OrderedList
                                        when {
                                            box.isNotEmpty() && !ordered ->
                                                QuickNoteBlock(key++, QuickNoteBlockKind.TODO, rest, checked = box[1] != ' ', marker = marker, indent = indent, lines = ir)
                                            else ->
                                                QuickNoteBlock(
                                                    key++, if (ordered) QuickNoteBlockKind.ORDERED else QuickNoteBlockKind.BULLET,
                                                    box + rest, marker = marker, indent = indent, lines = ir,
                                                )
                                        }
                                    }
                            }
                            item = item.next
                        }
                    }
                    is TableBlock -> blocks += raw(r, "表格")
                    is BlockQuote -> blocks += raw(r, "引用")
                    is HtmlBlock -> blocks += raw(r, "HTML")
                    is ThematicBreak -> blocks += raw(r, "分隔线")
                    is IndentedCodeBlock -> blocks += raw(r, "代码")
                    else -> blocks += raw(r, "其他内容")
                }
            }
            node = node.next
        }
        return blocks
    }

    private fun contains(node: Node, test: (Node) -> Boolean): Boolean {
        var child = node.firstChild
        while (child != null) {
            if (test(child) || contains(child, test)) return true
            child = child.next
        }
        return false
    }

    /**
     * The document with [blocks] applied to [original]: untouched blocks and everything between them
     * (blank lines, link definitions) are copied as they were; edited blocks replace exactly their
     * lines; deleted ones drop them; new ones are inserted where they sit, set apart by a blank line.
     */
    fun compose(original: String, blocks: List<QuickNoteBlock>): String {
        val source = if (original.isEmpty()) emptyList() else split(original)
        val newline = source.firstOrNull { it.end.isNotEmpty() }?.end ?: "\n"
        val out = mutableListOf<Line>()
        var cursor = 0
        var lastNew: QuickNoteBlock? = null
        var lastKind: QuickNoteBlock? = null

        fun emit(text: String) {
            if (out.isNotEmpty() && out.last().end.isEmpty()) out[out.lastIndex] = out.last().copy(end = newline)
            out += Line(text, newline)
        }
        fun copy(range: IntRange) {
            for (i in range) {
                if (out.isNotEmpty() && out.last().end.isEmpty()) out[out.lastIndex] = out.last().copy(end = newline)
                out += source[i]
            }
        }
        fun lastBlank() = out.isEmpty() || out.last().text.isBlank()
        fun apart(previous: QuickNoteBlock?, next: QuickNoteBlock) = !(previous != null && previous.listLike && next.listLike)

        for (b in blocks) {
            val r = b.lines
            if (r != null) {
                val gap = cursor until r.first
                copy(gap)
                // A block added just before this one needs the blank line the source did not have.
                if (gap.isEmpty() && lastNew != null && !lastBlank() && apart(lastNew, b)) emit("")
                lastNew = null
                if (b.deleted) {
                    cursor = r.last + 1
                    // Drop the blank line after it too, so no double blank lines are left.
                    if (cursor < source.size && source[cursor].text.isBlank() && lastBlank()) cursor++
                    continue
                }
                if (!b.edited) copy(r) else b.lines().forEach(::emit)
                cursor = r.last + 1
                lastKind = b
            } else if (!b.deleted && b.hasContent) {
                if (!lastBlank() && apart(lastKind, b)) emit("")
                b.lines().forEach(::emit)
                lastNew = b
                lastKind = b
            }
        }
        if (cursor < source.size) {
            if (lastNew != null && !lastBlank() && source[cursor].text.isNotBlank()) emit("")
            copy(cursor until source.size)
        }
        if (out.isEmpty()) return ""
        // Keep the original's choice of ending with a newline or not.
        val endsWithNewline = source.isEmpty() || source.last().end.isNotEmpty()
        out[out.lastIndex] = out.last().copy(end = if (endsWithNewline) out.last().end.ifEmpty { newline } else "")
        return out.joinToString("") { it.text + it.end }
    }
}
