package cn.embeddedquiz

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "questions")
data class QuestionRow(@PrimaryKey val id: String, val json: String)

@Entity(tableName = "sticky_notes")
data class StickyNote(
    @PrimaryKey val id: String,
    val title: String = "",
    val content: String = "",
    val created: Long,
    val updated: Long,
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

@Entity(tableName = "attempts")
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
)

@Entity(tableName = "ai")
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
)

@Dao
interface QuizDao {
    @Query("SELECT * FROM sticky_notes ORDER BY updated DESC, id")
    fun stickyNotes(): Flow<List<StickyNote>>

    @Query("SELECT * FROM sticky_notes ORDER BY updated DESC, id")
    suspend fun allStickyNotes(): List<StickyNote>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putStickyNote(row: StickyNote)

    @Query("DELETE FROM sticky_notes WHERE id=:id") suspend fun deleteStickyNote(id: String)

    @Query("DELETE FROM sticky_notes") suspend fun clearStickyNotes()

    @Query("SELECT * FROM questions ORDER BY id") fun questions(): Flow<List<QuestionRow>>

    @Query("SELECT * FROM progress") fun progress(): Flow<List<Progress>>

    @Query("SELECT * FROM attempts ORDER BY timestamp DESC, id DESC")
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
        [QuestionRow::class, Progress::class, Attempt::class, AiEntry::class, StickyNote::class],
    version = 3,
    exportSchema = true,
)
abstract class QuizDatabase : RoomDatabase() {
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

        fun open(context: Context, name: String = "embeddedquiz.db") =
            Room.databaseBuilder(context, QuizDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
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

    fun parseBank(text: String): List<QuestionRow> {
        val root = JSONObject(text)
        require(root.getInt("schemaVersion") == 1) { "题库格式版本不支持" }
        val arr = root.getJSONArray("questions")
        require(arr.length() in 1..20000) { "题库必须包含 1–20000 题" }
        val rows = arr.objects().map { QuestionRow(parseQuestion(it).id, it.toString()) }
        require(rows.map { it.id }.distinct().size == rows.size) { "题库 ID 重复" }
        return rows
    }
}
