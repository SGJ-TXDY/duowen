package cn.embeddedquiz

import android.os.Handler
import android.os.Looper
import androidx.compose.ui.platform.AndroidUiDispatcher
import kotlin.coroutines.ContinuationInterceptor
import org.robolectric.Shadows.shadowOf

/**
 * Robolectric resets the main message queue between tests; Compose 1.7.6 keeps its main dispatcher.
 * A non-UI test can leave its snapshot consumer queued across that reset. The dispatcher then
 * believes its callback is scheduled and will not post another one when the next UI test writes.
 *
 * Deliver only that dispatcher's original callback when work exists but its Handler message was
 * removed. This neither discards snapshot changes nor resets a dispatcher, observer or recomposer.
 * Reflection is confined to this test helper and intentionally fails if the pinned internals change.
 */
internal object ComposeMainQueue {
    private fun field(dispatcher: Any, name: String): Any =
        checkNotNull(dispatcher.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(dispatcher))

    fun deliverStrandedCallbacks(): Boolean {
        check(Looper.myLooper() === Looper.getMainLooper())
        val dispatcher = AndroidUiDispatcher.Main[ContinuationInterceptor]!!
        val handler = field(dispatcher, "handler") as Handler
        val callback = field(dispatcher, "dispatchCallback") as Runnable
        check(handler.looper === Looper.getMainLooper())
        val stranded = synchronized(field(dispatcher, "lock")) {
            field(dispatcher, "scheduledTrampolineDispatch") == true &&
                (field(dispatcher, "toRunTrampolined") as Collection<*>).isNotEmpty() &&
                !handler.hasCallbacks(callback)
        }
        if (!stranded) return false
        check(handler.post(callback))
        shadowOf(handler.looper).idle()
        return true
    }
}