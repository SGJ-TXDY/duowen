package cn.embeddedquiz

internal val AiSessionRow.owner: QuestionOwner
    get() = QuestionOwner(questionId, contentHash,
        if (questionSnapshotVersion == AiSnapshotCodec.DOCUMENT_VERSION) AiOwnerKind.QUICK_NOTE else AiOwnerKind.QUESTION)
