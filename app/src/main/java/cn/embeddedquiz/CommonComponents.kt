package cn.embeddedquiz

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
fun Action(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = text },
    ) {
        Text(text)
    }
}

@Composable
fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge)
}

@Composable
fun VerificationNotice(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
fun Detail(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        remember(text) { ReadingBlocks.parse(text) }
            .forEach { block ->
                if (block.code) CodeBlock(block.text)
                else
                    SelectionContainer {
                        Text(
                            block.text,
                            style = MaterialTheme.typography.bodyLarge,
                            fontFamily = FontFamily.Default,
                        )
                    }
            }
    }
}

@Composable
fun Picker(label: String, value: String, options: List<String>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier =
                Modifier.heightIn(min = 48.dp).semantics { contentDescription = "$label：$value" },
        ) {
            Text("$label：$value")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 360.dp),
        ) {
            options.distinct().forEach { x ->
                DropdownMenuItem(
                    text = { Text(x) },
                    onClick = {
                        onChange(x)
                        expanded = false
                    },
                )
            }
        }
    }
}
