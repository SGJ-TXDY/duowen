package cn.embeddedquiz

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ComposeMainQueueTest {
    @get:Rule val ui = createComposeRule()

    @Before fun prepareMainQueue() { ComposeMainQueue.deliverStrandedCallbacks() }

    private val dispatcher get() = AndroidUiDispatcher.Main[ContinuationInterceptor]!!
    private fun field(name: String): Any =
        checkNotNull(dispatcher.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(dispatcher))

    @Test fun aRemovedNotificationStillDeliversTheLatestTextAndSelection() {
        val value = mutableStateOf(TextFieldValue("before", TextRange(2)))
        ui.setContent { BasicTextField(value.value, { value.value = it }, Modifier.testTag("field")) }
        val latest = TextFieldValue("after\n  code", TextRange(8))
        ui.runOnUiThread {
            value.value = latest
            val handler = field("handler") as Handler
            val callback = field("dispatchCallback") as Runnable
            assertTrue("The real snapshot write scheduled a notification", handler.hasCallbacks(callback))
            // Reproduce lost messages without changing Compose flags or data.
            handler.removeCallbacks(callback)
            (field("choreographer") as Choreographer).removeFrameCallback(callback as Choreographer.FrameCallback)
            var delivered = false
            try {
                delivered = ComposeMainQueue.deliverStrandedCallbacks()
                assertTrue(delivered)
            } finally {
                // Deliver the original callback even if the assertion fails.
                if (!delivered) {
                    handler.post(callback)
                    shadowOf(Looper.getMainLooper()).idle()
                }
            }
        }
        ui.waitForIdle()
        ui.onNodeWithTag("field").assertTextEquals(latest.text, includeEditableText = true)
        ui.onNodeWithTag("field").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.TextSelectionRange, latest.selection,
        ))
        assertEquals(latest, value.value)
    }

    @Test fun anAlreadyQueuedCallbackIsNotPostedAgain() {
        val calls = AtomicInteger()
        ui.runOnUiThread {
            (dispatcher as CoroutineDispatcher).dispatch(EmptyCoroutineContext, Runnable { calls.incrementAndGet() })
            assertFalse(ComposeMainQueue.deliverStrandedCallbacks())
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals(1, calls.get())
    }

    @Test fun anIdleDispatcherIsLeftAlone() {
        ui.runOnUiThread {
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(ComposeMainQueue.deliverStrandedCallbacks())
        }
    }
}