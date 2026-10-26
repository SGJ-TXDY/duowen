package cn.embeddedquiz

import android.app.Activity
import android.os.Looper
import androidx.compose.runtime.Recomposer
import androidx.lifecycle.ViewModelProvider
import java.time.Duration
import java.util.concurrent.locks.LockSupport
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/*
 * Robolectric gives every test a new Application inside one JVM; production has one per process.
 * Activity-based tests need two things the Activity's own defaults do not provide here:
 *
 * 1. ComponentActivity's default factory goes through AndroidViewModelFactory.getInstance(), which
 *    keeps the first Application in a static. Later tests then get a ViewModel bound to a stale
 *    Application and its preferences, not the ones the test prepared.
 * 2. A window composed outside a Compose test rule gets a Recomposer on a UI dispatcher left over
 *    from an earlier test; after destroy it can keep pending work forever and stall every later
 *    Compose test. Activity tests therefore run under a Compose rule (createEmptyComposeRule()),
 *    whose single recomposer the rule itself shuts down.
 */

/** Drops the cached default factory so the next Activity's ViewModel uses this test's Application. */
internal fun resetDefaultViewModelFactory() {
    ViewModelProvider.AndroidViewModelFactory::class.java
        .getDeclaredField("_instance")
        .apply { isAccessible = true }
        .set(null, null)
}

/** Take before building an Activity; only recomposers it adds are waited for at teardown. */
internal fun runningRecomposers(): Set<Any> = Recomposer.runningRecomposers.value.toSet()

/**
 * Destroys the Activity and checks that no Recomposer it created is still running, so the next
 * test does not inherit it. Never cancels or waits on recomposers that existed before.
 */
internal fun <T : Activity> ActivityController<T>.destroyAndAwaitRecomposers(before: Set<Any>) {
    if (!get().isDestroyed) pause().stop().destroy()
    fun created() = Recomposer.runningRecomposers.value.filterNot { it in before }
    val end = System.nanoTime() + 15_000_000_000L
    while (created().isNotEmpty() && System.nanoTime() < end) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        LockSupport.parkNanos(1_000_000)
    }
    assertTrue("Activity recomposers still running after destroy: ${created().size}", created().isEmpty())
}
