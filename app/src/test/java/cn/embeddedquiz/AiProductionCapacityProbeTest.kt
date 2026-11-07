package cn.embeddedquiz

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class AiProductionCapacityProbeTest {
    @Test fun nearCapacityColdAndFileBackup()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val id={ UUID.randomUUID().toString() };val name="p-"+id().take(8)+".db"
        val queries=java.util.Collections.synchronizedList(mutableListOf<String>())
        val db=androidx.room.Room.databaseBuilder(context,QuizDatabase::class.java,name)
            .setQueryCallback({ sql,_ -> queries.add(sql) },java.util.concurrent.Executor { it.run() }).build()
        val repo=QuizRepository(db)
        println("AI_PRODUCTION_PATH workPathLength=${File(context.cacheDir,"complete-backup").absolutePath.length} oldStagePathLength=${File(File(context.cacheDir,"complete-backup"),"ai-staging-${id()}.db").absolutePath.length}")
        val out=File.createTempFile("prod-backup-",".json",context.cacheDir)
        val loader=ClassLoader.getSystemClassLoader();val factory=Class.forName("java.lang.management.ManagementFactory",true,loader)
        val threads=factory.getMethod("getThreadMXBean").invoke(null);val type=Class.forName("com.sun.management.ThreadMXBean",true,loader)
        type.getMethod("setThreadAllocatedMemoryEnabled",Boolean::class.javaPrimitiveType).invoke(threads,true)
        val alloc=type.getMethod("getTotalThreadAllocatedBytes");fun allocated()=(alloc.invoke(threads) as Number).toLong()
        try {
            val q=Question("near","near capacity",Kind.SHORT,"fixture")
            repo.importBank("""{"schemaVersion":1,"questions":[{"id":"near","stem":"near capacity","kind":"SHORT","source":"fixture"}]}""")
            val store=AiStore(db,db.ai(),{q},now={1000})
            store.importIntoEmpty(AiDataset(sessions=List(31) { n -> AiSessionRow(id(),q.id,q.contentHash,questionSnapshot=AiSnapshotCodec.question(q),title="s$n",createdAt=1000,updatedAt=1000,draftText=('a'+n%26).toString().repeat(1024*1024)) }))
            val bytes=AiMeter.total(db.ai().measures());assertTrue(bytes>AiConversationCodec.CAPACITY*96/100)
            repo.dao.putAi(AiEntry("legacy","near",DeepSeekClient.FLASH,"old history","o".repeat(1024*1024),10,2,0,0.1,1))
            queries.clear();val startAlloc=allocated();val start=System.nanoTime();repo.initializeAiStorage();val coldNs=System.nanoTime()-start;val coldAllocated=allocated()-startAlloc
            val coldReads=synchronized(queries) { queries.count { it.trimStart().startsWith("SELECT",true) } };queries.clear()
            val exportAlloc=allocated();val exportStart=System.nanoTime();repo.backupTo { out.outputStream() };val exportNs=System.nanoTime()-exportStart;val exportedAllocated=allocated()-exportAlloc
            val exportReads=synchronized(queries) { queries.count { it.trimStart().startsWith("SELECT",true) } }
            assertTrue(coldReads>0 && exportReads>0)
            assertTrue(out.length()>32L*1024*1024)
            val restoreAlloc=allocated();val restoreStart=System.nanoTime();repo.restoreFrom(out.inputStream());val restoreNs=System.nanoTime()-restoreStart;val restoredAllocated=allocated()-restoreAlloc
            assertEquals(31,db.ai().allSessions().size);assertEquals(1024*1024,repo.dao.allAi().single().response.length)
            val before=db.ai().allSessions().map { it.sessionId }
            repo.storageResource={ throw java.io.IOException("live workspace exhausted") }
            assertTrue(runCatching { repo.restoreFrom(out.inputStream()) }.isFailure)
            assertEquals(before,db.ai().allSessions().map { it.sessionId })
            println("AI_PRODUCTION_CAPACITY bytes=$bytes sessions=31 outputBytes=${out.length()} coldReads=$coldReads exportReads=$exportReads coldNs=$coldNs coldAllocatedBytes=$coldAllocated exportNs=$exportNs exportAllocatedBytes=$exportedAllocated restoreNs=$restoreNs restoreAllocatedBytes=$restoredAllocated hostProcessWindow=true peakNotMeasured=true")
        } finally { out.delete();db.close();context.deleteDatabase(name) }
    }
}
