package cn.embeddedquiz

internal val AiSessionRow.owner: QuestionOwner
    get() = QuestionOwner(questionId, contentHash,
        when (questionSnapshotVersion) {
            AiSnapshotCodec.DOCUMENT_VERSION -> AiOwnerKind.QUICK_NOTE
            AiSnapshotCodec.FREE_VERSION -> AiOwnerKind.FREE
            else -> AiOwnerKind.QUESTION
        })

internal val AiOwnerKind.savedSnapshotVersion: Int
    get() = when (this) {
        AiOwnerKind.QUESTION -> AiSnapshotCodec.VERSION
        AiOwnerKind.QUICK_NOTE -> AiSnapshotCodec.DOCUMENT_VERSION
        AiOwnerKind.FREE -> AiSnapshotCodec.FREE_VERSION
        AiOwnerKind.TEMPORARY -> throw AiStoreFailure(RejectCode.INVALID)
    }
