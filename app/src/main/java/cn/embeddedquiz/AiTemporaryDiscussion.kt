package cn.embeddedquiz

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase

/** One isolated Room store per temporary page. No handle to it enters the backup or workbench. */
internal class AiTemporaryDiscussion private constructor(
    private val database: QuizDatabase,
    val sessionId: String,
    val storedOwner: QuestionOwner,
    val store: AiStore,
    val pages: AiPageReader,
) {
    val dao = database.ai()
    val owner = storedOwner.copy(kind = AiOwnerKind.TEMPORARY)

    suspend fun view(row: AiSessionRow): SessionView = pages.session(row).copy(question = owner, questionSnapshot = "")

    suspend fun messages(cursor: PageCursor?, limit: Int, busy: Boolean): AiPage<MessageView> {
        val page = pages.messages(sessionId, cursor, limit, busy)
        return AiPage(page.items.map { message -> message.copy(run = message.run?.let(::publicRun)) }, page.next)
    }

    fun publicRun(run: RunView): RunView = run.copy(owner = run.owner.copy(question = owner))

    fun close() = database.close()

    companion object {
        suspend fun open(
            repo: QuizRepository, sessionId: String, coordinatorEpoch: String,
            config: AiRuntimeConfig, datasetEpoch: () -> String,
        ): AiTemporaryDiscussion {
            val db = Room.inMemoryDatabaseBuilder(repo.db.storageContext, QuizDatabase::class.java)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        db.execSQL("PRAGMA temp_store = MEMORY")
                    }
                }).build()
            try {
                val dao = db.ai()
                val snapshot = AiFreeSnapshot(sessionId, "临时对话", null).encode()
                val now = config.now()
                val row = AiSessionRow(sessionId, sessionId, DeepSeekClient.hash(snapshot), AiSnapshotCodec.FREE_VERSION,
                    snapshot, "临时对话", now, now)
                db.withTransaction { dao.insertSession(AiMeter.stored(row)) }
                return AiTemporaryDiscussion(db, sessionId, row.owner,
                    AiStore(db, dao, { null }, config.now, config.zone, coordinatorEpoch,
                        ownedByCoordinator = true, useCache = true),
                    AiPageReader(dao, repo, datasetEpoch))
            } catch (e: Throwable) {
                db.close()
                throw e
            }
        }
    }
}
