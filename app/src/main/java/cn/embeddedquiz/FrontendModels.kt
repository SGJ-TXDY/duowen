package cn.embeddedquiz

/** 首页和导航角标共用的数据库快照；日期使用设备本地日期的 epochDay。 */
data class TodaySummary(
    val day: Long,
    val dueCount: Int,
    val unseenCount: Int,
    val dailyCount: Int,
    val programmingCount: Int,
)

/** value 只在查询成功时非空；空题库是计数均为零的成功结果。 */
data class TodaySummaryState(
    val value: TodaySummary? = null,
    val loading: Boolean = true,
    val error: String = "",
)
