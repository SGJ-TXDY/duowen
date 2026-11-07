package cn.embeddedquiz

import androidx.room.InvalidationTracker
import androidx.room.withTransaction
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*

/** One shared observation per owner. Observes both tables and reads one Room snapshot. */
internal class AiCostsStore(private val db:QuizDatabase):AiCostsRepository {
    private val owner=SupervisorJob()
    private val scope=CoroutineScope(owner+Dispatchers.IO)
    init { db.aiLifecycle.onClosing { owner.cancel();owner } }
    val state:StateFlow<AiCostsUiState> = flow {
        db.aiLifecycle.awaitAvailable();emitAll(db.aiInvalidations("ai","ai_runs"))
    }.conflate().map {
        try {
            db.aiLifecycle.awaitAvailable();QuizRepository(db).ensureAiStorage()
            db.aiLifecycle.operation { db.withTransaction { snapshot() } }
        } catch(e:CancellationException) { throw e }
        catch(_:Exception) { AiCostsUiState(false,"STORAGE_FAILED",emptyList(),emptyList()) }
    }.catch { emit(AiCostsUiState(false,"STORAGE_FAILED",emptyList(),emptyList())) }.stateIn(scope,SharingStarted.WhileSubscribed(0),AiCostsUiState(true,null,emptyList(),emptyList()))
    override fun observe():Flow<AiCostsUiState> = state

    private data class Key(val day:Long,val model:String,val currency:String)
    private data class Month(val year:Int,val month:Int,val currency:String)
    private class Totals {
        var money:BigDecimal=BigDecimal.ZERO;var unknownEstimate=0L;var unknownUsage=0L
        var input=0L;var output=0L;var calls=0L;var legacy=0L;var hits=0L
        fun part(origin:CostOrigin)=CostPart(origin,money,unknownEstimate,unknownUsage,input,output,calls,legacy,hits)
        fun add(p:CostPart) { money=money.add(p.knownEstimated);unknownEstimate=Math.addExact(unknownEstimate,p.unknownEstimateCalls)
            unknownUsage=Math.addExact(unknownUsage,p.unknownUsageCalls);calls=Math.addExact(calls,p.networkCalls)
            legacy=Math.addExact(legacy,p.legacyRecords);hits=Math.addExact(hits,p.cacheHits) }
    }
    private suspend fun snapshot():AiCostsUiState {
        val sql=db.openHelper.readableDatabase
        val bins=linkedMapOf<Key,MutableMap<CostOrigin,Totals>>()
        fun bucket(day:Long,model:String,currency:String,origin:CostOrigin)=bins.getOrPut(Key(day,model,currency)) { linkedMapOf() }.getOrPut(origin,::Totals)
        sql.query("SELECT day,model,estimatedUsd FROM ai").use { c -> while(c.moveToNext()) {
            currentCoroutineContext().ensureActive()
            val a=bucket(c.getLong(0),c.getString(1),"USD",CostOrigin.LEGACY_ESTIMATE)
            a.money=a.money.add(BigDecimal.valueOf(c.getDouble(2)));a.legacy=Math.addExact(a.legacy,1)
        } }
        sql.query("SELECT billingDay,model,currency,source,networkDispatched,usageStatus,inputTokens,outputTokens,estimatedUsd FROM ai_runs WHERE networkDispatched=1 OR source='CACHE'").use { c -> while(c.moveToNext()) {
            currentCoroutineContext().ensureActive()
            val a=bucket(c.getLong(0),c.getString(1),c.getString(2),CostOrigin.RUN)
            if(c.getString(3)=="CACHE") a.hits=Math.addExact(a.hits,1)
            else {
                a.calls=Math.addExact(a.calls,1)
                if(c.getString(5)!="KNOWN") a.unknownUsage=Math.addExact(a.unknownUsage,1)
                if(!c.isNull(6)) a.input=Math.addExact(a.input,c.getLong(6))
                if(!c.isNull(7)) a.output=Math.addExact(a.output,c.getLong(7))
                if(c.isNull(8)) a.unknownEstimate=Math.addExact(a.unknownEstimate,1) else a.money=a.money.add(AiJson.decimal(c.getString(8)))
            }
        } }
        val buckets=bins.map { (k,parts)-> CostBucket(k.day,k.model,k.currency,parts.map { (origin,part)->part.part(origin) }.sortedBy { it.origin.name }) }
            .sortedWith(compareByDescending<CostBucket> { it.day }.thenBy { it.model }.thenBy { it.currency })
        val months=linkedMapOf<Month,Totals>()
        for(b in buckets) {
            val date=LocalDate.ofEpochDay(b.day);val total=months.getOrPut(Month(date.year,date.monthValue,b.currency),::Totals)
            b.parts.forEach(total::add)
        }
        return AiCostsUiState(false,null,buckets,months.map { (m,t)->CostMonthUi(m.year,m.month,m.currency,t.money,
            t.unknownEstimate,t.unknownUsage,t.legacy,t.calls,t.hits) }.sortedWith(compareByDescending<CostMonthUi> { it.year }.thenByDescending { it.month }.thenBy { it.currency }))
    }
}

internal fun QuizDatabase.aiInvalidations(vararg tables:String):Flow<Unit> = callbackFlow {
    val observer=object:InvalidationTracker.Observer(tables) { override fun onInvalidated(tables:Set<String>) { trySend(Unit) } }
    invalidationTracker.addObserver(observer);trySend(Unit)
    awaitClose { invalidationTracker.removeObserver(observer) }
}
