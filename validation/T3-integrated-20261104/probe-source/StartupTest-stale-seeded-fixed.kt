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
        val current = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val cached = ViewModelProvider.AndroidViewModelFactory.getInstance(current)
        val appField = ViewModelProvider.AndroidViewModelFactory::class.java.declaredFields.first { android.app.Application::class.java.isAssignableFrom(it.type) }.apply { isAccessible = true }
        val factoryApplication = appField.get(cached) as android.app.Application
        println("T3_PROBE currentApplication=${System.identityHashCode(current)} factoryApplication=${System.identityHashCode(factoryApplication)} same=${current === factoryApplication} currentDatabase=${current.getDatabasePath("embeddedquiz.db")} factoryDatabase=${factoryApplication.getDatabasePath("embeddedquiz.db")}")
        check(current !== factoryApplication) { "Probe requires an earlier Activity's cached Application" }
        // Controlled fixture: stale factory data must not become the current startup's data.
        kotlinx.coroutines.runBlocking {
            val foreign = QuizDatabase.open(factoryApplication)
            try {
                val repository = QuizRepository(foreign)
                repository.importBank("""{"schemaVersion":1,"questions":[{"id":"foreign-one","stem":"foreign fixture","source":"probe","kind":"SHORT"}]}""")
            } finally { foreign.close() }
        }
        factoryApplication.getSharedPreferences("settings", 0).edit().putInt("bankRevision", 4).commit()
        resetDefaultViewModelFactory()
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
