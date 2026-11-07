package cn.embeddedquiz

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** AI-02a-2 entry probe, not a device performance acceptance or production migration. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class AiFoundationCapacityProbeTest {
    @Test fun nearCapacityRecordsCpuAllocationAndTransactionLatency()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val db=Room.inMemoryDatabaseBuilder(context,AiFoundationTestDb::class.java).allowMainThreadQueries().build()
        val q=Question("capacity-probe","容量探针",Kind.SHORT,"fixture")
        val snapshot=AiSnapshotCodec.question(q)
        val uuid={UUID.randomUUID().toString()}
        // Exactly one MiB per new text field; leave room for real command receipts.
        val draft="x".repeat(1024*1024)
        val data=AiDataset(sessions=List(31) { AiSessionRow(uuid(),q.id,q.contentHash,questionSnapshot=snapshot,
            title="probe",createdAt=1000,updatedAt=1000,draftText=draft) })
        val bytes=AiConversationCodec.encodedBytes(data)
        assertTrue(bytes>AiConversationCodec.CAPACITY*96/100)
        val store=AiStore(db,db.ai(),{q},now={1000L})
        // Android's compile classpath does not expose JDK management APIs. Host-only test reflection
        // reads the real JDK counters without adding app dependencies or touching Android runtime.
        val loader=ClassLoader.getSystemClassLoader()
        val factory=Class.forName("java.lang.management.ManagementFactory",true,loader)
        val os=factory.getMethod("getOperatingSystemMXBean").invoke(null)
        val threads=factory.getMethod("getThreadMXBean").invoke(null)
        val osType=Class.forName("com.sun.management.OperatingSystemMXBean",true,loader)
        val threadType=Class.forName("com.sun.management.ThreadMXBean",true,loader)
        threadType.getMethod("setThreadAllocatedMemoryEnabled",Boolean::class.javaPrimitiveType).invoke(threads,true)
        val cpuMethod=osType.getMethod("getProcessCpuTime")
        val allocationMethod=threadType.getMethod("getTotalThreadAllocatedBytes")
        fun cpuTime()=(cpuMethod.invoke(os) as Number).toLong()
        fun allocations()=(allocationMethod.invoke(threads) as Number).toLong()
        try {
            store.importIntoEmpty(data)
            val sid=data.sessions.first().sessionId; val editor=store.openEditor(sid)
            println("AI_CAPACITY_PROBE fixtureBytes=$bytes capacity=${AiConversationCodec.CAPACITY} sessions=31 fieldBytes=${draft.length}")
            var revision=0L
            repeat(3) { index ->
                val allocationBefore=allocations()
                val cpuBefore=cpuTime(); val start=System.nanoTime()
                // Same one-MiB text, but a real new accepted draft token/receipt/transaction.
                store.saveDraft(DraftCommand(uuid(),sid,revision,DraftToken(editor,index.toLong()),draft))
                val elapsed=System.nanoTime()-start; val cpu=cpuTime()-cpuBefore
                val allocated=allocations()-allocationBefore
                revision++
                println("AI_CAPACITY_PROBE sample=$index wallNs=$elapsed processCpuNs=$cpu allocatedBytes=$allocated")
            }
            assertEquals(3L,store.flushDraft(sid,3))
            val result=store.dataset(); assertEquals(31,result.sessions.size)
            assertTrue(result.sessions.all { it.draftText==draft }); assertEquals(3,result.commands.size)
            AiConversationCodec.validate(result)
            println("AI_CAPACITY_PROBE finalBytes=${AiConversationCodec.encodedBytes(result)} note=host_JVM_Robolectric_not_device_no_streaming_claim")
        } finally { db.close() }
    }
    @Test fun fixedOneKiBDraftWithDifferentUnrelatedBodiesHasBoundedReadsAndAllocation()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val factory=Class.forName("java.lang.management.ManagementFactory",true,ClassLoader.getSystemClassLoader())
        val threads=factory.getMethod("getThreadMXBean").invoke(null)
        val type=Class.forName("com.sun.management.ThreadMXBean",true,ClassLoader.getSystemClassLoader())
        type.getMethod("setThreadAllocatedMemoryEnabled",Boolean::class.javaPrimitiveType).invoke(threads,true)
        val allocation=type.getMethod("getTotalThreadAllocatedBytes")
        fun bytes()=(allocation.invoke(threads) as Number).toLong()
        for(count in listOf(1,8,31)) {
            val queries=java.util.Collections.synchronizedList(mutableListOf<String>())
            val db=Room.inMemoryDatabaseBuilder(context,AiFoundationTestDb::class.java).allowMainThreadQueries()
                .setQueryCallback({ sql,_ -> queries.add(sql) },java.util.concurrent.Executor { it.run() }).build()
            val q=Question("scale","scale",Kind.SHORT,"fixture")
            val rows=List(count) { n -> AiSessionRow(UUID.randomUUID().toString(),q.id,q.contentHash,
                questionSnapshot=AiSnapshotCodec.question(q.copy(company="source-$n")),title="s$n",createdAt=1000,updatedAt=1000,
                draftText=if(n==0) "x".repeat(1024) else (('a'+n%26).toString().repeat(1024*1024-64)+"different-$n")) }
            val store=AiStore(db,db.ai(),{q},now={1000L})
            try {
                val seedDb=Room.inMemoryDatabaseBuilder(context,AiFoundationTestDb::class.java).allowMainThreadQueries().build()
                val seeded=try {
                    val seed=AiStore(seedDb,seedDb.ai(),{q},now={1000L});val identities=mutableListOf<String>()
                    repeat(minOf(count,3)) { n ->
                        val sid=seed.create(UUID.randomUUID().toString(),q.copy(company="source-$n")).sessionId!!;identities.add(sid)
                        val c=SendCommand(UUID.randomUUID().toString(),sid,QuestionOwner(q.id,q.contentHash),0,null,null,
                            "different-payload-$n"+"v".repeat(n*1000),emptySet(),"code-$n",AiRequestType.FOLLOW_UP,DeepSeekClient.FLASH)
                        val receipt=seed.accept(c);seed.checkpoint(receipt.requestId!!,1,"reply-$n",RunStatus.COMPLETED)
                    }
                    val data=seed.dataset();data.copy(sessions=rows.mapIndexed { n,row ->
                        if(n<identities.size) data.sessions.single { it.sessionId==identities[n] }.copy(draftText=row.draftText) else row
                    })
                } finally { seedDb.close() }
                store.importIntoEmpty(seeded);val sid=seeded.sessions.first().sessionId;val editor=store.openEditor(sid)
                // Fixed schedule: one warmup, then three measured saves for every distribution.
                store.saveDraft(DraftCommand(UUID.randomUUID().toString(),sid,0,DraftToken(editor,0),"x".repeat(1024)))
                repeat(3) { index ->
                    queries.clear();val allocated=bytes();val start=System.nanoTime()
                    store.saveDraft(DraftCommand(UUID.randomUUID().toString(),sid,index+1L,DraftToken(editor,index+1L),"x".repeat(1024)))
                    val elapsed=System.nanoTime()-start;val delta=bytes()-allocated
                    assertTrue(synchronized(queries) { queries.toList() }.none { it.contains("SELECT *",true) && !it.contains(" WHERE ",true) })
                    println("AI_SCALE_PROBE sessions=$count sample=$index wallNs=$elapsed allocatedBytes=$delta selectedBytes=1024 warmup=1 measured=3")
                }
                store.audit()
            } finally { db.close() }
        }
    }

}
