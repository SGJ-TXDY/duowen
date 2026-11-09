package cn.embeddedquiz

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The question page's AI entry (PLAN-1.7.5): the app's own mascot floating above the page. Tapping it
 * only opens the menu; the discussion opens, or a one-tap request is sent, when an item is chosen.
 * The page leaves it out during a mock exam, like the discussion itself. Showing it reads nothing
 * from the AI backend, so it does not move the send right.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AiMascotEntry(modifier: Modifier = Modifier, onOpen: (AiRequestType?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Surface(
            onClick = { expanded = !expanded },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            shadowElevation = 6.dp,
            modifier = Modifier.size(60.dp).testTag("ai-mascot").semantics { contentDescription = "AI 学习助手" },
        ) {
            Box(contentAlignment = Alignment.Center) { MascotStill(Modifier.size(44.dp)) }
        }
        DropdownMenu(
            expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(max = 320.dp).testTag("ai-mascot-menu"),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("AI 学习助手", style = MaterialTheme.typography.titleMedium)
                Text(
                    "发送时会把本题、参考答案、所选选项和本讨论最近几轮内容发送到 DeepSeek。AI 内容独立存储，不写入题库、不参与判分，需自行核实。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        expanded = false
                        onOpen(null)
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("ai-open"),
                ) { Text("打开 AI 讨论") }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AI_QUICK_TYPES.forEach { type ->
                        AiQuickChip(type.label, true) {
                            expanded = false
                            onOpen(type)
                        }
                    }
                }
            }
        }
    }
}
