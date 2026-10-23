package cn.embeddedquiz

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.text.input.TextFieldValue

/** Session-owned UI state survives temporary removal while full question JSON loads. */
class QuestionDraft(choices: List<String>, codeText: String, notesText: String) {
    val selected = mutableStateOf(choices)
    val response = mutableStateOf("")
    val revealed = mutableStateOf(false)
    val code = mutableStateOf(codeText)
    val notes = mutableStateOf(notesText)
    val prompt = mutableStateOf("")
    val scroll = LazyListState()
    val codeEditor = CodeEditorState(codeText)
    val timer = CodeTimerState()
}

class CodeEditorState(text: String) {
    val field = mutableStateOf(TextFieldValue(text))
}

class CodeTimerState {
    val visible = mutableStateOf(false)
    val running = mutableStateOf(false)
    val elapsed = mutableLongStateOf(0)
    val started = mutableLongStateOf(0)
}
