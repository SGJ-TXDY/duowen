package cn.embeddedquiz

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class AiProductionMigrationTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private fun name()="m-"+UUID.randomUUID().toString().take(8)+".db"
    @Test fun realProductionFiveToEightKeepsOldLearningHistoryAndRawJson()=runBlocking {
        val name=name();val old=AiLegacyV5TestDb.open(context,name)
        val raw=""" { "id":"c","kind":"SHORT","stem":"original","source":"fixture" } """
        try {
            old.dao().addQuestions(listOf(QuestionRow("c",raw)))
            old.dao().putProgress(Progress("c",seen=4,notes="keep old notes"))
            old.dao().putAi(AiEntry("old","c",DeepSeekClient.FLASH,"p","old reply",10,2,0,0.1,1))
            old.dao().putStickyNote(LegacyStickyNote("n","old title","old body",1,2))
            old.dao().putSettings(LearningSettings(theme="DARK"))
            old.dao().putPersonalMetadata(QuestionPersonal("c",tags="legacy tags"))
            old.dao().putPersonalAnswer(PersonalAnswer("c","A","legacy answer","hash",1))
            old.dao().addAttempt(Attempt(questionId="c",timestamp=1,day=0,response="old answer",correct=null,feedback="GOOD",mode="legacy",wasNew=true))
            old.dao().addExamTermination(ExamTermination("e","ABANDONED",null,null,null,null,null,1,1,null))
        } finally { old.close() }
        val before=context.openOrCreateDatabase(name,0,null).use { sql ->
            (AiLegacyBackupCodec.tables.values+"exam_terminations").associateWith { table -> sql.rawQuery("SELECT * FROM `$table`",null).use { c ->
                buildList { while(c.moveToNext()) add((0 until c.columnCount).map { c.getString(it) }) }
            } }
        }
        val db=QuizDatabase.open(context,name)
        try {
            StartupStorageCheck.verify(db);assertEquals(8,db.openHelper.readableDatabase.version)
            for((table,expected) in before) {
                val columns = if (table == "sticky_notes") "id,title,content,created,updated" else "*"
                db.openHelper.readableDatabase.query("SELECT $columns FROM `$table`").use { c ->
                    val actual = buildList { while (c.moveToNext()) add((0 until c.columnCount).map { c.getString(it) }) }
                    assertEquals(table, expected, actual)
                }
            }
            assertNull(db.dao().allStickyNotes().single().sourceRequestId)
            assertEquals(raw,db.dao().question("c")!!.json)
            assertEquals(AiDataset(),AiStore(db,db.ai(),{null}).dataset())
        } finally { db.close();context.deleteDatabase(name) }
    }
    @Test fun roomSixToEightPreservesExistingConversationAndAddsNullableNoteSource() = runBlocking {
        val name = name()
        val old = AiLegacyV5TestDb.open(context, name)
        val raw = """{"id":"q","kind":"SHORT","stem":"original","source":"fixture"}"""
        val question = HistoricalQuestionV3.read("q", raw)
        val session = AiSessionRow(UUID.randomUUID().toString(), question.id, question.contentHash,
            questionSnapshot = AiSnapshotCodec.question(question), title = "旧讨论", createdAt = 1, updatedAt = 1)
        try {
            old.dao().putStickyNote(LegacyStickyNote("n", "旧便签", "旧正文", 1, 2))
            val sql = old.openHelper.writableDatabase
            AiConversationMigration.migrate(sql)
            AiSqlAccess.insert(sql, "ai_sessions", AiSqlAccess.normalized("ai_sessions", AiRowCodec.encode(session)))
            sql.version = 6
        } finally { old.close() }

        val db = QuizDatabase.open(context, name)
        try {
            assertEquals(8, db.openHelper.readableDatabase.version)
            assertEquals(listOf(StickyNote("n", "旧便签", "旧正文", 1, 2)), db.dao().allStickyNotes())
            val stored = db.ai().session(session.sessionId)!!
            assertEquals(session, stored.copy(encodedBytes = 0))
            AiStore(db, db.ai(), { null }).audit()
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun productionMigrationSchemaFailureRollsBackWithoutDeletingOriginal() {
        val name=name();val old=AiLegacyV5TestDb.open(context,name)
        old.openHelper.writableDatabase.execSQL("INSERT INTO sticky_notes VALUES ('n','original','body',1,1)")
        old.openHelper.writableDatabase.execSQL("CREATE TABLE ai_sessions (broken TEXT)");old.close()
        val db=QuizDatabase.open(context,name)
        try { assertTrue(runCatching { db.openHelper.writableDatabase }.isFailure) } finally { db.close() }
        context.openOrCreateDatabase(name,0,null).use { sql ->
            assertEquals(5,sql.version)
            sql.rawQuery("SELECT title FROM sticky_notes",null).use { assertTrue(it.moveToFirst());assertEquals("original",it.getString(0)) }
            sql.rawQuery("SELECT name FROM sqlite_master WHERE name='ai_messages'",null).use { assertFalse(it.moveToFirst()) }
            sql.rawQuery("PRAGMA table_info(ai_sessions)",null).use { assertTrue(it.moveToFirst());assertEquals("broken",it.getString(1)) }
        }
        context.deleteDatabase(name)
    }
}
