package cn.embeddedquiz

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExamStateHolderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "exam-${System.nanoTime()}.db"
    private lateinit var db: QuizDatabase
    private lateinit var repo: QuizRepository
    private lateinit var holder: ExamStateHolder
    private val ids = (0 until 50).map { "q$it" }

    @Before
    fun setup() = runBlocking {
        db = QuizDatabase.open(context, name)
        repo = QuizRepository(db)
        val questions = JSONArray()
        ids.forEach { id ->
            questions.put(JSONObject().put("id", id).put("stem", "question $id")
                .put("source", "考试测试").put("kind", "SINGLE").put("reviewRequired", false)
                .put("options", JSONArray().put(JSONObject().put("key", "A").put("text", "yes"))
                    .put(JSONObject().put("key", "B").put("text", "no")))
                .put("correct", JSONArray().put("A")))
        }
        repo.importBank(JSONObject().put("schemaVersion", 1).put("questions", questions).toString())
        holder = ExamStateHolder(context.getSharedPreferences(name, 0), repo)
    }

    @After
    fun cleanup() {
        db.close()
        context.deleteDatabase(name)
        context.getSharedPreferences(name, 0).edit().clear().commit()
    }

    @Test
    fun tenOfFiftyOnlyRecordsTenAndPreservesAllUnansweredProgressFields() = runBlocking {
        val preserved = (10 until 30).associate { i ->
            val value = Progress("q$i", seen = 7, correctCount = 4, repetitions = 3,
                interval = 37, ease = 2.1, due = 23456, lapses = 3, wrong = i % 2 == 0,
                favorite = true, flagged = true, code = "code-$i", notes = "notes-$i")
            repo.dao.putProgress(value)
            value.questionId to value
        }
        val choices = (0 until 10).associate { "q$it" to setOf(if (it < 7) "A" else "B") }
        val result = holder.grade(ids, choices)
        assertEquals(ExamResult(50, 10, 40, 7, 14), result)
        val attempts = repo.dao.allAttempts()
        assertEquals(10, attempts.size)
        assertEquals(choices.keys, attempts.map { it.questionId }.toSet())
        assertEquals(7, attempts.count { it.correct == true })
        preserved.forEach { (id, value) -> assertEquals("全字段：$id", value, repo.dao.getProgress(id)) }
        (30 until 50).forEach { assertNull(repo.dao.getProgress("q$it")) }
        (0 until 10).forEach { assertEquals(1, repo.dao.getProgress("q$it")!!.seen) }
        val daily = repo.dao.dailyCounts(LocalDate.now().toEpochDay()).first().single()
        assertEquals(10, daily.answered)
        assertEquals(preserved.values.count { it.wrong } + 3, repo.dao.allProgress().count { it.wrong })
    }

    @Test
    fun allUnansweredIncludingExplicitEmptyCreatesNoLearningRowsOrDailyCounts() = runBlocking {
        assertEquals(ExamResult(50, 0, 50, 0, 0), holder.grade(ids, mapOf("q0" to emptySet())))
        assertTrue(repo.dao.allAttempts().isEmpty())
        assertTrue(repo.dao.allProgress().isEmpty())
        assertTrue(repo.dao.dailyCounts(0).first().isEmpty())
    }

    @Test
    fun allAnsweredHasFullDenominatorAndOriginalLearningRules() = runBlocking {
        assertEquals(ExamResult(50, 50, 0, 50, 100), holder.grade(ids, ids.associateWith { setOf("A") }))
        assertEquals(50, repo.dao.allAttempts().size)
        assertTrue(repo.dao.allProgress().all { it.seen == 1 && it.correctCount == 1 && !it.wrong })
    }

    @Test
    fun multipleMissingChoiceCountsAsWrongButClearedSetIsUnanswered() = runBlocking {
        val row = repo.dao.question("q0")!!
        val multiJson = JSONObject(row.json).put("kind", "MULTIPLE").put("correct", JSONArray().put("A").put("B"))
        db.openHelper.writableDatabase.execSQL("UPDATE questions SET json=? WHERE id='q0'", arrayOf(multiJson.toString()))
        val choices = mutableMapOf("q0" to setOf("A"), "q1" to setOf("A"))
        choices["q1"] = emptySet()
        assertEquals(ExamResult(2, 1, 1, 0, 0), holder.grade(listOf("q0", "q1"), choices))
        assertEquals(false, repo.dao.allAttempts().single().correct)
        assertTrue(repo.dao.getProgress("q0")!!.wrong)
        assertNull(repo.dao.getProgress("q1"))
    }

    @Test
    fun personalAnswerIsUsedForBothExamScoreAndLearningRecord() = runBlocking {
        repo.savePersonalAnswer(repo.question("q0")!!, setOf("B"), "人工核对")
        assertEquals(ExamResult(2, 1, 1, 1, 50), holder.grade(listOf("q0", "q1"), mapOf("q0" to setOf("B"))))
        assertEquals("PERSONAL", repo.dao.allAttempts().single().answerSource)
        assertEquals(true, repo.dao.allAttempts().single().correct)
    }

    @Test
    fun invalidPaperFailsBeforeAnyRecordIncludingUnansweredUngradableQuestion() = runBlocking {
        val row = repo.dao.question("q1")!!
        val invalid = JSONObject(row.json).put("reviewRequired", true).toString()
        db.openHelper.writableDatabase.execSQL("UPDATE questions SET json=? WHERE id='q1'", arrayOf(invalid))
        for (paper in listOf(emptyList(), listOf("q0", "q0"), listOf("q0", "missing"), listOf("q0", "q1"))) {
            assertTrue(runCatching { holder.grade(paper, mapOf("q0" to setOf("A"))) }.isFailure)
            assertTrue(repo.dao.allAttempts().isEmpty())
            assertTrue(repo.dao.allProgress().isEmpty())
        }
    }

    @Test
    fun secondInsertFailureRollsBackWholeExamAndPreservesExistingLearning() = runBlocking {
        repo.record(repo.question("q0")!!, "B", setOf("B"), "旧练习")
        val beforeProgress = repo.dao.allProgress()
        val beforeAttempts = repo.dao.allAttempts()
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_exam_insert BEFORE INSERT ON attempts
            WHEN NEW.questionId='q1' BEGIN SELECT RAISE(ABORT, 'injected exam insert failure'); END""")
        assertTrue(runCatching { holder.grade(listOf("q0", "q1"), mapOf("q0" to setOf("A"), "q1" to setOf("A"))) }.isFailure)
        assertEquals(beforeProgress, repo.dao.allProgress())
        assertEquals(beforeAttempts, repo.dao.allAttempts())
    }

    @Test
    fun ordinaryPracticeEmptyAndManualAssessmentSemanticsRemainUnchanged() = runBlocking {
        repo.record(repo.question("q0")!!, "", emptySet(), "顺序练习")
        assertEquals(false, repo.dao.allAttempts().single().correct)
        assertEquals(1, repo.dao.getProgress("q0")!!.lapses)
        val base = repo.question("q1")!!.copy(kind = Kind.SHORT, reviewRequired = true, correct = emptySet())
        for (assessment in Assessment.entries) {
            repo.record(base, "自评", emptySet(), "顺序练习", assessment = assessment)
            val latest = repo.dao.allAttempts().last()
            assertEquals(assessment.name, latest.assessment)
            assertEquals(assessment == Assessment.CORRECT, latest.correct)
        }
    }
}
