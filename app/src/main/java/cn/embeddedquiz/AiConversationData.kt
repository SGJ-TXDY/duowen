package cn.embeddedquiz

import androidx.room.*

@Entity(tableName = "ai_sessions",
    indices = [Index(value = ["questionId", "contentHash", "updatedAt", "sessionId"], unique = false), Index(value = ["encodedBytes"], unique = false)])
data class AiSessionRow(
    @PrimaryKey val sessionId: String,
    val questionId: String,
    val contentHash: String,
    val questionSnapshotVersion: Int = 1,
    val questionSnapshot: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val sessionRevision: Long = 0,
    val contextRevision: Long = 0,
    val draftText: String = "",
    val draftRevision: Long = 0,
    val lastEditorEpoch: String? = null,
    val lastLocalEditVersion: Long? = null,
    val encodedBytes: Long = 0,
    val activeEditorEpoch: String? = null,
)

@Entity(tableName = "ai_messages",
    foreignKeys = [ForeignKey(entity = AiSessionRow::class, parentColumns = ["sessionId"], childColumns = ["sessionId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["sessionId", "seq"], unique = true), Index(value = ["encodedBytes"], unique = false)])
data class AiMessageRow(
    @PrimaryKey val messageId: String,
    val sessionId: String,
    val seq: Long,
    val role: String,
    val text: String,
    val completion: String,
    val createdAt: Long,
    val encodedBytes: Long = 0,
)

@Entity(tableName = "ai_runs",
    foreignKeys = [ForeignKey(entity = AiSessionRow::class, parentColumns = ["sessionId"], childColumns = ["sessionId"], onDelete = ForeignKey.RESTRICT), ForeignKey(entity = AiMessageRow::class, parentColumns = ["messageId"], childColumns = ["userMessageId"], onDelete = ForeignKey.RESTRICT), ForeignKey(entity = AiMessageRow::class, parentColumns = ["messageId"], childColumns = ["assistantMessageId"], onDelete = ForeignKey.RESTRICT), ForeignKey(entity = AiRunRow::class, parentColumns = ["requestId"], childColumns = ["retryOf"], onDelete = ForeignKey.RESTRICT), ForeignKey(entity = AiRunRow::class, parentColumns = ["requestId"], childColumns = ["sourceRequestId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["commandId"], unique = true), Index(value = ["assistantMessageId"], unique = true), Index(value = ["userMessageId", "createdAt", "requestId"], unique = false), Index(value = ["sessionId", "createdAt", "requestId"], unique = false), Index(value = ["encodedBytes"], unique = false), Index(value = ["status", "reservedBytes"], unique = false), Index(value = ["retryOf"], unique = false), Index(value = ["sourceRequestId"], unique = false)])
data class AiRunRow(
    @PrimaryKey val requestId: String,
    val commandId: String,
    val sessionId: String,
    val userMessageId: String,
    val assistantMessageId: String,
    val model: String,
    val requestType: String,
    val coordinatorEpoch: String,
    val contextSnapshot: String,
    val contextDigest: String,
    val cacheKey: String,
    val createdAt: Long,
    val billingDay: Long,
    val billingZone: String,
    val contextSnapshotVersion: Int = 1,
    val status: String = "PREPARING",
    val source: String = "NETWORK",
    val sourceRequestId: String? = null,
    val retryOf: String? = null,
    val terminalReason: String? = null,
    val promptVersion: String = "1",
    val effectiveParameters: String = "{}",
    val reservedBytes: Long = 0,
    val eventSeq: Long = 0,
    val usageCutoffSeq: Long? = null,
    val networkDispatched: Boolean = false,
    val finishedAt: Long? = null,
    val providerResponseId: String? = null,
    val finishReason: String? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val hitTokens: Long? = null,
    val usageStatus: String = "UNKNOWN",
    val priceVersion: String? = null,
    val missRate: String? = null,
    val hitRate: String? = null,
    val outputRate: String? = null,
    val estimatedUsd: String? = null,
    val currency: String = "USD",
    val errorCode: String? = null,
    val encodedBytes: Long = 0,
)

@Entity(tableName = "ai_commands",
    foreignKeys = [ForeignKey(entity = AiSessionRow::class, parentColumns = ["sessionId"], childColumns = ["sessionId"], onDelete = ForeignKey.RESTRICT), ForeignKey(entity = AiRunRow::class, parentColumns = ["requestId"], childColumns = ["requestId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["sessionId", "createdAt", "commandId"], unique = false), Index(value = ["encodedBytes"], unique = false), Index(value = ["requestId"], unique = false)])
data class AiCommandRow(
    @PrimaryKey val commandId: String,
    val kind: String,
    val payloadDigest: String,
    val receiptJson: String,
    val createdAt: Long,
    val sessionId: String? = null,
    val requestId: String? = null,
    val encodedBytes: Long = 0,
)

@Entity(tableName = "ai_response_cache",
    foreignKeys = [ForeignKey(entity = AiRunRow::class, parentColumns = ["requestId"], childColumns = ["sourceRequestId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["encodedBytes"], unique = false), Index(value = ["sourceRequestId"], unique = false)])
data class AiCacheRow(
    @PrimaryKey val cacheKey: String,
    val sourceRequestId: String,
    val createdAt: Long,
    val protocolVersion: Int = 1,
    val encodedBytes: Long = 0,
)

@Dao
interface AiConversationDao : AiRowReader {
    @Query("SELECT * FROM ai_sessions ORDER BY sessionId") suspend fun allSessions(): List<AiSessionRow>
    @Query("SELECT * FROM ai_sessions WHERE sessionId=:id") override suspend fun session(id: String): AiSessionRow?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertSession(value: AiSessionRow)
    @Update(onConflict = OnConflictStrategy.ABORT) suspend fun updateSession(value: AiSessionRow)
    @Query("SELECT * FROM ai_messages ORDER BY messageId") suspend fun allMessages(): List<AiMessageRow>
    @Query("SELECT * FROM ai_messages WHERE messageId=:id") override suspend fun message(id: String): AiMessageRow?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertMessage(value: AiMessageRow)
    @Update(onConflict = OnConflictStrategy.ABORT) suspend fun updateMessage(value: AiMessageRow)
    @Query("SELECT * FROM ai_runs ORDER BY requestId") suspend fun allRuns(): List<AiRunRow>
    @Query("SELECT * FROM ai_runs WHERE requestId=:id") override suspend fun run(id: String): AiRunRow?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertRun(value: AiRunRow)
    @Update(onConflict = OnConflictStrategy.ABORT) suspend fun updateRun(value: AiRunRow)
    @Query("SELECT * FROM ai_commands ORDER BY commandId") suspend fun allCommands(): List<AiCommandRow>
    @Query("SELECT * FROM ai_commands WHERE commandId=:id") override suspend fun command(id: String): AiCommandRow?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertCommand(value: AiCommandRow)
    @Update(onConflict = OnConflictStrategy.ABORT) suspend fun updateCommand(value: AiCommandRow)
    @Query("SELECT * FROM ai_response_cache ORDER BY cacheKey") suspend fun allCaches(): List<AiCacheRow>
    @Query("SELECT * FROM ai_response_cache WHERE cacheKey=:id") override suspend fun cache(id: String): AiCacheRow?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertCache(value: AiCacheRow)
    @Update(onConflict = OnConflictStrategy.ABORT) suspend fun updateCache(value: AiCacheRow)

    @Query("SELECT * FROM ai_runs WHERE assistantMessageId=:id") override suspend fun runForAssistant(id:String):AiRunRow?
    @Query("SELECT * FROM ai_messages WHERE sessionId=:id ORDER BY seq DESC LIMIT 1") suspend fun latestMessage(id:String):AiMessageRow?
    @Query("SELECT COALESCE(MAX(seq),-1) FROM ai_messages WHERE sessionId=:id") suspend fun maxSeq(id:String):Long
    @Query("SELECT r.* FROM ai_runs r JOIN ai_messages m ON m.messageId=r.assistantMessageId WHERE r.sessionId=:id AND r.status='COMPLETED' ORDER BY m.seq DESC LIMIT 4") suspend fun recentCompleted(id:String):List<AiRunRow>
    @Query("SELECT COUNT(*) FROM ai_runs WHERE status IN ('PREPARING','GENERATING')") suspend fun activeCount():Long
    @Query("SELECT COALESCE(SUM(reservedBytes),0) FROM ai_runs") suspend fun reservedTotal():Long
    @Query("SELECT 'ai_sessions' AS tableName,COUNT(*) AS rowCount,COALESCE(SUM(encodedBytes),0) AS encodedBytes FROM ai_sessions UNION ALL SELECT 'ai_messages',COUNT(*),COALESCE(SUM(encodedBytes),0) FROM ai_messages UNION ALL SELECT 'ai_runs',COUNT(*),COALESCE(SUM(encodedBytes),0) FROM ai_runs UNION ALL SELECT 'ai_commands',COUNT(*),COALESCE(SUM(encodedBytes),0) FROM ai_commands UNION ALL SELECT 'ai_response_cache',COUNT(*),COALESCE(SUM(encodedBytes),0) FROM ai_response_cache") suspend fun measures():List<AiTableMeasure>
    @Query("UPDATE ai_sessions SET activeEditorEpoch=:epoch WHERE sessionId=:id") suspend fun grantEditor(id:String,epoch:String):Int

    @Query("SELECT * FROM ai_sessions WHERE questionId=:qid AND questionSnapshotVersion=:version AND (:archived OR contentHash=:hash) AND (:at IS NULL OR updatedAt<:at OR (updatedAt=:at AND sessionId<:tie)) ORDER BY updatedAt DESC,sessionId DESC LIMIT :limit")
    suspend fun sessionPage(qid:String,hash:String,archived:Boolean,at:Long?,tie:String,limit:Int,version:Int=1):List<AiSessionRow>
    @Query("SELECT * FROM ai_messages WHERE sessionId=:sid AND (:seq IS NULL OR seq<:seq) ORDER BY seq DESC LIMIT :limit")
    suspend fun messagePage(sid:String,seq:Long?,limit:Int):List<AiMessageRow>
    @Query("SELECT * FROM ai WHERE questionId=:qid AND (:mode=2 OR (:mode=1 AND contentHash='') OR (:mode=0 AND contentHash=:hash)) AND (:at IS NULL OR created<:at OR (created=:at AND cacheKey<:tie)) ORDER BY created DESC,cacheKey DESC LIMIT :limit")
    suspend fun legacyPage(qid:String,mode:Int,hash:String,at:Long?,tie:String,limit:Int):List<AiEntry>
    @Query("UPDATE ai_sessions SET activeEditorEpoch=NULL WHERE sessionId=:id AND activeEditorEpoch=:epoch") suspend fun revokeEditor(id:String,epoch:String):Int
    @Query("SELECT COUNT(*) FROM ai_runs WHERE userMessageId=:id AND status='COMPLETED'")
    suspend fun completedAttempts(id:String):Int
}
