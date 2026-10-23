package cn.embeddedquiz

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class ReadingBlock(val text: String, val code: Boolean)

object ReadingBlocks {
    fun parse(text: String): List<ReadingBlock> {
        val output = mutableListOf<ReadingBlock>()
        val lines = text.split('\n')
        var current = StringBuilder()
        var code = false
        lines.forEachIndexed { index, line ->
            val isCode =
                line.startsWith("    ") || line.startsWith('\t') || (line.isBlank() && code)
            if (current.isNotEmpty() && isCode != code) {
                output += ReadingBlock(current.toString(), code)
                current = StringBuilder()
            }
            code = isCode
            current.append(line)
            if (index < lines.lastIndex) current.append('\n')
        }
        if (current.isNotEmpty()) output += ReadingBlock(current.toString(), code)
        return output
    }
}

sealed class MarkdownBlock {
    data class Text(val value: String, val heading: Int = 0, val list: Boolean = false) :
        MarkdownBlock()

    data class Code(val value: String, val language: String) : MarkdownBlock()
}

object SimpleMarkdown {
    fun parse(text: String): List<MarkdownBlock> {
        val result = mutableListOf<MarkdownBlock>()
        var code: StringBuilder? = null
        var language = ""
        for (line in text.split('\n')) {
            if (line.trimStart().startsWith("```")) {
                if (code == null) {
                    code = StringBuilder()
                    language = line.trim().removePrefix("```")
                } else {
                    result += MarkdownBlock.Code(code.toString().removeSuffix("\n"), language)
                    code = null
                }
            } else if (code != null) code.append(line).append('\n')
            else {
                val heading = Regex("^(#{1,6})\\s+(.*)$").matchEntire(line)
                val item = Regex("^\\s*(?:[-*+] |\\d+[.)] )(.*)$").matchEntire(line)
                when {
                    heading != null ->
                        result +=
                            MarkdownBlock.Text(
                                heading.groupValues[2],
                                heading.groupValues[1].length,
                            )
                    item != null -> result += MarkdownBlock.Text(item.groupValues[1], list = true)
                    line.isNotBlank() -> result += MarkdownBlock.Text(line)
                }
            }
        }
        code?.let { result += MarkdownBlock.Code(it.toString().removeSuffix("\n"), language) }
        return result
    }

    fun inline(text: String): AnnotatedString = buildAnnotatedString {
        val regex = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`")
        var offset = 0
        for (match in regex.findAll(text)) {
            append(text.substring(offset, match.range.first))
            if (match.groupValues[1].isNotEmpty())
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(match.groupValues[1]) }
            else
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                    append(match.groupValues[2])
                }
            offset = match.range.last + 1
        }
        append(text.substring(offset))
    }
}

@Composable
fun CodeBlock(code: String, label: String = "代码") {
    val clipboard = LocalClipboardManager.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 14.dp),
                )
                TextButton(
                    onClick = { clipboard.setText(AnnotatedString(code)) },
                    modifier =
                        Modifier.heightIn(min = 48.dp).semantics { contentDescription = "复制代码" },
                ) {
                    Text("复制代码")
                }
            }
            SelectionContainer {
                Text(
                    code,
                    fontFamily = FontFamily.Monospace,
                    softWrap = false,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier =
                        Modifier.fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = 8.dp),
                )
            }
        }
    }
}

@Composable
fun MarkdownContent(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        remember(text) { SimpleMarkdown.parse(text) }
            .forEach { block ->
                when (block) {
                    is MarkdownBlock.Code ->
                        CodeBlock(
                            block.value,
                            if (block.language.isBlank()) "代码" else "代码 · ${block.language}",
                        )
                    is MarkdownBlock.Text ->
                        SelectionContainer {
                            Text(
                                SimpleMarkdown.inline((if (block.list) "• " else "") + block.value),
                                style =
                                    if (block.heading > 0) MaterialTheme.typography.titleMedium
                                    else MaterialTheme.typography.bodyLarge,
                                fontWeight = if (block.heading > 0) FontWeight.Bold else null,
                            )
                        }
                }
            }
    }
}

@Composable
fun HighlightedText(text: String, search: String, maxLines: Int = Int.MAX_VALUE) {
    val color = MaterialTheme.colorScheme.tertiaryContainer
    val annotated =
        remember(text, search, color) {
            buildAnnotatedString {
                append(text)
                for (term in SearchTerms.split(search)) {
                    var offset = 0
                    while (offset < text.length) {
                        val index = text.indexOf(term, offset, ignoreCase = true)
                        if (index < 0) break
                        addStyle(
                            SpanStyle(background = color, fontWeight = FontWeight.Bold),
                            index,
                            index + term.length,
                        )
                        offset = index + term.length
                    }
                }
            }
        }
    Text(annotated, maxLines = maxLines)
}
