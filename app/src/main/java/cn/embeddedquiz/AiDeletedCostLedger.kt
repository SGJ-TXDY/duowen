package cn.embeddedquiz

import androidx.room.Entity
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.LocalDate
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

internal const val AI_DELETED_COST_SECTION = "deletedAiCosts"
internal const val AI_DELETED_COST_COLUMNS =
    "billingDay,model,currency,knownEstimated,unknownEstimateCalls,unknownUsageCalls,knownInput,knownOutput,networkCalls,cacheHits"

@Entity(tableName = "ai_deleted_costs", primaryKeys = ["billingDay", "model", "currency"])
internal data class AiDeletedCostRow(
    val billingDay: Long,
    val model: String,
    val currency: String,
    val knownEstimated: String,
    val unknownEstimateCalls: Long,
    val unknownUsageCalls: Long,
    val knownInput: Long,
    val knownOutput: Long,
    val networkCalls: Long,
    val cacheHits: Long,
) {
    fun part() = CostPart(CostOrigin.RUN, AiJson.decimal(knownEstimated), unknownEstimateCalls,
        unknownUsageCalls, knownInput, knownOutput, networkCalls, 0, cacheHits)
}

internal object AiDeletedCostMigration : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE ai_deleted_costs (billingDay INTEGER NOT NULL, model TEXT NOT NULL, currency TEXT NOT NULL, " +
            "knownEstimated TEXT NOT NULL, unknownEstimateCalls INTEGER NOT NULL, unknownUsageCalls INTEGER NOT NULL, " +
            "knownInput INTEGER NOT NULL, knownOutput INTEGER NOT NULL, networkCalls INTEGER NOT NULL, cacheHits INTEGER NOT NULL, " +
            "PRIMARY KEY(billingDay,model,currency))")
    }
}

internal object AiDeletedCostLedger {
    private val columns = AI_DELETED_COST_COLUMNS.split(',')
    private const val runs = "SELECT billingDay,model,currency, " +
        "CASE WHEN source='CACHE' THEN '0' ELSE COALESCE(estimatedUsd,'0') END, " +
        "CASE WHEN source<>'CACHE' AND estimatedUsd IS NULL THEN 1 ELSE 0 END, " +
        "CASE WHEN source<>'CACHE' AND usageStatus<>'KNOWN' THEN 1 ELSE 0 END, " +
        "CASE WHEN source='CACHE' THEN 0 ELSE COALESCE(inputTokens,0) END, " +
        "CASE WHEN source='CACHE' THEN 0 ELSE COALESCE(outputTokens,0) END, " +
        "CASE WHEN source='CACHE' THEN 0 ELSE 1 END, CASE WHEN source='CACHE' THEN 1 ELSE 0 END " +
        "FROM ai_runs WHERE (networkDispatched=1 OR source='CACHE')"

    private data class Key(val day: Long, val model: String, val currency: String)

    private fun AiDeletedCostRow.combine(other: AiDeletedCostRow): AiDeletedCostRow = copy(
        knownEstimated = AiJson.decimal(knownEstimated).add(AiJson.decimal(other.knownEstimated)).toPlainString(),
        unknownEstimateCalls = Math.addExact(unknownEstimateCalls, other.unknownEstimateCalls),
        unknownUsageCalls = Math.addExact(unknownUsageCalls, other.unknownUsageCalls),
        knownInput = Math.addExact(knownInput, other.knownInput),
        knownOutput = Math.addExact(knownOutput, other.knownOutput),
        networkCalls = Math.addExact(networkCalls, other.networkCalls),
        cacheHits = Math.addExact(cacheHits, other.cacheHits),
    )

    private suspend fun grouped(db: SupportSQLiteDatabase, query: String, args: Array<String> = emptyArray()): List<AiDeletedCostRow> {
        val rows = linkedMapOf<Key, AiDeletedCostRow>()
        db.query(query, args).use { c ->
            while (c.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val row = AiDeletedCostRow(c.getLong(0), c.getString(1), c.getString(2), c.getString(3),
                    c.getLong(4), c.getLong(5), c.getLong(6), c.getLong(7), c.getLong(8), c.getLong(9))
                val key = Key(row.billingDay, row.model, row.currency)
                rows[key] = rows[key]?.combine(row) ?: row
            }
        }
        return rows.values.toList()
    }

    suspend fun read(db: SupportSQLiteDatabase): List<AiDeletedCostRow> =
        grouped(db, "SELECT $AI_DELETED_COST_COLUMNS FROM ai_deleted_costs UNION ALL $runs")

    suspend fun archiveSession(db: SupportSQLiteDatabase, sessionId: String) {
        for (row in grouped(db, "$runs AND sessionId=?", arrayOf(sessionId))) accumulate(db, row)
    }

    // Book an unknown network charge at dispatch so an abrupt process exit still counts the call.
    // Settlement replaces that contribution under the same Room transaction, without storing IDs or text.
    suspend fun recordTemporaryCharge(db: SupportSQLiteDatabase, row: AiRunRow) {
        check(row.networkDispatched || row.source == "CACHE")
        accumulate(db, temporaryContribution(row))
    }

    suspend fun settleTemporaryDispatch(db: SupportSQLiteDatabase, row: AiRunRow) {
        check(row.networkDispatched)
        val charge = temporaryContribution(row)
        accumulate(db, charge.copy(unknownEstimateCalls = charge.unknownEstimateCalls - 1,
            unknownUsageCalls = charge.unknownUsageCalls - 1, networkCalls = 0))
    }

    private fun temporaryContribution(row: AiRunRow): AiDeletedCostRow {
        val cache = row.source == "CACHE"
        return AiDeletedCostRow(row.billingDay, row.model, row.currency, row.estimatedUsd ?: "0",
            if (!cache && row.estimatedUsd == null) 1 else 0,
            if (!cache && row.usageStatus != "KNOWN") 1 else 0,
            if (cache) 0 else row.inputTokens ?: 0, if (cache) 0 else row.outputTokens ?: 0,
            if (cache) 0 else 1, if (cache) 1 else 0)
    }

    private suspend fun accumulate(db: SupportSQLiteDatabase, row: AiDeletedCostRow) {
        val prior = grouped(db, "SELECT $AI_DELETED_COST_COLUMNS FROM ai_deleted_costs WHERE billingDay=? AND model=? AND currency=?",
            arrayOf(row.billingDay.toString(), row.model, row.currency)).singleOrNull()
        val sum = prior?.combine(row) ?: row
        check(sum.unknownEstimateCalls >= 0 && sum.unknownUsageCalls >= 0)
        val values = arrayOf(sum.billingDay, sum.model, sum.currency, sum.knownEstimated, sum.unknownEstimateCalls,
            sum.unknownUsageCalls, sum.knownInput, sum.knownOutput, sum.networkCalls, sum.cacheHits)
        db.execSQL("INSERT OR REPLACE INTO ai_deleted_costs ($AI_DELETED_COST_COLUMNS) VALUES (?,?,?,?,?,?,?,?,?,?)", values)
    }

    fun key(row: JSONObject): String = AiJson.canonical(JSONArray(listOf(
        AiJson.long(row, "billingDay"), AiJson.string(row, "model"), AiJson.string(row, "currency"))))

    fun validate(row: JSONObject): JSONObject {
        require(row.keys().asSequence().toSet() == columns.toSet())
        LocalDate.ofEpochDay(AiJson.long(row, "billingDay"))
        require(AiJson.string(row, "model") in AiSnapshotCodec.MODELS_V1)
        require(AiJson.string(row, "currency").matches(Regex("[A-Z]{3}")))
        AiJson.decimal(AiJson.string(row, "knownEstimated"))
        val counters = columns.drop(4).map { AiJson.long(row, it) }
        require(counters.all { it in 0..1_000_000_000_000L })
        val (unknownCost, unknownUsage, _, _, calls) = counters
        require(unknownCost <= calls && unknownUsage <= calls && (calls > 0 || counters.last() > 0))
        return row
    }
}
