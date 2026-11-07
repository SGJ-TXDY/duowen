package cn.embeddedquiz

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One authority per physical Room owner, shared by every Repository wrapper. No detached worker. */
internal class AiStorageLifecycle {
    private val lock = Any()
    private val replacement = Mutex()
    private val writes = Mutex()
    private val validation = Mutex()
    private var validated = false
    private val operations = mutableSetOf<Job>()
    private val writers = mutableSetOf<Job>()
    private val legacyListeners = mutableListOf<suspend (Boolean, Boolean) -> Unit>()
    private val listeners = mutableListOf<suspend (Boolean, Boolean) -> Unit>()
    private var epoch = 0L
    private var paused = false
    private var closed = false
    private var healthy = true
    private var newSender = false
    private val admissionChanges = MutableStateFlow(0L)
    private val closeHooks = mutableListOf<()->Job>()
    private fun changed() { admissionChanges.value++ }
    fun legacyToken(): Long? = synchronized(lock) { if(newSender) null else token() }
    fun legacyRetired(): Boolean = synchronized(lock) { newSender }
    fun isClosing(): Boolean = synchronized(lock) { closed }
    fun onClosing(hook:()->Job) { val late=synchronized(lock) { if(closed) true else { closeHooks+=hook;false } };if(late) hook() }
    suspend fun awaitAvailable() {
        admissionChanges.first { token()!=null || isClosing() }
        check(!isClosing()) { "CLOSED" }
    }
    /** Opt-in cutover. No old writer can be active when the new facade becomes READY. */
    suspend fun enableNewSender() = operation {
        replacement.withLock {
            val jobs=synchronized(lock) {
                check(!closed && healthy)
                newSender=true;paused=true;epoch++;changed();writers.toList()
            }
            try { withContext(NonCancellable) {
                synchronized(lock) { legacyListeners.toList() }.forEach { it(true,false) }
                jobs.forEach { it.cancel() };jobs.joinAll()
            } } finally { synchronized(lock) { paused=false;changed() } }
        }
    }
    // Installed by the owner to reuse its existing draft writer boundary, even for direct repo calls.
    var draftBoundary: suspend (suspend () -> Unit) -> Unit = { it() }
    var afterReplacement: suspend () -> Unit = {}
    fun health(value: Boolean) = synchronized(lock) { healthy = value;changed() }
    fun token(): Long? = synchronized(lock) { if (closed || paused || !healthy) null else epoch }
    fun listenLegacy(listener:suspend (Boolean,Boolean)->Unit) = synchronized(lock) { check(!closed);legacyListeners+=listener }
    fun listen(listener: suspend (Boolean, Boolean) -> Unit) = synchronized(lock) { check(!closed); listeners += listener }
    fun attach(token: Long, job: Job): Boolean = synchronized(lock) {
        if (closed || paused || !healthy || epoch != token) false else {
            writers += job
            job.invokeOnCompletion { synchronized(lock) { writers -= job } }
            true
        }
    }
    suspend fun <T> operation(block: suspend () -> T): T = coroutineScope {
        val task = currentCoroutineContext().job
        synchronized(lock) { check(!closed) { "存储已关闭" }; operations += task }
        try { block() } finally { synchronized(lock) { operations -= task } }
    }
    suspend fun <T> write(token: Long, block: suspend () -> T): T = writes.withLock {
        currentCoroutineContext().ensureActive()
        check(synchronized(lock) { !closed && !paused && healthy && token == epoch }) { "AI写入已失效" }
        block()
    }
    suspend fun validate(force:Boolean=false, block:suspend ()->Unit) = validation.withLock {
        if(force || !validated) { block(); validated=true }
    }
    suspend fun <T> foundationWrite(block: suspend () -> T): T = operation {
        val t = checkNotNull(token()) { "AI存储暂不可写" }
        write(t, block)
    }
    suspend fun replace(block: suspend () -> Unit) = operation {
        replacement.withLock {
            val jobs = synchronized(lock) { check(!closed); paused = true; epoch++;changed(); writers.toList() }
            var replaced = false
            try {
                // Await cancellation even when the restore caller itself is cancelled. Never free
                // admission while a cancelled network thread/finally can still be writing.
                withContext(NonCancellable) {
                    synchronized(lock) { legacyListeners.toList()+listeners.toList() }.forEach { it(true, false) }
                    jobs.forEach { it.cancel() }; jobs.joinAll()
                }
                currentCoroutineContext().ensureActive()
                // Once the replacement boundary begins, drain its atomic transaction and owner
                // refresh before releasing admission, just as the existing draft replacement did.
                withContext(NonCancellable) {
                    writes.withLock { block(); replaced = true; validated = true; afterReplacement() }
                }
                currentCoroutineContext().ensureActive()
            } finally {
                withContext(NonCancellable) {
                    try { synchronized(lock) { legacyListeners.toList() }.forEach { it(false,replaced) } }
                    finally {
                        synchronized(lock) { paused=false;changed() }
                        synchronized(lock) { listeners.toList() }.forEach { it(false,replaced) }
                    }
                }
            }
        }
    }
    /** Called before owner cancellation; the owner joins this exact snapshot before closing Room. */
    fun closing(): List<Job> {
        val (tasks,hooks)=synchronized(lock) {
            closed=true;paused=true;epoch++;changed()
            (operations+writers).toList() to closeHooks.toList()
        }
        val drains=hooks.map { it() }
        tasks.forEach { it.cancel() }
        return tasks+drains
    }
}
