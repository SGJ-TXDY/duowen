package cn.embeddedquiz

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

@Entity(
    tableName = "questions",
    indices =
        [
            Index("kind"),
            Index("source"),
            Index("normalizedCategory"),
            Index("company"),
            Index("reviewRequired"),
        ],
)
data class QuestionRow(
    @PrimaryKey val id: String,
    val json: String,
    @ColumnInfo(defaultValue = "'SHORT'") val kind: String = "SHORT",
    @ColumnInfo(defaultValue = "''") val source: String = "",
    @ColumnInfo(defaultValue = "''") val category: String = "",
    @ColumnInfo(defaultValue = "''") val normalizedCategory: String = "",
    @ColumnInfo(defaultValue = "''") val company: String = "",
    @ColumnInfo(defaultValue = "'未标注'") val difficulty: String = "未标注",
    @ColumnInfo(defaultValue = "1") val reviewRequired: Boolean = true,
    @ColumnInfo(defaultValue = "''") val stem: String = "",
    @ColumnInfo(defaultValue = "''") val correctKeys: String = "",
    @ColumnInfo(defaultValue = "''") val searchText: String = "",
    @ColumnInfo(defaultValue = "0") val hasAnswer: Boolean = false,
    @ColumnInfo(defaultValue = "''") val contentHash: String = "",
    @ColumnInfo(defaultValue = "''") val optionKeys: String = "",
)

@Entity(tableName = "sticky_notes", indices = [
    Index(value = ["sourceRequestId"], unique = true),
    Index(value = ["sourceCommandId"], unique = true),
])
data class StickyNote(
    @PrimaryKey val id: String,
    val title: String = "",
    val content: String = "",
    val created: Long,
    val updated: Long,
    val sourceRequestId: String? = null,
    val sourceCommandId: String? = null,
    val sourceQuestionId: String? = null,
    val sourceQuestionHash: String? = null,
    val sourceQuestionLabel: String? = null,
)

@Entity(tableName = "progress")
data class Progress(
    @PrimaryKey val questionId: String,
    val seen: Int = 0,
    val correctCount: Int = 0,
    val repetitions: Int = 0,
    val interval: Int = 0,
    val ease: Double = 2.5,
    val due: Long = 0,
    val lapses: Int = 0,
    val wrong: Boolean = false,
    val favorite: Boolean = false,
    val flagged: Boolean = false,
    val code: String = "",
    val notes: String = "",
) {
    fun schedule() = Schedule(repetitions, interval, ease, due, lapses)
}

@Entity(
    tableName = "attempts",
    indices = [Index("day"), Index("questionId"), Index("normalizedCategory")],
)
data class Attempt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val questionId: String,
    val timestamp: Long,
    val day: Long,
    val response: String,
    val correct: Boolean?,
    val feedback: String,
    val mode: String,
    val wasNew: Boolean,
    @ColumnInfo(defaultValue = "''") val assessment: String = "",
    @ColumnInfo(defaultValue = "''") val answerSource: String = "",
    @ColumnInfo(defaultValue = "''") val normalizedCategory: String = "",
    @ColumnInfo(defaultValue = "''") val questionHash: String = "",
)

@Entity(tableName = "ai", indices = [Index(value = ["questionId", "contentHash", "created"])])
data class AiEntry(
    @PrimaryKey val cacheKey: String,
    val questionId: String,
    val model: String,
    val prompt: String,
    val response: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val hitTokens: Int,
    val estimatedUsd: Double,
    val created: Long,
    @ColumnInfo(defaultValue = "''") val contentHash: String = "",
    @ColumnInfo(defaultValue = "'FOLLOW_UP'") val requestType: String = "FOLLOW_UP",
    @ColumnInfo(defaultValue = "0")
    val day: Long =
        java.time.Instant.ofEpochMilli(created)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
            .toEpochDay(),
)

@Dao
interface QuizDao {
    @Query("SELECT * FROM exam_terminations WHERE sessionId=:id OR (:legacyHash IS NOT NULL AND legacyHash=:legacyHash) LIMIT 1")
    suspend fun examTermination(id: String, legacyHash: String? = null): ExamTermination?

    @Insert
    suspend fun addExamTermination(value: ExamTermination)

    @Query("SELECT id,category FROM questions") suspend fun categoryRows(): List<CategoryRow>

    @Query("UPDATE questions SET normalizedCategory=:category WHERE id=:id")
    suspend fun setNormalizedCategory(id: String, category: String)

    @Query(
        "UPDATE attempts SET normalizedCategory=COALESCE((SELECT normalizedCategory FROM questions WHERE questions.id=attempts.questionId),normalizedCategory)"
    )
    suspend fun refreshAttemptCategories()

    @Query(
        "SELECT id,stem,kind,source,category,normalizedCategory,company,difficulty,reviewRequired,correctKeys,hasAnswer,contentHash,optionKeys,'' AS matchSnippet FROM questions ORDER BY id"
    )
    fun summaries(): Flow<List<QuestionSummary>>

    @Query("SELECT * FROM questions WHERE id=:id") suspend fun question(id: String): QuestionRow?

    @RawQuery
    suspend fun searchSummaries(query: androidx.sqlite.db.SupportSQLiteQuery): List<QuestionSummary>

    @RawQuery
    suspend fun searchStickyNotes(query: androidx.sqlite.db.SupportSQLiteQuery): List<StickyNote>

    @Query("SELECT COUNT(*) FROM questions") suspend fun questionCount(): Int

    @Query(
        """
        SELECT :today AS day,
            COUNT(CASE WHEN q.kind!='PROGRAMMING' AND p.seen>0 AND p.due<=:today
                THEN 1 END) AS dueCount,
            COUNT(CASE WHEN q.kind!='PROGRAMMING' AND COALESCE(p.seen,0)=0
                THEN 1 END) AS unseenCount,
            COUNT(CASE WHEN q.kind!='PROGRAMMING' THEN 1 END) AS dailyCount,
            COUNT(CASE WHEN q.kind='PROGRAMMING' THEN 1 END) AS programmingCount
        FROM questions q LEFT JOIN progress p ON p.questionId=q.id
        """
    )
    fun todaySummary(today: Long): Flow<TodaySummary>

    @Query("SELECT * FROM learning_settings WHERE id=1") fun settings(): Flow<LearningSettings?>

    @Query("SELECT * FROM learning_settings WHERE id=1")
    suspend fun getSettings(): LearningSettings?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSettings(value: LearningSettings)

    @Query("UPDATE learning_settings SET lastNotifiedDay=:day WHERE id=1")
    suspend fun markNotified(day: Long)

    @Query("SELECT * FROM personal_answers") fun personalAnswers(): Flow<List<PersonalAnswer>>

    @Query("SELECT * FROM personal_answers") suspend fun allPersonalAnswers(): List<PersonalAnswer>

    @Query("SELECT * FROM personal_answers WHERE questionId=:id")
    suspend fun personalAnswer(id: String): PersonalAnswer?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putPersonalAnswer(value: PersonalAnswer)

    @Query("DELETE FROM personal_answers WHERE questionId=:id")
    suspend fun deletePersonalAnswer(id: String)

    @Query("DELETE FROM personal_answers") suspend fun clearPersonalAnswers()

    @Query("SELECT * FROM question_personal") fun personalMetadata(): Flow<List<QuestionPersonal>>

    @Query("SELECT * FROM question_personal WHERE questionId=:id")
    suspend fun personalMetadata(id: String): QuestionPersonal?

    @Query("SELECT * FROM question_personal")
    suspend fun allPersonalMetadata(): List<QuestionPersonal>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putPersonalMetadata(value: QuestionPersonal)

    @Query("DELETE FROM question_personal") suspend fun clearPersonalMetadata()

    @Query(
        "SELECT * FROM ai WHERE questionId=:id AND (contentHash=:hash OR contentHash='') ORDER BY created DESC LIMIT 20"
    )
    fun questionAi(id: String, hash: String): Flow<List<AiEntry>>

    @Query(
        "SELECT * FROM ai WHERE questionId=:id AND contentHash=:hash ORDER BY created DESC LIMIT 4"
    )
    suspend fun aiContext(id: String, hash: String): List<AiEntry>

    @Query(
        "SELECT day,model,SUM(inputTokens) AS inputTokens,SUM(outputTokens) AS outputTokens,SUM(estimatedUsd) AS estimatedUsd FROM ai GROUP BY day,model ORDER BY day DESC,model"
    )
    fun costs(): Flow<List<CostCount>>

    @Query(
        "SELECT day,COUNT(DISTINCT questionId) AS answered,SUM(CASE WHEN correct IS NOT NULL AND assessment='' AND answerSource!='MANUAL' THEN 1 ELSE 0 END) AS automatic,SUM(CASE WHEN correct=1 AND assessment='' AND answerSource!='MANUAL' THEN 1 ELSE 0 END) AS autoCorrect,SUM(CASE WHEN assessment!='' OR answerSource='MANUAL' THEN 1 ELSE 0 END) AS manual,SUM(CASE WHEN correct=1 AND (assessment!='' OR answerSource='MANUAL') THEN 1 ELSE 0 END) AS manualCorrect,SUM(CASE WHEN correct IS NULL THEN 1 ELSE 0 END) AS ungraded,COUNT(DISTINCT CASE WHEN wasNew=1 THEN questionId END) AS newAnswered,COUNT(DISTINCT CASE WHEN mode='到期复习' THEN questionId END) AS reviewAnswered FROM attempts WHERE day>=:fromDay GROUP BY day ORDER BY day"
    )
    fun dailyCounts(fromDay: Long): Flow<List<DailyCount>>

    @Query(
        "SELECT normalizedCategory AS category,COUNT(DISTINCT questionId) AS answered,SUM(CASE WHEN correct IS NOT NULL AND assessment='' AND answerSource!='MANUAL' THEN 1 ELSE 0 END) AS automatic,SUM(CASE WHEN correct=1 AND assessment='' AND answerSource!='MANUAL' THEN 1 ELSE 0 END) AS autoCorrect,SUM(CASE WHEN assessment!='' OR answerSource='MANUAL' THEN 1 ELSE 0 END) AS manual,SUM(CASE WHEN correct=1 AND (assessment!='' OR answerSource='MANUAL') THEN 1 ELSE 0 END) AS manualCorrect,COUNT(DISTINCT CASE WHEN correct=1 AND id=(SELECT MAX(a2.id) FROM attempts a2 WHERE a2.questionId=attempts.questionId) THEN questionId END) AS mastered FROM attempts GROUP BY normalizedCategory"
    )
    fun categoryCounts(): Flow<List<CategoryCount>>

    @Query(
        "SELECT CASE WHEN due<:today THEN :today ELSE due END AS day,COUNT(*) AS count FROM progress INNER JOIN questions ON questions.id=progress.questionId WHERE seen>0 AND kind!='PROGRAMMING' AND due<=:lastDay GROUP BY CASE WHEN due<:today THEN :today ELSE due END ORDER BY day"
    )
    fun dueCounts(today: Long, lastDay: Long): Flow<List<DueCount>>

    @Query("SELECT DISTINCT day FROM attempts ORDER BY day DESC")
    fun learnedDays(): Flow<List<Long>>

    @Query(
        "SELECT COUNT(*) AS attempts,COUNT(DISTINCT questionId) AS studied,COALESCE(SUM(CASE WHEN correct IS NOT NULL AND assessment='' AND answerSource!='MANUAL' THEN 1 ELSE 0 END),0) AS automatic,COALESCE(SUM(CASE WHEN correct=1 AND assessment='' AND answerSource!='MANUAL' THEN 1 ELSE 0 END),0) AS autoCorrect,COALESCE(SUM(CASE WHEN assessment!='' OR answerSource='MANUAL' THEN 1 ELSE 0 END),0) AS manual,COALESCE(SUM(CASE WHEN correct=1 AND (assessment!='' OR answerSource='MANUAL') THEN 1 ELSE 0 END),0) AS manualCorrect FROM attempts"
    )
    fun totals(): Flow<Totals>

    @Query("SELECT * FROM attempts ORDER BY timestamp DESC,id DESC LIMIT :limit OFFSET :offset")
    suspend fun historyPage(limit: Int, offset: Int): List<Attempt>

    @Query(
        "SELECT COUNT(*) FROM progress INNER JOIN questions ON questions.id=progress.questionId WHERE seen>0 AND due<=:today AND kind!='PROGRAMMING'"
    )
    suspend fun dueCount(today: Long): Int

    @Query("SELECT * FROM sticky_notes ORDER BY updated DESC, id")
    fun stickyNotes(): Flow<List<StickyNote>>

    @Query("SELECT * FROM sticky_notes ORDER BY updated DESC, id")
    suspend fun allStickyNotes(): List<StickyNote>

    @Query("SELECT * FROM sticky_notes WHERE sourceRequestId=:id")
    suspend fun stickyNoteByAiRequest(id: String): StickyNote?

    @Query("SELECT * FROM sticky_notes WHERE sourceCommandId=:id")
    suspend fun stickyNoteByAiCommand(id: String): StickyNote?

    @Insert suspend fun insertAiStickyNote(row: StickyNote)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putStickyNote(row: StickyNote)

    @Query("DELETE FROM sticky_notes WHERE id=:id") suspend fun deleteStickyNote(id: String)

    @Query("DELETE FROM sticky_notes") suspend fun clearStickyNotes()

    @Query("SELECT * FROM questions ORDER BY id") fun questions(): Flow<List<QuestionRow>>

    @Query("SELECT * FROM progress") fun progress(): Flow<List<Progress>>

    @Query("SELECT * FROM attempts ORDER BY timestamp DESC, id DESC LIMIT 100")
    fun attempts(): Flow<List<Attempt>>

    @Query("SELECT * FROM ai ORDER BY created") fun ai(): Flow<List<AiEntry>>

    @Query("SELECT * FROM questions ORDER BY id") suspend fun allQuestions(): List<QuestionRow>

    @Query("SELECT * FROM progress") suspend fun allProgress(): List<Progress>

    @Query("SELECT * FROM attempts") suspend fun allAttempts(): List<Attempt>

    @Query("SELECT * FROM ai") suspend fun allAi(): List<AiEntry>

    @Query("SELECT * FROM progress WHERE questionId=:id")
    suspend fun getProgress(id: String): Progress?

    @Query("SELECT * FROM ai WHERE cacheKey=:key") suspend fun cached(key: String): AiEntry?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addQuestions(rows: List<QuestionRow>)

    @Update suspend fun updateQuestions(rows: List<QuestionRow>)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putProgress(row: Progress)

    @Insert suspend fun addAttempt(row: Attempt)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAi(row: AiEntry)

    @Query("DELETE FROM questions") suspend fun clearQuestions()

    @Query("DELETE FROM progress") suspend fun clearProgress()

    @Query("DELETE FROM attempts") suspend fun clearAttempts()

    @Query("DELETE FROM ai") suspend fun clearAi()
}

@Database(
    entities =
        [
            QuestionRow::class,
            ExamTermination::class,
            Progress::class,
            Attempt::class,
            AiEntry::class,
            StickyNote::class,
            LearningSettings::class,
            PersonalAnswer::class,
            QuestionPersonal::class,
            AiSessionRow::class, AiMessageRow::class, AiRunRow::class, AiCommandRow::class, AiCacheRow::class,
        ],
    version = 7,
    exportSchema = true,
)
abstract class QuizDatabase : RoomDatabase() {
    internal val examAccess = ExamAccess()
    internal val aiLifecycle = AiStorageLifecycle()
    internal val aiServices by lazy { AiServices(this) }
    internal val quickNotesRepository by lazy {
        QuickNotesRepository(java.io.File(storageContext.filesDir, "quicknotes-v1"))
    }
    internal lateinit var storageContext: Context
    @Suppress("DEPRECATION")
    override fun init(configuration: androidx.room.DatabaseConfiguration) {
        storageContext = configuration.context.applicationContext
        super.init(configuration)
    }
    abstract fun ai(): AiConversationDao
    abstract fun dao(): QuizDao

    companion object {
        val MIGRATION_1_2 =
            object : androidx.room.migration.Migration(1, 2) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE attempts ADD COLUMN assessment TEXT NOT NULL DEFAULT ''"
                    )
                }
            }
        val MIGRATION_2_3 =
            object : androidx.room.migration.Migration(2, 3) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS sticky_notes (id TEXT NOT NULL, title TEXT NOT NULL, content TEXT NOT NULL, created INTEGER NOT NULL, updated INTEGER NOT NULL, PRIMARY KEY(id))"
                    )
                }
            }

        val MIGRATION_3_4 = LearningMigration
        val MIGRATION_4_5 = ExamMigration
        val MIGRATION_5_6 = AiConversationMigration
        internal val MIGRATION_6_7 = StickyNoteSourceMigration

        fun open(context: Context, name: String = "embeddedquiz.db"): QuizDatabase {
            CategoryMap.load(context)
            return Room.databaseBuilder(context, QuizDatabase::class.java, name)
                .openHelperFactory(PreservingOpenHelperFactory())
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                .build()
        }
    }
}

object BankCodec {
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    fun parseQuestion(o: JSONObject): Question {
        val kind = Kind.valueOf(o.getString("kind"))
        val options =
            (o.optJSONArray("options") ?: JSONArray()).objects().map {
                Option(it.getString("key"), it.getString("text"))
            }
        val a = o.optJSONArray("correct") ?: JSONArray()
        val correct = (0 until a.length()).map { a.getString(it) }.toSet()
        val q =
            Question(
                o.getString("id"),
                o.getString("stem"),
                kind,
                o.getString("source"),
                o.optString("company", "未注明"),
                o.optString("category", "未分类"),
                o.optString("difficulty", "未标注"),
                options,
                o.optString("answer"),
                correct,
                o.optString("explanation"),
                o.optBoolean("reviewRequired", true),
                (o.optJSONArray("images") ?: JSONArray()).objects().map {
                    Picture(it.getString("name"), it.getString("data"))
                },
                o.optString("examples"),
                o.optString("constraints"),
                o.optString("approach"),
                o.optString("referenceCode"),
                o.optString("locator"),
                o.optString("duplicateOf"),
                o.optString("raw"),
            )
        require(
            q.id.isNotBlank() && q.id.length <= 200 && q.stem.isNotBlank() && q.source.isNotBlank()
        ) {
            "题目 ID、题干或来源不完整"
        }
        require(options.map { it.key }.distinct().size == options.size) { "选项编号重复" }
        require(!q.objective || q.reviewRequired || options.size >= 2) { "可判分客观题至少需要两个选项" }
        require(correct.all { key -> options.any { it.key == key } }) { "答案引用不存在的选项" }
        if (q.objective && !q.reviewRequired) {
            require(correct.isNotEmpty()) { "可判分客观题必须有标准答案" }
            require(kind == Kind.MULTIPLE || correct.size == 1) { "单选/判断只能有一个答案" }
        }
        require(q.images.all { it.data.length <= 8_000_000 }) { "单张图片过大" }
        return q
    }

    fun parseBank(text: String, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<QuestionRow> {
        val root = JSONObject(text)
        require(root.getInt("schemaVersion") == 1) { "题库格式版本不支持" }
        val arr = root.getJSONArray("questions")
        require(arr.length() in 1..20000) { "题库必须包含 1–20000 题" }
        val rows =
            arr.objects().mapIndexed { index, it ->
                QuestionIndex.row(parseQuestion(it), it.toString()).also {
                    onProgress(index + 1, arr.length())
                }
            }
        require(rows.map { it.id }.distinct().size == rows.size) { "题库 ID 重复" }
        return rows
    }
}
