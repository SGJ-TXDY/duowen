package cn.embeddedquiz

import java.math.BigDecimal
import kotlinx.coroutines.flow.Flow

enum class CostOrigin { LEGACY_ESTIMATE, RUN }
data class CostPart(
    val origin: CostOrigin, val knownEstimated: BigDecimal,
    val unknownEstimateCalls: Long, val unknownUsageCalls: Long,
    val knownInput: Long, val knownOutput: Long, val networkCalls: Long,
    val legacyRecords: Long, val cacheHits: Long,
)
data class CostBucket(val day: Long, val model: String, val currency: String, val parts: List<CostPart>)
data class CostMonthUi(
    val year: Int, val month: Int, val currency: String, val knownEstimated: BigDecimal,
    val unknownEstimateCalls: Long, val unknownUsageCalls: Long,
    val legacyRecords: Long, val networkCalls: Long, val cacheHits: Long,
)
data class AiCostsUiState(
    val loading: Boolean, val errorCode: String?, val buckets: List<CostBucket>,
    val months: List<CostMonthUi>,
)
interface AiCostsRepository {
    fun observe(): Flow<AiCostsUiState> // 同库事务快照聚合，两个消费者共用一个订阅。
}