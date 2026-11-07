package cn.embeddedquiz

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PersonalMarksContractTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private suspend fun withRepo(action: suspend (QuizRepository) -> Unit) {
        val name = "marks-${System.nanoTime()}.db"
        val db = QuizDatabase.open(context, name)
        try {
            val repo = QuizRepository(db)
            repo.importBank("""{"schemaVersion":1,"questions":[{"id":"a","stem":"题目","source":"测试","kind":"SHORT"}]}""")
            action(repo)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun createsDefaultMetadataAndClearsDifficulty() = runBlocking {
        withRepo { repo ->
            repo.savePerceivedDifficulty("a", "难")
            assertEquals(QuestionPersonal("a", perceivedDifficulty = "难"), repo.dao.personalMetadata("a"))
            repo.savePerceivedDifficulty("a", "")
            assertEquals(QuestionPersonal("a"), repo.dao.personalMetadata("a"))
        }
    }

    @Test
    fun preservesVerifiedSolutionProgressNotesAndBackupRepresentation() = runBlocking {
        withRepo { repo ->
            val original = QuestionPersonal("a", "旧标签", "易", "return 0;", true)
            val progress = Progress("a", seen = 3, favorite = true, code = "draft", notes = "题目笔记")
            repo.dao.putPersonalMetadata(original)
            repo.dao.putProgress(progress)
            repo.savePerceivedDifficulty("a", "中")
            val saved = repo.dao.personalMetadata("a")!!
            assertEquals(original.copy(perceivedDifficulty = "中"), saved)
            assertEquals(progress, repo.dao.getProgress("a"))
            assertEquals(saved, LearningCodec.parseMetadata(LearningCodec.metadata(saved)))
            assertEquals(listOf("a"), repo.search(BrowseFilter(search = "旧标签 题目笔记"), false, false).map { it.id })
            assertTrue(repo.dao.allAttempts().isEmpty())
        }
    }

    @Test
    fun concurrentQuestionNotesAndDifficultyWritesDoNotOverwriteEachOther() = runBlocking {
        withRepo { repo ->
            repeat(12) { index ->
                coroutineScope {
                    val notes = async(Dispatchers.IO) { repo.update("a") { it.copy(notes = "笔记$index") } }
                    val difficulty = async(Dispatchers.IO) { repo.savePerceivedDifficulty("a", "难") }
                    awaitAll(notes, difficulty)
                }
                assertEquals(QuestionPersonal("a", perceivedDifficulty = "难"), repo.dao.personalMetadata("a"))
                assertEquals("笔记$index", repo.dao.getProgress("a")!!.notes)
            }
        }
    }

    @Test
    fun invalidWritesLeaveExistingMetadataUnchangedAndCreateNoOrphans() = runBlocking {
        withRepo { repo ->
            repo.dao.putPersonalMetadata(QuestionPersonal("a", tags = "旧数据保留"))
            val before = repo.dao.allPersonalMetadata()
            assertTrue(runCatching { repo.savePerceivedDifficulty("a", "未设置") }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { repo.savePerceivedDifficulty("missing", "易") }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { repo.savePerceivedDifficulty("", "易") }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(before, repo.dao.allPersonalMetadata())
        }
    }

    @Test
    fun savingOnlySolutionPreservesLatestDifficultyLegacyTagsAndQuestionNotes() = runBlocking {
        withRepo { repo ->
            val original = QuestionPersonal("a", "旧标签,生命周期", "易", "old solution", true)
            val progress = Progress("a", seen = 2, notes = "每题笔记", code = "draft", favorite = true)
            repo.dao.putPersonalMetadata(original)
            repo.dao.putProgress(progress)
            // The editor may still hold 'original'; the save must read the updated row instead.
            repo.savePerceivedDifficulty("a", "难")
            repo.saveVerifiedSolution("a", "    return 42;\n", true)
            assertEquals(
                original.copy(perceivedDifficulty = "难", verifiedCode = "    return 42;\n"),
                repo.dao.personalMetadata("a"),
            )
            assertEquals(progress, repo.dao.getProgress("a"))
            assertTrue(repo.dao.allAttempts().isEmpty())
        }
    }

    @Test
    fun concurrentSolutionAndDifficultyWritesPreserveBothResultsAndLegacyTags() = runBlocking {
        withRepo { repo ->
            repo.dao.putPersonalMetadata(QuestionPersonal("a", tags = "只读旧标记"))
            repeat(12) { index ->
                val difficulty = if (index % 2 == 0) "难" else "易"
                coroutineScope {
                    val gate = CompletableDeferred<Unit>()
                    val solution = async(Dispatchers.IO) {
                        gate.await()
                        repo.saveVerifiedSolution("a", "solution $index", true)
                    }
                    val perceived = async(Dispatchers.IO) {
                        gate.await()
                        repo.savePerceivedDifficulty("a", difficulty)
                    }
                    gate.complete(Unit)
                    awaitAll(solution, perceived)
                }
                assertEquals(
                    QuestionPersonal("a", "只读旧标记", difficulty, "solution $index", true),
                    repo.dao.personalMetadata("a"),
                )
            }
        }
    }

    @Test
    fun invalidSolutionsRollbackAndMissingQuestionsCreateNoMetadata() = runBlocking {
        withRepo { repo ->
            val original = QuestionPersonal("a", "旧标签", "中", "keep", true)
            repo.dao.putPersonalMetadata(original)
            listOf("", " \n\t").forEach { blank ->
                assertTrue(
                    runCatching { repo.saveVerifiedSolution("a", blank, true) }.exceptionOrNull()
                        is IllegalArgumentException
                )
            }
            assertTrue(
                runCatching { repo.saveVerifiedSolution("missing", "code", true) }.exceptionOrNull()
                    is IllegalArgumentException
            )
            assertTrue(
                runCatching { repo.saveVerifiedSolution(" ", "code", false) }.exceptionOrNull()
                    is IllegalArgumentException
            )
            assertEquals(listOf(original), repo.dao.allPersonalMetadata())
            repo.dao.clearPersonalMetadata()
            assertTrue(runCatching { repo.saveVerifiedSolution("a", "", true) }.isFailure)
            assertTrue(repo.dao.allPersonalMetadata().isEmpty())
        }
    }

    @Test
    fun solutionCanBeSavedUnverifiedAndClearedWithoutChangingDifficulty() = runBlocking {
        withRepo { repo ->
            repo.saveVerifiedSolution("a", "  draft solution  ", false)
            assertEquals(
                QuestionPersonal("a", verifiedCode = "  draft solution  "),
                repo.dao.personalMetadata("a"),
            )
            repo.savePerceivedDifficulty("a", "中")
            repo.saveVerifiedSolution("a", "confirmed", true)
            repo.saveVerifiedSolution("a", "", false)
            assertEquals(QuestionPersonal("a", perceivedDifficulty = "中"), repo.dao.personalMetadata("a"))
        }
    }

    @Test
    fun legacyMetadataAndVersionThreeBackupRoundTripThroughIncrementalEdits() = runBlocking {
        withRepo { repo ->
            val legacy = QuestionPersonal("a", "旧标签,100%_中文", "难", "    legacy code\n", true)
            val global = StickyNote("global", "全局标签", "独立内容", 1, 2)
            repo.dao.putPersonalMetadata(legacy)
            repo.dao.putStickyNote(global)
            assertEquals(7, repo.db.openHelper.writableDatabase.version)
            assertEquals(legacy, LearningCodec.parseMetadata(LearningCodec.metadata(legacy)))
            val oldVersionThree = JSONObject(repo.backup()).asLegacyBackup(3).toString()
            repo.dao.clearPersonalMetadata()
            repo.restore(oldVersionThree)
            assertEquals(legacy, repo.dao.personalMetadata("a"))
            repo.saveVerifiedSolution("a", "new solution", false)
            repo.savePerceivedDifficulty("a", "易")
            val updated = legacy.copy(perceivedDifficulty = "易", verifiedCode = "new solution", codeVerified = false)
            val exported = repo.backup()
            val json = JSONObject(exported)
            assertEquals(5, json.getInt("backupVersion"))
            assertEquals(legacy.tags, json.getJSONArray("questionPersonal").getJSONObject(0).getString("tags"))
            repo.dao.clearPersonalMetadata()
            repo.dao.clearStickyNotes()
            repo.restore(exported)
            assertEquals(updated, repo.dao.personalMetadata("a"))
            assertEquals(listOf(global), repo.dao.allStickyNotes())
            assertEquals(
                listOf("a"),
                repo.search(BrowseFilter(search = "旧标签"), false, false).map { it.id },
            )
            assertTrue(repo.search(BrowseFilter(search = "独立内容"), false, false).isEmpty())
            assertEquals(listOf(global), repo.searchNotes("独立内容"))
        }
    }

    @Test
    fun versionOneAndTwoBackupsKeepExistingLegacyMetadataAndSolution() = runBlocking {
        withRepo { repo ->
            val legacy = QuestionPersonal("a", "旧标签", "难", "legacy", true)
            val global = StickyNote("global", "全局标签", "独立内容", 1, 2)
            repo.dao.putPersonalMetadata(legacy)
            repo.dao.putStickyNote(global)
            repo.saveVerifiedSolution("a", "new solution", true)
            val expected = legacy.copy(verifiedCode = "new solution")
            val template = repo.backup()
            listOf(1, 2).forEach { version ->
                val old = JSONObject(template).asLegacyBackup(version)
                old.remove("learningSettings")
                old.remove("personalAnswers")
                old.remove("questionPersonal")
                if (version == 1) old.remove("stickyNotes")
                repo.restore(old.toString())
                assertEquals(expected, repo.dao.personalMetadata("a"))
                assertEquals(listOf(global), repo.dao.allStickyNotes())
            }
        }
    }
}
