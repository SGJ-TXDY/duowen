package cn.embeddedquiz

/** Adapter for a chat opened by ID. New saved chats start with startFreeDiscussion. */
internal class UnlinkedAiSource(
    private val owner: QuestionOwner,
    private val model: () -> String,
) : AiChatSource {
    override val context = if (owner.kind == AiOwnerKind.TEMPORARY) AiSourceContext("临时", "不保留记录")
        else AiSourceContext("自由对话", "未关联题目或速记")
    override val quickTypes = emptyList<AiRequestType>()
    override val staleLabel = ""
    override fun current(session: SessionView) = session.question == owner
    override suspend fun sessions(facade: AiConversationFacade, cursor: PageCursor?, limit: Int) =
        ResultOf.Ok(AiPage<SessionView>(emptyList(), null))

    override suspend fun create(facade: AiConversationFacade, commandId: String) =
        ResultOf.Rejected(Rejection(RejectCode.INVALID, false, RecoveryAction.NONE))

    override suspend fun send(facade: AiConversationFacade, request: AiSendRequest) = facade.send(
        SendCommand(request.commandId, request.session.sessionId, request.session.question,
            request.session.contextRevision, request.draftToken, request.savedDraftRevision,
            request.input, emptySet(), "", AiRequestType.FOLLOW_UP, model())
    )
}
