package cn.embeddedquiz

import android.os.SystemClock
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * [onChange] fires only when the text itself changes. Cursor, selection and IME composition stay in
 * the local [TextFieldValue]: they are editing state, not draft content, and must not cause saves.
 * With [enabled] false the text stays readable and selectable but cannot be edited.
 */
@Composable
fun CodeEditor(
    value: String,
    state: CodeEditorState = remember { CodeEditorState(value) },
    enabled: Boolean = true,
    onChange: (String) -> Unit,
) {
    var field by state.field
    LaunchedEffect(value) {
        if (value != field.text) field = TextFieldValue(value, TextRange(value.length))
    }
    val clipboard = LocalClipboardManager.current
    val style =
        MaterialTheme.typography.bodyLarge.copy(
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("我的代码（自动保存草稿）", style = MaterialTheme.typography.titleSmall)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    val start = field.selection.min
                    val end = field.selection.max
                    val text = field.text.replaceRange(start, end, "    ")
                    field = TextFieldValue(text, TextRange(start + 4))
                    onChange(text)
                },
                enabled = enabled,
                modifier =
                    Modifier.heightIn(min = 48.dp).semantics { contentDescription = "插入四个空格缩进" },
            ) {
                Text("Tab 缩进")
            }
            OutlinedButton(
                onClick = { clipboard.setText(AnnotatedString(field.text)) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("复制我的代码")
            }
        }
        Surface(
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shape = MaterialTheme.shapes.small,
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .heightIn(min = 180.dp, max = 360.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                Text(
                    (1..field.text.count { it == '\n' } + 1).joinToString("\n"),
                    style = style,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp).clearAndSetSemantics {},
                )
                Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                    BasicTextField(
                        value = field,
                        onValueChange = {
                            val changed = it.text != field.text
                            field = it
                            if (changed) onChange(it.text)
                        },
                        readOnly = !enabled,
                        textStyle = style,
                        keyboardOptions =
                            KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                autoCorrectEnabled = false,
                                keyboardType = KeyboardType.Ascii,
                            ),
                        modifier =
                            Modifier.widthIn(min = 240.dp)
                                .heightIn(min = 180.dp)
                                .testTag("code-editor")
                                .semantics { contentDescription = "代码编辑器，支持多行代码和原始缩进" },
                    )
                }
            }
        }
    }
}

@Composable
fun CodingTimer(state: CodeTimerState = remember { CodeTimerState() }) {
    var visible by state.visible
    var running by state.running
    var elapsed by state.elapsed
    var started by state.started
    var tick by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(running) {
        while (running) {
            tick = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    val seconds = (elapsed + if (running) (tick - started).coerceAtLeast(0) else 0) / 1000
    Column {
        Row {
            Checkbox(checked = visible, onCheckedChange = { visible = it })
            Text("显示编程计时器", Modifier.padding(top = 14.dp))
        }
        if (visible)
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "${seconds/60}:${(seconds%60).toString().padStart(2,'0')}",
                    Modifier.padding(top = 16.dp),
                )
                OutlinedButton(
                    onClick = {
                        if (running)
                            elapsed += (SystemClock.elapsedRealtime() - started).coerceAtLeast(0)
                        else {
                            started = SystemClock.elapsedRealtime()
                            tick = started
                        }
                        running = !running
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(if (running) "暂停" else "开始计时")
                }
                TextButton(
                    onClick = {
                        running = false
                        elapsed = 0
                        started = 0
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("重置计时")
                }
            }
    }
}
