package cn.embeddedquiz

import java.math.BigDecimal
import java.math.RoundingMode

/** Snapshot rates are currency units per 1,000,000 tokens. No live price lookup. */
internal object AiEstimateCodec {
    const val SCALE = 12
    private val million = BigDecimal("1000000")
    // Exact intermediate decimal arithmetic; round only the final amount, HALF_UP at 12 places.
    fun expected(run: AiRunRow): String? {
        if (run.source == "CACHE") return "0"
        if (run.usageStatus != "KNOWN" || run.priceVersion.isNullOrBlank() ||
            run.missRate == null || run.hitRate == null || run.outputRate == null) return null
        val input = requireNotNull(run.inputTokens); val output = requireNotNull(run.outputTokens); val hit = requireNotNull(run.hitTokens)
        require(input >= 0 && output >= 0 && hit in 0..input)
        val amount = BigDecimal.valueOf(Math.subtractExact(input,hit)).multiply(AiJson.decimal(run.missRate))
            .add(BigDecimal.valueOf(hit).multiply(AiJson.decimal(run.hitRate)))
            .add(BigDecimal.valueOf(output).multiply(AiJson.decimal(run.outputRate)))
            .divide(million).setScale(SCALE,RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString()
        AiJson.decimal(amount) // Keep the same bounded, non-exponent storage grammar.
        return amount
    }
}
