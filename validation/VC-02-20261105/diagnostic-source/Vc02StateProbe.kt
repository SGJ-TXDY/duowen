package cn.embeddedquiz

import android.view.View
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Read-only probes, scoped to the currently blocked setContent; no global reset/observer. */
internal object Vc02StateProbe {
    fun field(instance: Any, name: String): Any? {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            val current = type
            val f = current.declaredFields.firstOrNull { it.name == name }
            if (f != null) return f.apply { isAccessible = true }.get(instance)
            type = current.superclass
        }
        return null
    }
    fun sample(rule: Any, label: String) {
        runCatching {
            val env = requireNotNull(field(rule, "environment"))
            val idle = field(env, "composeIdlingResource")
            val flags = idle?.javaClass?.declaredFields?.filter { it.type == Boolean::class.javaPrimitiveType }
                ?.joinToString { it.isAccessible = true; "${it.name}=${it.get(idle)}" }
            val recomposer = field(env, "recomposer") as? Recomposer
            println("VC02 PROBE $label idle=[$flags] recomposer=${recomposer?.currentState?.value} pending=${recomposer?.hasPendingWork} globalPending=${Snapshot.current.hasPendingChanges()}")
            val dispatcherType = Class.forName("androidx.compose.ui.platform.AndroidUiDispatcher")
            val companion = dispatcherType.getDeclaredField("Companion").get(null)
            val context = companion.javaClass.getDeclaredMethod("getMain").invoke(companion) as kotlin.coroutines.CoroutineContext
            val dispatcher = context[kotlin.coroutines.ContinuationInterceptor]!!
            val status = dispatcher.javaClass.declaredFields.filter { it.type == Boolean::class.javaPrimitiveType || it.name in listOf("toRunTrampolined", "handler", "choreographer") }
                .joinToString { it.isAccessible = true; val v = it.get(dispatcher); "${it.name}=" + if(v is Collection<*>) "size=${v.size}" else "$v" }
            println("VC02 DISPATCHER $label $status")
            val registry = field(env, "composeRootRegistry") ?: return
            val getter = registry.javaClass.methods.first { it.name.startsWith("getCreatedComposeRoots") }
            val roots = getter.invoke(registry) as? Collection<*>
            roots?.forEach { root ->
                val view = root as? View
                val delegate = root?.let { field(it, "measureAndLayoutDelegate") }
                println("VC02 ROOT $label type=${root?.javaClass?.name} size=${view?.width}x${view?.height} measured=${view?.measuredWidth}x${view?.measuredHeight} attached=${view?.isAttachedToWindow} layoutRequested=${view?.isLayoutRequested} delegate=$delegate")
            }
        }.onFailure { println("VC02 PROBE $label error=${it.javaClass.name}:${it.message}") }
    }
    fun watch(rule: Any): AutoCloseable {
        val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "VC02 current-editor probe").apply { isDaemon = true } }
        for (delay in listOf(500L, 1500L)) timer.schedule({ sample(rule, "editor-blocked-${delay}ms") }, delay, TimeUnit.MILLISECONDS)
        return AutoCloseable { timer.shutdownNow() }
    }
}