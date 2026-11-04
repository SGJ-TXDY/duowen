package cn.embeddedquiz

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Entity(tableName = "exam_terminations", indices = [Index(value = ["legacyHash"], unique = true)])
data class ExamTermination(
    @PrimaryKey val sessionId: String,
    val status: String,
    val total: Int?,
    val answered: Int?,
    val unanswered: Int?,
    val correct: Int?,
    val score: Int?,
    val endedAt: Long,
    val revision: Long,
    // Alias for an anonymous pre-v5 snapshot; never the identity of a new exam.
    val legacyHash: String?,
) {
    fun result(): ExamResult? = if (status == "SUBMITTED")
        ExamResult(checkNotNull(total), checkNotNull(answered), checkNotNull(unanswered),
            checkNotNull(correct), checkNotNull(score)) else null
}

object ExamMigration : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `exam_terminations` (`sessionId` TEXT NOT NULL, `status` TEXT NOT NULL, `total` INTEGER, `answered` INTEGER, `unanswered` INTEGER, `correct` INTEGER, `score` INTEGER, `endedAt` INTEGER NOT NULL, `revision` INTEGER NOT NULL, `legacyHash` TEXT, PRIMARY KEY(`sessionId`))")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_exam_terminations_legacyHash` ON `exam_terminations` (`legacyHash`)")
    }
}

/** Shared by repository wrappers of this database; serializes destructive imports and exam claims. */
internal class ExamAccess {
    private val mutex = Mutex()
    @Volatile var active = false
        private set

    fun claim(): Boolean {
        if (!mutex.tryLock()) return false
        return try {
            if (active) false else { active = true; true }
        } finally { mutex.unlock() }
    }
    fun release() { active = false }
    suspend fun <T> replaceLearning(block: suspend () -> T): T = mutex.withLock {
        check(!active) { "请先结束当前考试，再恢复备份或重置学习记录" }
        block()
    }
}