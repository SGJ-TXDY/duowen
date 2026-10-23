package cn.embeddedquiz

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
fun Action(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled) { Text(text) }
}

@Composable
fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge)
}

@Composable
fun Detail(text: String) {
    SelectionContainer {
        Text(text, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun Picker(label: String, value: String, options: List<String>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("$label：$value") }
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
