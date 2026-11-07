package cn.embeddedquiz

import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.CancellationException

enum class StickyNoteOriginStatus { CURRENT, CHANGED, MISSING }
data class StickyNoteOriginView(val label: String, val status: StickyNoteOriginStatus)
sealed interface StickyNoteOriginOpen {
    data class Ready(val question: Question, val changed: Boolean) : StickyNoteOriginOpen
    data object Missing : StickyNoteOriginOpen
}

internal fun StickyNote.stickyNoteOrigin(currentHash: (String) -> String?): StickyNoteOriginView? {
    val id = sourceQuestionId ?: return null
    val label = sourceQuestionLabel ?: return null
    val hash = currentHash(id)
    return StickyNoteOriginView(label, when (hash) {
        null -> StickyNoteOriginStatus.MISSING
        sourceQuestionHash -> StickyNoteOriginStatus.CURRENT
        else -> StickyNoteOriginStatus.CHANGED
    })
}

internal suspend fun QuizRepository.openStickyNoteSource(note: StickyNote): StickyNoteOriginOpen {
    val id = note.sourceQuestionId ?: return StickyNoteOriginOpen.Missing
    val current = question(id) ?: return StickyNoteOriginOpen.Missing
    return StickyNoteOriginOpen.Ready(current, current.contentHash != note.sourceQuestionHash)
}

internal suspend fun <T> aiLocalResult(block: suspend () -> T): ResultOf<T> = try {
    ResultOf.Ok(block())
} catch (e: CancellationException) {
    throw e
} catch (e: AiStoreFailure) {
    ResultOf.Rejected(Rejection(e.code, e.code == RejectCode.STALE_OWNER,
        if (e.code == RejectCode.STALE_OWNER) RecoveryAction.RELOAD else RecoveryAction.NONE))
} catch (_: IllegalArgumentException) {
    ResultOf.Rejected(Rejection(RejectCode.INVALID, false, RecoveryAction.NONE))
} catch (_: Exception) {
    ResultOf.Rejected(Rejection(RejectCode.STORAGE_FAILED, true, RecoveryAction.RESTART))
}

internal suspend fun QuizRepository.saveAiReplyAsStickyNote(commandId: String, reply: MessageView): ResultOf<StickyNote> =
    aiLocalResult {
        AiJson.uuid(commandId)
        val owner = reply.run?.owner ?: throw AiStoreFailure(RejectCode.INVALID)
        if (owner.question.kind != AiOwnerKind.QUESTION) throw AiStoreFailure(RejectCode.INVALID)
        db.aiLifecycle.foundationWrite {
            db.withTransaction {
                val ai = db.ai()
                val run = ai.run(owner.requestId) ?: throw AiStoreFailure(RejectCode.NOT_FOUND)
                val session = ai.session(run.sessionId) ?: throw AiStoreFailure(RejectCode.NOT_FOUND)
                if (owner != RunOwner(session.owner, run.sessionId, run.requestId) ||
                    reply.sessionId != run.sessionId || reply.messageId != run.assistantMessageId)
                    throw AiStoreFailure(RejectCode.STALE_OWNER)
                val message = ai.message(run.assistantMessageId) ?: throw AiStoreFailure(RejectCode.NOT_FOUND)
                if (run.status != "COMPLETED" || message.role != "ASSISTANT" ||
                    message.completion != "COMPLETE" || message.text.isBlank())
                    throw AiStoreFailure(RejectCode.INVALID)
                dao.stickyNoteByAiCommand(commandId)?.let {
                    if (it.sourceRequestId != run.requestId) throw AiStoreFailure(RejectCode.IDEMPOTENCY_CONFLICT)
                    return@withTransaction it
                }
                dao.stickyNoteByAiRequest(run.requestId)?.let { return@withTransaction it }
                val question = AiSnapshotCodec.readQuestion(session.questionSnapshot, session.owner)
                val label = boundedAiText(question.stem.trim().lineSequence().first(), 120)
                val now = System.currentTimeMillis()
                val note = StickyNote(UUID.randomUUID().toString(), "AI 解析 · $label", message.text, now, now,
                    run.requestId, commandId, question.id, question.contentHash, label)
                dao.insertAiStickyNote(note)
                note
            }
        }
    }
