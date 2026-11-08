package cn.embeddedquiz

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Where a sticky note came from, as the backend reports it (PLAN-1.7.1). */
internal enum class NoteSourceStatus {
    /** The question exists in the version the note was saved from. */
    CURRENT,
    /** The question exists but was updated after the note was saved. */
    CHANGED,
    /** The question is no longer in the bank. */
    MISSING,
}

internal data class NoteSourceView(val label: String, val status: NoteSourceStatus)

internal sealed interface NoteSourceOpen {
    data class Ready(val question: Question, val changed: Boolean) : NoteSourceOpen

    data object Missing : NoteSourceOpen
}

/**
 * Note sources and the read-only original question, provided by GPT's note backend. Notes saved
 * before 1.7.1 have no source and look as they always did.
 */
internal interface StickyNoteSources {
    fun source(note: StickyNote): NoteSourceView?

    suspend fun open(note: StickyNote): NoteSourceOpen
}

/** GPT's note origins (PLAN-1.7.1) in this screen's terms; only the question bank is read. */
internal fun stickyNoteSources(vm: QuizViewModel) =
    object : StickyNoteSources {
        override fun source(note: StickyNote) =
            vm.stickyNoteSource(note)?.let {
                NoteSourceView(
                    it.label,
                    when (it.status) {
                        StickyNoteOriginStatus.CURRENT -> NoteSourceStatus.CURRENT
                        StickyNoteOriginStatus.CHANGED -> NoteSourceStatus.CHANGED
                        StickyNoteOriginStatus.MISSING -> NoteSourceStatus.MISSING
                    },
                )
            }

        override suspend fun open(note: StickyNote) =
            when (val r = vm.openStickyNoteSource(note)) {
                is StickyNoteOriginOpen.Ready -> NoteSourceOpen.Ready(r.question, r.changed)
                StickyNoteOriginOpen.Missing -> NoteSourceOpen.Missing
            }
    }

/** The source line of a note, with the way back to its question; the note itself stays readable. */
@Composable
internal fun NoteSourceRow(source: NoteSourceView, opening: Boolean, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("note-source"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "来自题目：" + source.label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onOpen,
                enabled = source.status != NoteSourceStatus.MISSING && !opening,
                modifier = Modifier.heightIn(min = 48.dp).testTag("note-open-source"),
            ) {
                Text(if (opening) "正在打开…" else "回到原题")
            }
        }
        when (source.status) {
            NoteSourceStatus.CURRENT -> Unit
            NoteSourceStatus.CHANGED ->
                Text(
                    "题目在保存便签后已更新，原题内容可能与当时不同。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("note-source-changed"),
                )
            NoteSourceStatus.MISSING ->
                Text(
                    "原题已不在题库中，来源不可用；便签内容不受影响。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("note-source-missing"),
                )
        }
    }
}

/** The original question, read only: nothing here answers, grades or schedules a review. */
@Composable
internal fun SourceQuestionView(
    open: NoteSourceOpen.Ready,
    backLabel: String = "返回便签",
    changedText: String = "题目在保存便签后已更新，下面是题库中的当前内容。",
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val q = open.question
    Column(Modifier.fillMaxSize().testTag("source-question"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(AppIcons.Back, contentDescription = backLabel)
            }
            Text(
                "原题（只读）",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
        }
        if (open.changed)
            Text(
                changedText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("source-question-changed"),
            )
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Text(
                    listOf(q.category, q.difficulty, q.source).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { Text(q.stem, style = MaterialTheme.typography.bodyLarge) }
            if (q.options.isNotEmpty())
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        q.options.forEach { o ->
                            val right = o.key in q.correct
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                border =
                                    BorderStroke(
                                        1.dp,
                                        if (right) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    ),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    o.text + if (right) "（参考答案）" else "",
                                    fontWeight = if (right) FontWeight.Bold else null,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                )
                            }
                        }
                    }
                }
            listOf("参考答案" to q.answer, "解析" to q.explanation, "解题思路" to q.approach)
                .filter { it.second.isNotBlank() }
                .forEach { (title, body) ->
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(title, style = MaterialTheme.typography.titleSmall)
                            Text(body)
                        }
                    }
                }
            if (q.referenceCode.isNotBlank())
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("参考代码", style = MaterialTheme.typography.titleSmall)
                        CodeBlock(q.referenceCode, "原题参考代码")
                    }
                }
        }
    }
}
