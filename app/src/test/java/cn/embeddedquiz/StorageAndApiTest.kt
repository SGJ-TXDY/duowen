package cn.embeddedquiz

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class StorageAndApiTest {
    private val bank="""{"schemaVersion":1,"questions":[{"id":"test-1","stem":"test","source":"test","kind":"SINGLE","options":[{"key":"A","text":"a"},{"key":"B","text":"b"}],"correct":["A"],"answer":"A","reviewRequired":false}]}"""
    @Test fun closeReopenBackupRestoreAndWrongAnswerScheduling() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="test-${System.nanoTime()}.db"
        var db=QuizDatabase.open(context,name); var repo=QuizRepository(db)
        assertEquals(1,repo.importBank(bank).first)
        assertEquals(1,repo.importBank(bank).second)
        val q=BankCodec.parseQuestion(JSONObject(repo.dao.allQuestions().single().json))
        repo.update(q.id) {it.copy(favorite=true,code="    int x;",notes="memo")}
        repo.dao.putAi(AiEntry("hash",q.id,"deepseek-flash","why","AI explanation",100,20,10,0.0001,1))
        repo.record(q,"B",setOf("B"),"test",LocalDate.of(2026,12,31))
        db.close(); db=QuizDatabase.open(context,name); repo=QuizRepository(db)
        val p=repo.dao.getProgress(q.id)!!
        assertTrue(p.favorite);assertTrue(p.wrong);assertEquals(0,p.repetitions)
        assertEquals(LocalDate.of(2027,1,1).toEpochDay(),p.due);assertEquals(1,repo.dao.allAttempts().size)
        val backup=repo.backup();repo.reset();assertEquals(0,repo.dao.getProgress(q.id)!!.seen)
        repo.restore(backup);assertEquals("    int x;",repo.dao.getProgress(q.id)!!.code)
        assertEquals(1,repo.dao.allAttempts().size)
        assertEquals("AI explanation",repo.dao.cached("hash")!!.response)
        assertEquals("A",BankCodec.parseQuestion(JSONObject(repo.dao.allQuestions().single().json)).answer)
        val malformed=JSONObject(backup);malformed.getJSONArray("progress").getJSONObject(0).put("ease",0)
        assertTrue(runCatching {repo.restore(malformed.toString())}.isFailure)
        assertEquals(1,repo.dao.allAttempts().size)
        assertTrue(runCatching {repo.record(q.copy(kind=Kind.PROGRAMMING),"",emptySet(),"test")}.isFailure)
        db.close();context.deleteDatabase(name);Unit
    }
    @Test fun embeddedBankIsCompleteAndSafe() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val rows=BankCodec.parseBank(context.assets.open("bank.json").bufferedReader().use {it.readText()})
        assertEquals(906,rows.size)
        val qs=rows.map {BankCodec.parseQuestion(JSONObject(it.json))}
        assertEquals(152,qs.count {it.id.startsWith("company-")&&it.answer.isNotBlank()})
        assertEquals(67,qs.count {it.id.startsWith("company-")&&!it.reviewRequired&&it.correct.isNotEmpty()})
        assertEquals(41,qs.count {it.source=="手写代码"&&it.kind==Kind.PROGRAMMING&&it.reviewRequired})
        assertEquals(79,qs.count {!it.daily})
        val yanxuan=qs.filter {it.id.startsWith("yanxuan-")}
        assertEquals(61,yanxuan.size)
        assertTrue(yanxuan.all {it.daily&&Grader.grade(it,it.correct)==true&&Grader.grade(it,emptySet())==false})
        assertEquals(setOf("B"),yanxuan.first {it.id=="yanxuan-03-001"}.correct)
        assertTrue(qs.any {it.images.isNotEmpty()})
        assertFalse(qs.first {it.id=="pute-212"}.daily)
    }
    @Test fun importRejectsBadAnswerReferencesAndDuplicateIds() {
        val root=JSONObject(bank)
        root.getJSONArray("questions").getJSONObject(0).put("correct",org.json.JSONArray().put("Z"))
        assertTrue(runCatching {BankCodec.parseBank(root.toString())}.isFailure)
        val duplicate=JSONObject(bank)
        duplicate.getJSONArray("questions").put(duplicate.getJSONArray("questions").getJSONObject(0))
        assertTrue(runCatching {BankCodec.parseBank(duplicate.toString())}.isFailure)
    }
    @Test fun manualAssessmentPersistsAndDistinguishesUnknown() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="manual-${System.nanoTime()}.db"
        var db=QuizDatabase.open(context,name);var repo=QuizRepository(db)
        repo.importBank(bank)
        val q=BankCodec.parseQuestion(JSONObject(repo.dao.allQuestions().single().json)).copy(reviewRequired=true,correct=emptySet(),answer="")
        val day=LocalDate.of(2026,12,31)
        for(result in listOf(Assessment.UNKNOWN,Assessment.WRONG)) {
            repo.record(q,"",emptySet(),"test",day,result)
            assertTrue(repo.dao.getProgress(q.id)!!.wrong)
            assertEquals(day.plusDays(1).toEpochDay(),repo.dao.getProgress(q.id)!!.due)
        }
        db.close();db=QuizDatabase.open(context,name);repo=QuizRepository(db)
        assertEquals(setOf("UNKNOWN","WRONG"),repo.dao.allAttempts().map {it.assessment}.toSet())
        assertTrue(repo.dao.allAttempts().all {it.correct==false})
        repo.record(q,"my answer",emptySet(),"test",day,Assessment.CORRECT)
        assertFalse(repo.dao.getProgress(q.id)!!.wrong)
        // A standard answer takes precedence: manual assessment cannot override it.
        val standard=BankCodec.parseQuestion(JSONObject(repo.dao.allQuestions().single().json))
        repo.record(standard,"B",setOf("B"),"test",day,Assessment.CORRECT)
        assertTrue(repo.dao.getProgress(q.id)!!.wrong)
        assertEquals(1,repo.dao.getProgress(q.id)!!.correctCount)
        assertEquals("",repo.dao.allAttempts().last().assessment)
        repo.record(standard,"A",setOf("A"),"test",day)
        assertFalse(repo.dao.getProgress(q.id)!!.wrong)
        assertEquals(2,repo.dao.getProgress(q.id)!!.correctCount)
        assertTrue(runCatching {repo.record(q,"",emptySet(),"test")}.isFailure)
        assertEquals("A",BankCodec.parseQuestion(JSONObject(repo.dao.allQuestions().single().json)).answer)
        val backup=repo.backup();repo.reset();repo.restore(backup)
        assertEquals(setOf("UNKNOWN","WRONG","CORRECT",""),repo.dao.allAttempts().map {it.assessment}.toSet())
        val legacy=JSONObject(backup)
        val attempts=legacy.getJSONArray("attempts")
        for(i in 0 until attempts.length()) attempts.getJSONObject(i).remove("assessment")
        repo.restore(legacy.toString());assertTrue(repo.dao.allAttempts().all {it.assessment.isEmpty()})
        db.close();context.deleteDatabase(name);Unit
    }
    @Test fun bundledAnswerUpgradePreservesLearningAndCustomQuestions() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="upgrade-${System.nanoTime()}.db";val db=QuizDatabase.open(context,name);val repo=QuizRepository(db)
        val old=JSONObject(bank);old.getJSONArray("questions").getJSONObject(0).put("answer","").put("correct",org.json.JSONArray()).put("reviewRequired",true)
        repo.importBank(old.toString())
        val q=BankCodec.parseQuestion(JSONObject(repo.dao.allQuestions().single().json))
        repo.record(q,"memo",emptySet(),"test",assessment=Assessment.CORRECT)
        repo.update(q.id) {it.copy(favorite=true,code="code",notes="notes")}
        val progress=repo.dao.getProgress(q.id);val attempts=repo.dao.allAttempts()
        repeat(2) {repo.upgradeBundledBank(bank)}
        assertEquals(progress,repo.dao.getProgress(q.id));assertEquals(attempts,repo.dao.allAttempts())
        assertEquals("A",BankCodec.parseQuestion(JSONObject(repo.dao.allQuestions().single().json)).answer)
        val other=JSONObject(bank);other.getJSONArray("questions").getJSONObject(0).put("stem","different user question")
        repo.upgradeBundledBank(other.toString())
        assertEquals("test",BankCodec.parseQuestion(JSONObject(repo.dao.allQuestions().single().json)).stem)
        db.close();context.deleteDatabase(name);Unit
    }
    @Test fun versionOneDatabaseMigratesWithoutLosingAttempts() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="migration-${System.nanoTime()}.db"
        val schema=JSONObject(java.io.File("schemas/cn.embeddedquiz.QuizDatabase/1.json").readText()).getJSONObject("database")
        val legacy=context.openOrCreateDatabase(name,Context.MODE_PRIVATE,null)
        val entities=schema.getJSONArray("entities")
        for(i in 0 until entities.length()) {
            val entity=entities.getJSONObject(i)
            legacy.execSQL(entity.getString("createSql").replace("\u0024{TABLE_NAME}",entity.getString("tableName")))
        }
        legacy.execSQL("INSERT INTO attempts VALUES (1, 'test-1', 1, 1, 'old answer', NULL, 'GOOD', 'test', 1)")
        legacy.execSQL("INSERT INTO progress VALUES ('test-1', 3, 1, 1, 1, 2.5, 100, 1, 1, 1, 0, 'code', 'notes')")
        legacy.version=1;legacy.close()
        val db=QuizDatabase.open(context,name)
        val a=db.dao().allAttempts().single()
        assertEquals("old answer",a.response);assertEquals("",a.assessment);assertNull(a.correct)
        val p=db.dao().getProgress("test-1")!!
        assertEquals(3,p.seen);assertTrue(p.favorite);assertTrue(p.wrong);assertEquals("notes",p.notes)
        db.close();context.deleteDatabase(name);Unit
    }
    @Test fun apiParsesSuccessAndSanitizesErrors() {
        val server=MockWebServer();server.start()
        try {
            val client=DeepSeekClient(endpoint=server.url("/chat/completions").toString())
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"explanation"}}],"usage":{"prompt_tokens":100,"completion_tokens":20,"prompt_cache_hit_tokens":10}}"""))
            val reply=client.complete("unit-test-key","deepseek-flash","prompt")
            assertEquals("explanation",reply.text);assertEquals(100,reply.input)
            val req=server.takeRequest();assertEquals("Bearer unit-test-key",req.getHeader("Authorization"))
            for(status in listOf(401,402,429,500)) {
                server.enqueue(MockResponse().setResponseCode(status).setBody("do-not-expose-body"))
                val e=runCatching {client.complete("unit-test-key","deepseek-flash","prompt")}.exceptionOrNull()
                assertTrue(e is AiFailure);assertFalse(e!!.message!!.contains("do-not-expose"))
            }
            server.enqueue(MockResponse().setBody("not json"))
            assertTrue(runCatching {client.complete("x","deepseek-flash","p")}.exceptionOrNull() is AiFailure)
            assertTrue(runCatching {client.complete("","deepseek-flash","p")}.exceptionOrNull() is AiFailure)
        } finally {server.shutdown()}
    }
    @Test fun networkTimeoutIsRecoverable() {
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            val http=okhttp3.OkHttpClient.Builder().callTimeout(150,java.util.concurrent.TimeUnit.MILLISECONDS).build()
            val client=DeepSeekClient(http,server.url("/chat/completions").toString())
            val error=runCatching {client.complete("test-key","deepseek-flash","prompt")}.exceptionOrNull()
            assertTrue(error is AiFailure);assertTrue(error!!.message!!.contains("离线刷题"))
        } finally {server.shutdown()}
    }
}
