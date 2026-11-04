package cn.embeddedquiz

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** T4 draft editing UI. The editor reports text changes only; caret and selection stay local. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@OptIn(ExperimentalTestApi::class)
class CodeDraftSaveUiTest {
    @get:Rule val ui = createComposeRule()

    private var code by mutableStateOf("")
    private var enabled by mutableStateOf(true)
    private val changes = mutableListOf<String>()
    private lateinit var state: CodeEditorState

    /** The caller's side as QuestionScreen wires it: keep the text, forward each change. */
    private fun showEditor(initial: String) {
        code = initial
        state = CodeEditorState(initial)
        ui.setContent {
            MaterialTheme {
                CodeEditor(code, state, enabled) {
                    code = it
                    changes += it
                }
            }
        }
    }

    private val editor get() = ui.onNodeWithTag("code-editor")

    @Test
    fun onlyTextChangesAreReportedAndTheCaretSurvivesTheRoundTrip() {
        showEditor("int a;")
        editor.performTextInputSelection(TextRange(3))
        editor.performTextInputSelection(TextRange(0, 3))
        editor.performTextInputSelection(TextRange(6))
        ui.waitForIdle()
        assertEquals("moving the caret or selecting is not a draft change", emptyList<String>(), changes)

        editor.performTextInput(" b;")
        ui.waitForIdle()
        assertEquals(listOf("int a; b;"), changes)
        // The parent's value echo must not reset the field and throw the caret to a new position.
        assertEquals(TextRange(9), state.field.value.selection)

        editor.performTextInputSelection(TextRange(4))
        ui.waitForIdle()
        assertEquals(1, changes.size)
        assertEquals(TextRange(4), state.field.value.selection)
        editor.assertTextEquals("int a; b;", includeEditableText = true)
    }

    @Test
    fun indentInsertsAtTheCaretAsOneChange() {
        showEditor("ab")
        editor.performTextInputSelection(TextRange(1))
        ui.onNodeWithContentDescription("插入四个空格缩进").performClick()
        ui.waitForIdle()
        assertEquals(listOf("a    b"), changes)
        assertEquals(TextRange(5), state.field.value.selection)
    }

    @Test
    fun disabledEditorKeepsTheTextReadableButAcceptsNoEdit() {
        showEditor("keep")
        enabled = false
        ui.waitForIdle()
        editor.assert(SemanticsMatcher.expectValue(SemanticsProperties.IsEditable, false))
        editor.assertTextEquals("keep", includeEditableText = true)
        ui.onNodeWithContentDescription("插入四个空格缩进").assertIsNotEnabled().performClick()
        ui.waitForIdle()
        assertTrue(changes.isEmpty())
        assertEquals("keep", code)

        enabled = true
        ui.waitForIdle()
        editor.assert(SemanticsMatcher.expectValue(SemanticsProperties.IsEditable, true))
    }
}
