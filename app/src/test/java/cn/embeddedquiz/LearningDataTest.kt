package cn.embeddedquiz

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LearningDataTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val bank =
        """{"schemaVersion":1,"questions":[{"id":"a","stem":"pointer first 100%","source":"测试","category":"1 C语言基础与内存管理","kind":"SINGLE","reviewRequired":false,"options":[{"key":"A","text":"stack variable"},{"key":"B","text":"heap memory"}],"answer":"heap","correct":["A"]},{"id":"b","stem":"其他主观题","source":"测试","category":"1 C语言基础与内存管理","kind":"SHORT"}]}"""

    private suspend fun withRepo(action: suspend (QuizRepository) -> Unit) {
        val name = "learning-${System.nanoTime()}.db"
        val db = QuizDatabase.open(context, name)
        try {
            val repo = QuizRepository(db)
            repo.importBank(bank)
            action(repo)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun migrationThreeToFourKeepsRawJsonNotesHistoryAndIndexes() = runBlocking {
        val name = "migration4-${System.nanoTime()}.db"
        val raw = JSONObject(bank).getJSONArray("questions").getJSONObject(0).toString()
        val schema =
            JSONObject(java.io.File("schemas/cn.embeddedquiz.QuizDatabase/3.json").readText())
                .getJSONObject("database")
        val legacy = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        val entities = schema.getJSONArray("entities")
        for (index in 0 until entities.length()) {
            val entity = entities.getJSONObject(index)
            legacy.execSQL(
                entity
                    .getString("createSql")
                    .replace("\u0024{TABLE_NAME}", entity.getString("tableName"))
            )
        }
        legacy.execSQL("INSERT INTO questions VALUES (?,?)", arrayOf("a", raw))
        legacy.execSQL("INSERT INTO progress VALUES ('a',2,1,1,1,2.5,100,1,1,1,0,'code','原有笔记')")
        legacy.execSQL("INSERT INTO attempts VALUES (1,'a',1,1,'A',1,'GOOD','旧练习',1,'')")
        legacy.execSQL("INSERT INTO sticky_notes VALUES ('note','标题','内容',1,2)")
        legacy.execSQL(
            "INSERT INTO ai VALUES ('cache','a','deepseek-flash','问题','旧解析',10,20,0,0.1,1)"
        )
        legacy.version = 3
        legacy.close()
        val db = QuizDatabase.open(context, name)
        try {
            val row = db.dao().question("a")!!
            assertEquals(raw, row.json)
            assertEquals("SINGLE", row.kind)
            assertEquals("C语言与内存", row.normalizedCategory)
            assertEquals("A,B", row.optionKeys)
            assertTrue(row.contentHash.matches(Regex("[a-f0-9]{64}")))
            assertEquals("原有笔记", db.dao().getProgress("a")!!.notes)
            assertTrue(db.dao().getProgress("a")!!.favorite)
            assertEquals("内容", db.dao().allStickyNotes().single().content)
            assertEquals("ORIGINAL", db.dao().allAttempts().single().answerSource)
            assertEquals("旧解析", db.dao().allAi().single().response)
            assertEquals(20, db.dao().getSettings()!!.dailyGoal)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun backupThreeRestoresNewDataAndOldBackupsKeepIt() = runBlocking {
        withRepo { repo ->
            val q = repo.question("a")!!
            repo.savePersonalAnswer(q, setOf("B"), "已手动核对")
            val settings =
                LearningSettings(
                    dailyGoal = 35,
                    theme = "DARK",
                    fontSize = "LARGE",
                    notificationPermissionAsked = true,
                )
            val metadata = QuestionPersonal(q.id, "指针,生命周期", "难", "    int main() {}", true)
            repo.dao.putSettings(settings)
            repo.dao.putPersonalMetadata(metadata)
            repo.dao.putStickyNote(StickyNote("note", "标题", "便签", 1, 2))
            val backup = repo.backup()
            assertEquals(5, JSONObject(backup).getInt("backupVersion"))
            repo.dao.clearPersonalAnswers()
            repo.dao.clearPersonalMetadata()
            repo.dao.putSettings(LearningSettings())
            repo.restore(backup)
            assertEquals(settings, repo.dao.getSettings())
            assertEquals(metadata, repo.dao.allPersonalMetadata().single())
            assertEquals(setOf("B"), repo.dao.allPersonalAnswers().single().selected)
            val old = JSONObject(backup).asLegacyBackup(2)
            old.remove("learningSettings")
            old.remove("personalAnswers")
            old.remove("questionPersonal")
            repo.restore(old.toString())
            assertEquals(settings, repo.dao.getSettings())
            assertEquals(metadata, repo.dao.allPersonalMetadata().single())
            old.put("backupVersion", 1).remove("stickyNotes")
            repo.restore(old.toString())
            assertEquals("便签", repo.dao.allStickyNotes().single().content)
            assertEquals(setOf("B"), repo.dao.allPersonalAnswers().single().selected)
            val bad = JSONObject(backup)
            bad.getJSONObject("learningSettings").put("dailyGoal", 0)
            assertTrue(runCatching { repo.restore(bad.toString()) }.isFailure)
            assertEquals(35, repo.dao.getSettings()!!.dailyGoal)
        }
    }

    @Test
    fun personalAnswersTakePrecedenceWithoutChangingOriginalAndExportSafely() = runBlocking {
        withRepo { repo ->
            val q = repo.question("a")!!
            val raw = repo.dao.question(q.id)!!.json
            repo.savePersonalAnswer(q, setOf("B"), "个人确认")
            repo.record(q, "B", setOf("B"), "测试")
            assertTrue(repo.dao.allAttempts().single().correct!!)
            assertEquals("PERSONAL", repo.dao.allAttempts().single().answerSource)
            assertEquals(raw, repo.dao.question(q.id)!!.json)
            assertEquals(setOf("A"), repo.question(q.id)!!.correct)
            val export = repo.exportPersonalAnswers()
            repo.dao.clearPersonalAnswers()
            assertEquals(1, repo.importPersonalAnswers(export))
            val stale = q.copy(stem = "题目内容变化")
            assertFalse(repo.dao.personalAnswer(q.id)!!.validFor(stale))
            assertTrue(runCatching { repo.savePersonalAnswer(q, setOf("Z"), "") }.isFailure)
            val bad = JSONObject(export)
            bad.getJSONArray("answers").getJSONObject(0).put("questionHash", "0".repeat(64))
            assertTrue(runCatching { repo.importPersonalAnswers(bad.toString()) }.isFailure)
            assertEquals(setOf("B"), repo.dao.personalAnswer(q.id)!!.selected)
        }
    }

    @Test
    fun sqlStatisticsSeparateSelfAssessmentAndUseDistinctDailyQuestions() = runBlocking {
        withRepo { repo ->
            val a = repo.question("a")!!
            val b = repo.question("b")!!
            val day = LocalDate.of(2024, 2, 29)
            repo.record(a, "A", setOf("A"), "新题", day)
            repo.record(a, "B", setOf("B"), "到期复习", day)
            repo.record(b, "回答", emptySet(), "新题", day, Assessment.CORRECT)
            repo.record(b, "", emptySet(), "到期复习", day, Assessment.UNKNOWN)
            val daily = repo.dao.dailyCounts(day.toEpochDay()).first().single()
            assertEquals(2, daily.answered)
            assertEquals(2, daily.automatic)
            assertEquals(1, daily.autoCorrect)
            assertEquals(2, daily.manual)
            assertEquals(1, daily.manualCorrect)
            val totals = repo.dao.totals().first()
            assertEquals(4, totals.attempts)
            assertEquals(2, totals.studied)
            val category = repo.dao.categoryCounts().first().single()
            assertEquals("C语言与内存", category.category)
            assertEquals(0, category.mastered)
            val due =
                repo.dao
                    .dueCounts(day.plusDays(1).toEpochDay(), day.plusDays(7).toEpochDay())
                    .first()
            assertEquals(2, due.sumOf { it.count })
            assertEquals(LocalDate.of(2024, 3, 1).toEpochDay(), due.single().day)
        }
    }

    @Test
    fun searchAndTermsCoverOptionsAnswerNotesTagsAndStickyNotesLiterally() = runBlocking {
        withRepo { repo ->
            repo.update("a") { it.copy(notes = "lifetime", code = "char *pointer;") }
            repo.dao.putPersonalMetadata(QuestionPersonal("a", tags = "embedded"))
            repo.dao.putStickyNote(StickyNote("memo", "指针 栈", "生命周期", 1, 1))
            suspend fun search(text: String) =
                repo.search(BrowseFilter(search = text), false, false)
            assertEquals(listOf("a"), search("pointer lifetime").map { it.id })
            assertEquals(listOf("a"), search("variable heap").map { it.id })
            assertEquals(listOf("a"), search("embedded pointer").map { it.id })
            assertEquals(listOf("a"), search("%").map { it.id })
            assertTrue(search("pointer 不存在").isEmpty())
            assertTrue(search("' OR 1=1").isEmpty())
            assertEquals("memo", repo.searchNotes("栈 生命周期").single().id)
            assertTrue(repo.searchNotes("栈 不存在").isEmpty())
        }
    }

    @Test
    fun orderedCodeDraftsKeepLatestWithoutLosingFavoriteAndNotes() = runBlocking {
        withRepo { repo ->
            repo.update("a") { it.copy(favorite = true) }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val drafts = CodeDraftStateHolder(scope, repo)
            try {
                repeat(40) { drafts.update("a", "    int version = $it;", "笔记 $it") }
                drafts.writer.flush()
                val progress = repo.dao.getProgress("a")!!
                assertEquals("    int version = 39;", progress.code)
                assertEquals("笔记 39", progress.notes)
                assertTrue(progress.favorite)
            } finally {
                drafts.shutdown().join()
                scope.cancel()
            }
        }
    }

    @Test
    fun localLogStaysBoundedAndNeverStoresExceptionMessage() {
        val log = LocalErrorLog(context)
        java.io
            .File(context.filesDir, "local-errors.log")
            .writeBytes(ByteArray(600 * 1024) { 'x'.code.toByte() })
        log.record(LogEvent.AI, IllegalStateException("secret-key-and-full-question"))
        assertTrue(
            java.io.File(context.filesDir, "local-errors.log").length() <= LocalErrorLog.MAX_BYTES
        )
        assertFalse(log.read().contains("secret-key-and-full-question"))
        log.clear()
        assertTrue(log.read().isEmpty())
    }
}
