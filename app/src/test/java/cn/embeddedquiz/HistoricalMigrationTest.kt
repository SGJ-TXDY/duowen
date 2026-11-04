package cn.embeddedquiz

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HistoricalMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val valid = """ {"id":"legacy","stem":"legacy question","source":"old source","category":"1 C语言基础与内存管理","kind":"SINGLE","options":[{"key":"A","text":"yes"},{"key":"B","text":"no"}],"correct":["A"],"answer":"original answer","explanation":"original explanation","reviewRequired":false} """
    private data class Snapshot(val table: String, val columns: List<String>, val rows: List<List<String?>>)

    private fun seed(version: Int, raw: String): Pair<String, List<Snapshot>> {
        val name = "t3-${System.nanoTime().toString(36)}.db"
        val schema = JSONObject(File("schemas/cn.embeddedquiz.QuizDatabase/$version.json").readText()).getJSONObject("database")
        val entities = schema.getJSONArray("entities")
        val db = context.openOrCreateDatabase(name, 0, null)
        val snapshots = mutableListOf<Snapshot>()
        try {
            for (n in 0 until entities.length()) {
                val entity = entities.getJSONObject(n)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\u0024{TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                if (indices != null) for (i in 0 until indices.length())
                    db.execSQL(indices.getJSONObject(i).getString("createSql").replace("\u0024{TABLE_NAME}", table))
                val fields = entity.getJSONArray("fields")
                val columns = (0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }
                val values = (0 until fields.length()).map { index ->
                    val field = fields.getJSONObject(index)
                    val column = columns[index]
                    when {
                        table == "questions" && column == "id" -> "legacy"
                        table == "questions" && column == "json" -> raw
                        column == "questionId" -> "legacy"
                        column == "correct" -> null
                        column == "assessment" -> ""
                        column == "feedback" -> "GOOD"
                        field.getString("affinity") == "INTEGER" -> 1
                        field.getString("affinity") == "REAL" -> 2.5
                        else -> "preserve-$table-$column"
                    }
                }
                db.execSQL("INSERT INTO `$table` (${columns.joinToString { "`$it`" }}) VALUES (${columns.joinToString { "?" }})", values.toTypedArray())
                val rows = db.rawQuery("SELECT ${columns.joinToString { "`$it`" }} FROM `$table`", null).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(columns.indices.map { cursor.getString(it) }) }
                }
                snapshots += Snapshot(table, columns, rows)
            }
            db.version = version
        } finally { db.close() }
        return name to snapshots
    }

    @Test fun versionsOneTwoThreePreserveEveryOriginalField() = runBlocking {
        for (version in 1..3) {
            val (name, snapshots) = seed(version, valid)
            val db = QuizDatabase.open(context, name)
            try {
                assertEquals(1, db.dao().questionCount())
                val sql = db.openHelper.readableDatabase
                assertEquals(5, sql.version)
                for (snapshot in snapshots) {
                    sql.query("SELECT ${snapshot.columns.joinToString { "`$it`" }} FROM `${snapshot.table}`").use { cursor ->
                        val actual = buildList { while (cursor.moveToNext()) add(snapshot.columns.indices.map { cursor.getString(it) }) }
                        assertEquals("v$version/${snapshot.table}: all original fields", snapshot.rows, actual)
                    }
                }
                val q = QuizRepository(db).question("legacy")!!
                assertEquals("legacy question", q.stem)
                assertEquals(Kind.SINGLE, q.kind)
                val row = db.dao().question("legacy")!!
                assertEquals(valid, row.json)
                assertEquals("C语言与内存", row.normalizedCategory)
                assertEquals(q.contentHash, row.contentHash)
                assertEquals("A,B", row.optionKeys)
                assertFalse(row.reviewRequired)
                assertNotNull(db.dao().getSettings())
            } finally { db.close(); context.deleteDatabase(name) }
        }
    }

    @Test fun invalidHistoryRollsBackAllMigrationPathsWithoutDeletingRows() = runBlocking {
        val invalid = listOf("{", JSONObject(valid).put("kind", "UNKNOWN_OLD_KIND").toString(),
            JSONObject(valid).put("correct", org.json.JSONArray().put("Z")).toString(),
            JSONObject(valid).put("id", "different-id").toString(),
            JSONObject(valid).put("options", org.json.JSONArray().put(JSONObject().put("key", "A").put("text", "one"))
                .put(JSONObject().put("key", "A").put("text", "duplicate"))).toString())
        for (version in 1..3) for (raw in invalid) {
            val (name, snapshots) = seed(version, raw)
            val db = QuizDatabase.open(context, name)
            try { assertTrue(runCatching { db.dao().questionCount() }.isFailure) }
            finally { db.close() }
            val original = context.openOrCreateDatabase(name, 0, null)
            try {
                assertEquals(version, original.version)
                for (snapshot in snapshots) {
                    original.rawQuery("SELECT ${snapshot.columns.joinToString { "`$it`" }} FROM `${snapshot.table}`", null).use { cursor ->
                        val actual = buildList { while (cursor.moveToNext()) add(snapshot.columns.indices.map { cursor.getString(it) }) }
                        assertEquals(snapshot.rows, actual)
                    }
                }
                original.rawQuery("PRAGMA table_info(questions)", null).use { assertEquals(2, it.count) }
            } finally { original.close(); context.deleteDatabase(name) }
        }
    }

    @Test fun historicalReaderMatchesValidDataAndExternalImportStaysStrict() {
        val parsed = HistoricalQuestionV3.read("legacy", valid)
        assertEquals(BankCodec.parseQuestion(JSONObject(valid)), parsed)
        assertEquals(setOf("A"), parsed.correct)
        assertFalse(parsed.reviewRequired)
        for (bad in listOf(JSONObject(valid).put("kind", "UNKNOWN").toString(),
            JSONObject(valid).put("correct", org.json.JSONArray().put("Z")).toString())) {
            assertTrue(runCatching { HistoricalQuestionV3.read("legacy", bad) }.isFailure)
            assertTrue(runCatching {
                BankCodec.parseBank(JSONObject().put("schemaVersion", 1).put("questions", org.json.JSONArray().put(JSONObject(bad))).toString())
            }.isFailure)
        }
    }
}