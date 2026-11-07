package cn.embeddedquiz

import androidx.room.*

@Entity(tableName = "sticky_notes")
data class LegacyStickyNote(@PrimaryKey val id: String, val title: String, val content: String, val created: Long, val updated: Long)

@Dao
interface AiLegacyV5Dao {
    @Insert suspend fun addQuestions(rows: List<QuestionRow>)
    @Update suspend fun updateQuestions(rows: List<QuestionRow>)
    @Query("SELECT * FROM questions WHERE id=:id") suspend fun question(id: String): QuestionRow?
    @Insert suspend fun putProgress(row: Progress)
    @Insert suspend fun putAi(row: AiEntry)
    @Insert suspend fun putStickyNote(row: LegacyStickyNote)
    @Insert suspend fun putSettings(row: LearningSettings)
    @Insert suspend fun putPersonalMetadata(row: QuestionPersonal)
    @Insert suspend fun putPersonalAnswer(row: PersonalAnswer)
    @Insert suspend fun addAttempt(row: Attempt)
    @Insert suspend fun addExamTermination(row: ExamTermination)
}
