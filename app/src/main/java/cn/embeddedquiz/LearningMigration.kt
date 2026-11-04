package cn.embeddedquiz

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.Instant
import java.time.ZoneId


/** 3→4 adds independent personal data and rebuilds only derived question indexes. */
object LearningMigration : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val strings =
            linkedMapOf(
                "kind" to "SHORT",
                "source" to "",
                "category" to "",
                "normalizedCategory" to "",
                "company" to "",
                "difficulty" to "未标注",
                "stem" to "",
                "correctKeys" to "",
                "searchText" to "",
                "contentHash" to "",
                "optionKeys" to "",
            )
        strings.forEach { (name, value) ->
            db.execSQL("ALTER TABLE questions ADD COLUMN $name TEXT NOT NULL DEFAULT '$value'")
        }
        db.execSQL("ALTER TABLE questions ADD COLUMN reviewRequired INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE questions ADD COLUMN hasAnswer INTEGER NOT NULL DEFAULT 0")
        db.query("SELECT id,json FROM questions").use { cursor ->
            val statement =
                db.compileStatement(
                    "UPDATE questions SET kind=?,source=?,category=?,normalizedCategory=?,company=?,difficulty=?,reviewRequired=?,stem=?,correctKeys=?,searchText=?,hasAnswer=?,contentHash=?,optionKeys=? WHERE id=?"
                )
            while (cursor.moveToNext()) {
                val raw = cursor.getString(1)
                val row = HistoricalQuestionV3.index(cursor.getString(0), raw)
                val values =
                    listOf(
                        row.kind,
                        row.source,
                        row.category,
                        row.normalizedCategory,
                        row.company,
                        row.difficulty,
                        row.reviewRequired,
                        row.stem,
                        row.correctKeys,
                        row.searchText,
                        row.hasAnswer,
                        row.contentHash,
                        row.optionKeys,
                        row.id,
                    )
                values.forEachIndexed { index, value ->
                    when (value) {
                        is Boolean -> statement.bindLong(index + 1, if (value) 1 else 0)
                        else -> statement.bindString(index + 1, value.toString())
                    }
                }
                statement.executeUpdateDelete()
                statement.clearBindings()
            }
            statement.close()
        }
        listOf("kind", "source", "normalizedCategory", "company", "reviewRequired").forEach {
            db.execSQL("CREATE INDEX IF NOT EXISTS index_questions_$it ON questions($it)")
        }
        listOf("answerSource", "normalizedCategory", "questionHash").forEach {
            db.execSQL("ALTER TABLE attempts ADD COLUMN $it TEXT NOT NULL DEFAULT ''")
        }
        db.execSQL(
            "UPDATE attempts SET normalizedCategory=COALESCE((SELECT normalizedCategory FROM questions WHERE questions.id=attempts.questionId),''),answerSource=CASE WHEN assessment!='' OR (correct IS NOT NULL AND (SELECT kind FROM questions WHERE questions.id=attempts.questionId) NOT IN ('SINGLE','MULTIPLE','TRUE_FALSE')) THEN 'MANUAL' WHEN correct IS NULL THEN 'UNSCORED' ELSE 'ORIGINAL' END"
        )
        listOf("day", "questionId", "normalizedCategory").forEach {
            db.execSQL("CREATE INDEX IF NOT EXISTS index_attempts_$it ON attempts($it)")
        }
        db.execSQL("ALTER TABLE ai ADD COLUMN contentHash TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE ai ADD COLUMN requestType TEXT NOT NULL DEFAULT 'FOLLOW_UP'")
        db.execSQL("ALTER TABLE ai ADD COLUMN day INTEGER NOT NULL DEFAULT 0")
        db.query("SELECT cacheKey,created FROM ai").use { cursor ->
            while (cursor.moveToNext()) {
                val day =
                    Instant.ofEpochMilli(cursor.getLong(1))
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                        .toEpochDay()
                db.execSQL(
                    "UPDATE ai SET day=? WHERE cacheKey=?",
                    arrayOf(day, cursor.getString(0)),
                )
            }
        }
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_ai_questionId_contentHash_created ON ai(questionId,contentHash,created)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS learning_settings (id INTEGER NOT NULL,dailyGoal INTEGER NOT NULL,theme TEXT NOT NULL,fontSize TEXT NOT NULL,reminderEnabled INTEGER NOT NULL,reminderMinute INTEGER NOT NULL,notificationPermissionAsked INTEGER NOT NULL,lastNotifiedDay INTEGER NOT NULL,PRIMARY KEY(id))"
        )
        db.execSQL("INSERT INTO learning_settings VALUES (1,20,'SYSTEM','MEDIUM',0,1200,0,-1)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS personal_answers (questionId TEXT NOT NULL,choices TEXT NOT NULL,notes TEXT NOT NULL,questionHash TEXT NOT NULL,updated INTEGER NOT NULL,PRIMARY KEY(questionId))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS question_personal (questionId TEXT NOT NULL,tags TEXT NOT NULL,perceivedDifficulty TEXT NOT NULL,verifiedCode TEXT NOT NULL,codeVerified INTEGER NOT NULL,PRIMARY KEY(questionId))"
        )
    }
}
