package cn.embeddedquiz

import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StartupTest {
    @Test
    fun launchOfflineAndOpenQuestion() {
        // Load ViewModel data before attaching Compose: a paused Robolectric clock
        // cannot drain an indeterminate progress animation during Activity.setup().
        val controller =
            Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            val vm = ViewModelProvider(controller.get())[QuizViewModel::class.java]
            repeat(100) {
                shadowOf(Looper.getMainLooper()).idle()
                if (vm.questions.isEmpty()) Thread.sleep(50)
            }
            assertTrue(vm.notice, vm.ready)
            assertEquals(906, vm.questions.size)
            controller.visible()
            vm.start(vm.questions.filter { it.daily }, "顺序练习")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(Page.QUESTION, vm.page)
            assertTrue(vm.queue.isNotEmpty())
            assertFalse(
                vm.queue.any { id -> vm.questions.first { it.id == id }.kind == Kind.PROGRAMMING }
            )
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
