package cn.embeddedquiz

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExamMigrationTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()

    @Test fun versionFourToFivePreservesEveryExistingTableValueAndCreatesUniqueLedger() = runBlocking {
        val name = "exam-migration-${UUID.randomUUID()}.db"
        val schema = JSONObject(File("schemas/cn.embeddedquiz.QuizDatabase/4.json").readText()).getJSONObject("database")
        val entities = schema.getJSONArray("entities")
        val tables = (0 until entities.length()).map { entities.getJSONObject(it).getString("tableName") }
        val raw = app.openOrCreateDatabase(name, 0, null)
        val expected = linkedMapOf<String, List<List<String?>>>()
        try {
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                raw.execSQL(entity.getString("createSql").replace("\u0024{TABLE_NAME}", table))
                val indices = entity.getJSONArray("indices")
                for (j in 0 until indices.length()) raw.execSQL(indices.getJSONObject(j).getString("createSql").replace("\u0024{TABLE_NAME}", table))
                // Seed every column (including nullable values), without changing historical schema.
                val fields = entity.getJSONArray("fields")
                val names = (0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }
                val args = (0 until fields.length()).map { n ->
                    val field = fields.getJSONObject(n)
                    when (field.getString("affinity")) {
                        "INTEGER" -> 1
                        "REAL" -> 2.5
                        else -> "preserve-$table-${names[n]}"
                    }
                }
                raw.execSQL("INSERT INTO `$table` (${names.joinToString { "`$it`" }}) VALUES (${names.joinToString { "?" }})", args.toTypedArray())
                raw.rawQuery("SELECT * FROM `$table`", null).use { cursor ->
                    val rows = mutableListOf<List<String?>>()
                    while (cursor.moveToNext()) rows += (0 until cursor.columnCount).map { cursor.getString(it) }
                    expected[table] = rows
                }
            }
            raw.version = 4
        } finally { raw.close() }
        val db = QuizDatabase.open(app, name)
        try {
            val sql = db.openHelper.writableDatabase
            assertEquals(6, sql.version)
            for (table in tables) assertEquals("all fields in $table", expected[table], rows(sql, table))
            val terminal = ExamTermination("test-session", "ABANDONED", null, null, null, null, null, 123, 7, null)
            db.dao().addExamTermination(terminal)
            assertEquals(terminal, db.dao().examTermination("test-session"))
            assertTrue(runCatching { db.dao().addExamTermination(terminal) }.isFailure)
            db.close()
            val reopened = QuizDatabase.open(app, name)
            try {
                assertEquals(terminal, reopened.dao().examTermination("test-session"))
                for (table in tables) assertEquals(expected[table], rows(reopened.openHelper.readableDatabase, table))
            } finally { reopened.close() }
        } finally { db.close(); app.deleteDatabase(name) }
    }
    private fun rows(db: SupportSQLiteDatabase, table: String): List<List<String?>> =
        db.query("SELECT * FROM `$table`").use { cursor ->
            buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { cursor.getString(it) }) }
        }
}