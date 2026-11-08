package cn.embeddedquiz

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/*
 * One discussion opened from 更多 › AI 讨论 (PLAN-1.7.3, kept by the 1.7.4 workbench): the same
 * AiChatScreen and controller as the original entries, through the question or document adapter.
 * Back returns to the workbench, then to 更多.
 */

/**
 * The adapter a discussion would have on its original page. The current question or document is
 * used when it still exists; otherwise the owner and label from the list keep the old discussion
 * readable (the backend marks it read only). There is no reading anchor here, so a document
 * discussion has no section, as PLAN-1.7.3 asks.
 */
internal fun aiDiscussionSource(
    item: AiDiscussionListItem,
    question: (String) -> QuestionSummary?,
    document: (String) -> QuickNoteSummary?,
    model: () -> String,
    savedCode: (String) -> String,
): AiChatSource =
    when (item.owner.kind) {
        AiOwnerKind.QUESTION -> {
            val current = question(item.owner.questionId)
            val owner = current?.let { QuestionOwner(it.id, it.contentHash) } ?: item.owner
            // Only this question's own saved code goes with a code review; no answer choices are guessed.
            QuestionAiSource(owner, programming = current?.daily == false) {
                AiSendContext(emptySet(), savedCode(owner.questionId), model())
            }
        }
        AiOwnerKind.QUICK_NOTE -> {
            val summary =
                document(item.owner.questionId)
                    ?: QuickNoteSummary(item.owner.questionId, item.sourceLabel, "", item.owner.contentHash, 0)
            DocumentAiSource(summary, null, model)
        }
        AiOwnerKind.FREE, AiOwnerKind.TEMPORARY -> UnlinkedAiSource(item.owner, model)
    }

internal fun aiDiscussionKind(item: AiDiscussionListItem) = when (item.owner.kind) {
    AiOwnerKind.QUESTION -> "题目"
    AiOwnerKind.QUICK_NOTE -> "速记"
    AiOwnerKind.FREE -> "自由对话"
    AiOwnerKind.TEMPORARY -> "临时"
}

/** Opens [item] in [controller] and shows it alone; leaving it returns to the list. */
@Composable
internal fun AiDiscussionChat(
    controller: AiChatController,
    item: AiDiscussionListItem,
    onLeave: () -> Unit,
    onOpenNote: ((StickyNote) -> Unit)? = null,
    sourceHeader: (@Composable (SessionView) -> Unit)? = null,
    deleteSource: String? = null,
    onDeleted: (() -> Unit)? = null,
) {
    LaunchedEffect(controller) { controller.open(item.sessionId) }
    DisposableEffect(controller) { onDispose { controller.back() } }
    AiChatScreen(
        controller,
        Modifier.testTag("ai-all-chat"),
        onOpenNote = if (controller.canSaveNotes) onOpenNote else null,
        onLeaveSession = onLeave,
        sourceHeader = sourceHeader,
        deleteSource = deleteSource,
        // Deleted from its ⋯ menu: back to the list, which no longer has it.
        onDeleted = onDeleted ?: onLeave,
    )
}


