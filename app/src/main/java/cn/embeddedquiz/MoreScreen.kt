package cn.embeddedquiz

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

/** Everything the 更多 page shows, derived from state the ViewModel already holds. */
data class MoreUiState(
    val streak: Int,
    val studied: Int,
    val mistakes: Int,
    /** Null while the summary for [today] is loading or failed: no hint and no dot then. */
    val due: Int?,
    val pendingVerify: Int,
    val monthUsd: Double,
    val todayDone: Int,
    val dailyGoal: Int,
) {
    val dueHint
        get() = due?.takeIf { it > 0 }?.let { "今日待复习 $it" }

    val verifyHint
        get() = pendingVerify.takeIf { it > 0 }?.let { "$it 条" }

    val costHint
        get() =
            when {
                monthUsd <= 0 -> null
                monthUsd < 0.01 -> "本月 <$0.01"
                else -> "本月 $${"%.2f".format(monthUsd)}"
            }
}

fun moreUiState(
    today: LocalDate,
    learnedDays: Collection<Long>,
    totals: Totals,
    progress: Collection<Progress>,
    summary: TodaySummary?,
    questions: List<QuestionSummary>,
    canGrade: (QuestionSummary) -> Boolean,
    costs: List<CostCount>,
    daily: List<DailyCount>,
    dailyGoal: Int,
): MoreUiState {
    val day = today.toEpochDay()
    return MoreUiState(
        streak = LearningDates.streak(learnedDays, today),
        studied = totals.studied,
        mistakes = progress.count { it.wrong },
        // Around midnight the previous day's snapshot may linger; only trust today's.
        due = summary?.takeIf { it.day == day }?.dueCount,
        // Same list as the workbench, minus the ones a valid personal answer already settles.
        pendingVerify =
            questions.count {
                it.objective && (it.reviewRequired || it.correctKeys.isBlank()) && !canGrade(it)
            },
        monthUsd =
            costs
                .filter {
                    val date = LocalDate.ofEpochDay(it.day)
                    date.year == today.year && date.month == today.month
                }
                .sumOf { it.estimatedUsd },
        todayDone = daily.firstOrNull { it.day == day }?.answered ?: 0,
        dailyGoal = dailyGoal,
    )
}

private data class MoreEntry(
    val page: Page,
    val title: String,
    val icon: ImageVector,
    val tint: Color,
    val hint: String? = null,
    val dot: Boolean = false,
)

@Composable
fun MoreScreen(vm: QuizViewModel, today: LocalDate) {
    val learning = vm.learning
    val state =
        remember(
            today,
            learning.learnedDays,
            learning.totals,
            vm.progress,
            vm.todaySummaryState,
            vm.questions,
            learning.answers,
            learning.costs,
            learning.daily,
            learning.settings.dailyGoal,
        ) {
            moreUiState(
                today,
                learning.learnedDays,
                learning.totals,
                vm.progress.values,
                vm.todaySummaryState.value,
                vm.questions,
                learning::canGrade,
                learning.costs,
                learning.daily,
                learning.settings.dailyGoal,
            )
        }
    val colors = MaterialTheme.more
    // Category, programming and exam live on the home screen and the tab bar.
    // The review page left the tab bar for 速记; it stays reachable here and from notifications.
    val groups =
        listOf(
            listOf(
                MoreEntry(Page.REVIEW, "智能复习", AppIcons.Sync, colors.review, state.dueHint, state.dueHint != null),
                MoreEntry(Page.NOTES, "全局标签 / 便签", AppIcons.TagThin, colors.tag),
                MoreEntry(
                    Page.WORKBENCH,
                    Page.WORKBENCH.label,
                    AppIcons.ClipboardCheck,
                    colors.verify,
                    state.verifyHint,
                    state.verifyHint != null,
                ),
            ),
            listOf(
                MoreEntry(Page.STATISTICS, Page.STATISTICS.label, AppIcons.BarChart, colors.stats),
                MoreEntry(Page.COSTS, Page.COSTS.label, AppIcons.Coin, colors.cost, state.costHint),
            ),
            listOf(MoreEntry(Page.SETTINGS, Page.SETTINGS.label, AppIcons.Settings, colors.settings)),
        )
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(colors.page).testTag("more-list"),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            ProfileCard(
                title = "我的学习",
                subtitle = "今日 ${state.todayDone} / ${state.dailyGoal} 题",
                chips = listOf("连续 ${state.streak} 天", "已刷 ${state.studied} 题", "错题 ${state.mistakes}"),
                avatar = { Mascot(Modifier.size(56.dp)) },
                // No profile page yet; the card keeps its slot and gains a chevron once one exists.
                onClick = null,
            )
        }
        groups.forEach { group ->
            item { Spacer(Modifier.height(8.dp)) }
            item {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)) {
                    group.forEachIndexed { index, entry ->
                        if (index > 0)
                            HorizontalDivider(
                                Modifier.padding(start = 56.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        MoreRow(entry) { vm.navigate(entry.page) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileCard(
    title: String,
    subtitle: String,
    chips: List<String>,
    onClick: (() -> Unit)?,
    avatar: @Composable () -> Unit = { Icon(AppIcons.Person, contentDescription = null, Modifier.size(32.dp)) },
) {
    val sub = MaterialTheme.colorScheme.onSurfaceVariant
    val base = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)
    Column(
        (if (onClick != null) base.clickable(role = Role.Button, onClick = onClick)
            else base.semantics(mergeDescendants = true) {})
            .padding(start = 24.dp, top = 24.dp, end = 16.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(64.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                CompositionLocalProvider(
                    LocalContentColor provides MaterialTheme.colorScheme.onPrimaryContainer,
                    content = avatar,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
                Text(
                    subtitle,
                    fontSize = 14.sp,
                    color = sub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onClick != null)
                Icon(AppIcons.ChevronRight, contentDescription = null, Modifier.size(20.dp), tint = sub)
        }
        FlowRow(
            Modifier.padding(start = 80.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            chips.forEach {
                Text(
                    it,
                    fontSize = 12.sp,
                    color = sub,
                    modifier =
                        Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                            .padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun MoreRow(entry: MoreEntry, onClick: () -> Unit) {
    val sub = MaterialTheme.colorScheme.onSurfaceVariant
    // clickable merges the row's texts, so tests and TalkBack read title and hint together.
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(entry.icon, contentDescription = null, Modifier.size(24.dp), tint = entry.tint)
        Spacer(Modifier.width(16.dp))
        Text(entry.title, Modifier.weight(1f), fontSize = 16.sp, lineHeight = 22.sp)
        entry.hint?.let {
            Text(
                it,
                Modifier.padding(start = 8.dp).widthIn(max = 160.dp),
                fontSize = 13.sp,
                color = sub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (entry.dot)
            Box(
                Modifier.padding(start = 6.dp)
                    .size(8.dp)
                    .background(MaterialTheme.more.dot, CircleShape)
                    .semantics { contentDescription = "有待处理" }
            )
        Icon(
            AppIcons.ChevronRight,
            contentDescription = null,
            Modifier.padding(start = 4.dp).size(20.dp),
            tint = sub,
        )
    }
}
