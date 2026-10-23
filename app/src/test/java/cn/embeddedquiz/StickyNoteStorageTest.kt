package cn.embeddedquiz

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class StickyNoteStorageTest {
    @Test fun notesPersistRestoreAndSurviveResetAndOldBackup() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="notes-${System.nanoTime()}.db"
        var db=QuizDatabase.open(context,name)
        try {
            var repo=QuizRepository(db)
            repo.importBank(context.assets.open("bank.json").bufferedReader().use {it.readText()})
            val note=StickyNote("one","指针笔记","第一行\n    保留代码缩进",1,2)
            repo.dao.putStickyNote(note)
            db.close(); db=QuizDatabase.open(context,name); repo=QuizRepository(db)
            assertEquals(note,repo.dao.allStickyNotes().single())
            repo.reset()
            assertEquals(note,repo.dao.allStickyNotes().single())
            val backup=repo.backup()
            repo.dao.putStickyNote(note.copy(content="changed",updated=3))
            repo.dao.putStickyNote(StickyNote("two","临时","text",1,1))
            repo.restore(backup)
            assertEquals(listOf(note),repo.dao.allStickyNotes())
            val malformed=JSONObject(backup)
            malformed.getJSONArray("stickyNotes").getJSONObject(0).put("updated",-1)
            assertTrue(runCatching {repo.restore(malformed.toString())}.isFailure)
            assertEquals(listOf(note),repo.dao.allStickyNotes())
            val old=JSONObject(backup).put("backupVersion",1)
            old.remove("stickyNotes")
            repo.restore(old.toString())
            assertEquals(listOf(note),repo.dao.allStickyNotes())
            repo.dao.deleteStickyNote(note.id)
            assertTrue(repo.dao.allStickyNotes().isEmpty())
        } finally {db.close();context.deleteDatabase(name)}
    }

    @Test fun versionTwoMigrationPreservesExistingLearningData() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="notes-migrate-${System.nanoTime()}.db"
        val schema=JSONObject(java.io.File("schemas/cn.embeddedquiz.QuizDatabase/2.json").readText()).getJSONObject("database")
        val legacy=context.openOrCreateDatabase(name,Context.MODE_PRIVATE,null)
        val entities=schema.getJSONArray("entities")
        for(i in 0 until entities.length()) {
            val entity=entities.getJSONObject(i)
            legacy.execSQL(entity.getString("createSql").replace("\u0024{TABLE_NAME}",entity.getString("tableName")))
        }
        legacy.execSQL("INSERT INTO progress VALUES ('q', 2, 1, 1, 1, 2.5, 100, 1, 1, 1, 0, 'code', 'old notes')")
        legacy.execSQL("INSERT INTO attempts VALUES (1, 'q', 1, 1, 'answer', 0, 'AGAIN', 'test', 1, 'UNKNOWN')")
        legacy.version=2;legacy.close()
        val db=QuizDatabase.open(context,name)
        try {
            assertTrue(db.dao().allStickyNotes().isEmpty())
            assertEquals("old notes",db.dao().getProgress("q")!!.notes)
            assertTrue(db.dao().getProgress("q")!!.favorite)
            assertEquals("UNKNOWN",db.dao().allAttempts().single().assessment)
            val note=StickyNote("new",created=1,updated=1)
            db.dao().putStickyNote(note)
            assertEquals(note,db.dao().allStickyNotes().single())
        } finally {db.close();context.deleteDatabase(name)}
    }
}
