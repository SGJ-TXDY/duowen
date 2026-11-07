package cn.embeddedquiz

import androidx.room.Database
import androidx.room.RoomDatabase

/** Test-only host; no schema export, no production registration and no overlapping schema name. */
@Database(entities = [QuestionRow::class, ExamTermination::class, Progress::class, Attempt::class,
    AiEntry::class, StickyNote::class, LearningSettings::class, PersonalAnswer::class, QuestionPersonal::class,
    AiSessionRow::class, AiMessageRow::class, AiRunRow::class, AiCommandRow::class, AiCacheRow::class],
    version = 6, exportSchema = false)
abstract class AiFoundationTestDb : RoomDatabase() {
    abstract fun ai(): AiConversationDao
    abstract fun old(): QuizDao
}
