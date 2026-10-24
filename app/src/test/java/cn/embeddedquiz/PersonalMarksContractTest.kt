package cn.embeddedquiz

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
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
}
