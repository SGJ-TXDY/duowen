package cn.embeddedquiz

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun Home(vm: QuizViewModel, today: LocalDate) {
    val day = today.toEpochDay()
    val count = vm.learning.daily.firstOrNull { it.day == day }
    val goal = vm.learning.settings.dailyGoal
    val completed = count?.answered ?: 0
    val streak = LearningDates.streak(vm.learning.learnedDays, today)
    val summaryState = vm.todaySummaryState
    // Only trust a snapshot for the date being shown; around midnight the old day's counts may linger.
    val summary = summaryState.value?.takeIf { it.day == day }
    // Counts come from the backend summary; the question lists are only built when a button is tapped.
    fun daily() = vm.questions.filter { it.daily }
    fun unseen() = daily().filter { vm.p(it.id).seen == 0 }
    LazyColumn(
        modifier = Modifier.testTag("home-list"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${today.monthValue}月${today.dayOfMonth}日 · ${today.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.CHINA)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("今天，稳步积累", style = MaterialTheme.typography.headlineSmall)
                }
                Surface(
                    color = MaterialTheme.status.warningContainer,
                    contentColor = MaterialTheme.status.onWarningContainer,
                    shape = CircleShape,
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(AppIcons.Flame, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("连续 $streak 天", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        item {
            GoalHero(
                completed = completed,
                goal = goal,
                newAnswered = count?.newAnswered ?: 0,
                reviewAnswered = count?.reviewAnswered ?: 0,
                dueCount = summary?.dueCount,
                onReview = { vm.start(vm.due(day), "到期复习") },
                onLearnNew = { vm.start(unseen(), "新题") },
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(summary?.unseenCount?.toString(), "未学新题", Modifier.weight(1f))
                StatTile(summary?.dailyCount?.toString(), "日常题库", Modifier.weight(1f))
                StatTile(summary?.programmingCount?.toString(), "编程专区", Modifier.weight(1f))
            }
        }
        if (summaryState.error.isNotBlank())
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        summaryState.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = vm::retryTodaySummary) { Text("重试") }
                }
            }
        if (vm.queue.isNotEmpty())
            item {
                val exam = vm.mode == "模拟考试" && !vm.examFinished
                ContinueCard(
                    title = if (exam) "继续未交卷考试" else "继续上次练习",
                    mode =
                        listOfNotNull(vm.mode, vm.currentQuestionSummary?.normalizedCategory)
                            .joinToString(" · "),
                    position = vm.index + 1,
                    total = vm.queue.size,
                ) {
                    vm.page = Page.QUESTION
                }
            }
        item {
            Text(
                "练习方式",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ModeTile(AppIcons.Book, "学习新题", "从未做过的题", Modifier.weight(1f)) {
                        vm.start(unseen(), "新题")
                    }
                    ModeTile(AppIcons.Shuffle, "随机练习", "打乱日常题库", Modifier.weight(1f)) {
                        vm.start(daily().shuffled(), "随机练习")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ModeTile(AppIcons.ListOrder, "顺序练习", "按题库顺序", Modifier.weight(1f)) {
                        vm.start(daily(), "顺序练习")
                    }
                    ModeTile(
                        AppIcons.Target,
                        "薄弱知识点",
                        "正确率最低的分类",
                        Modifier.weight(1f),
                        vm::startWeakPractice,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ModeTile(AppIcons.Folder, "分类练习", "按分类与来源筛选", Modifier.weight(1f)) {
                        vm.navigate(Page.BROWSE)
                    }
                    ModeTile(AppIcons.Code, "编程专区", "编程题，不进日常复习", Modifier.weight(1f)) {
                        vm.navigate(Page.PROGRAMMING)
                    }
                }
            }
        }
        item {
            Text(
                "有可靠标准选项的题提交后直接判分；其余题目对照参考后自评正确、错误或不会。个人核对答案单独保存，AI 建议需自行核实。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun GoalHero(
    completed: Int,
    goal: Int,
    newAnswered: Int,
    reviewAnswered: Int,
    dueCount: Int?,
    onReview: () -> Unit,
    onLearnNew: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.primary,
        contentColor = colors.onPrimary,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                GoalRing(completed, goal)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("今日目标", style = MaterialTheme.typography.labelLarge)
                    Text(
                        if (completed >= goal) "今日目标已完成" else "还差 ${goal - completed} 题完成",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        "新题 $newAnswered · 复习 $reviewAnswered",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Text(
                "按当天完成的不同题目计数，重复作答不重复计入。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onPrimary.copy(alpha = 0.85f),
            )
            // While the summary loads, the review command itself decides (it reports an empty queue).
            val hasDue = dueCount == null || dueCount > 0
            Button(
                onClick = if (hasDue) onReview else onLearnNew,
                shape = MaterialTheme.shapes.medium,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = colors.onPrimary,
                        contentColor = colors.primary,
                    ),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(
                    if (hasDue) AppIcons.Review else AppIcons.Book,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    when {
                        dueCount == null -> "开始今日复习"
                        dueCount > 0 -> "开始今日复习 · $dueCount 题到期"
                        else -> "今日复习已清空 · 学习新题"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun GoalRing(completed: Int, goal: Int) {
    val track = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.22f)
    val bar = MaterialTheme.colorScheme.onPrimary
    val fraction = if (goal <= 0) 0f else (completed.toFloat() / goal).coerceIn(0f, 1f)
    Box(
        Modifier.size(88.dp).clearAndSetSemantics {
            contentDescription = "今日目标 $completed / $goal 题"
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val width = 8.dp.toPx()
            val inset = width / 2
            val arcSize = androidx.compose.ui.geometry.Size(size.width - width, size.height - width)
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(width))
            if (fraction > 0f)
                drawArc(
                    bar,
                    -90f,
                    360f * fraction,
                    false,
                    topLeft,
                    arcSize,
                    style = Stroke(width, cap = StrokeCap.Round),
                )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "$completed",
                style = MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Monospace,
            )
            Text("/ $goal 题", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun StatTile(value: String?, label: String, modifier: Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            // null means the summary has not loaded yet; never render it as zero.
            Text(
                value ?: "—",
                style = MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Monospace,
                color =
                    if (value == null) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ContinueCard(title: String, mode: String, position: Int, total: Int, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier.size(44.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    AppIcons.Play,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row {
                    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(
                        "$position / $total",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LinearProgressIndicator(
                    progress = { position.toFloat() / total.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                    strokeCap = StrokeCap.Round,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
                Text(
                    mode,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                AppIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun ModeTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.heightIn(min = 104.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
