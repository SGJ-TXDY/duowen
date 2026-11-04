package cn.embeddedquiz

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface InitializationOutcome {
    data class Ready(val examRestored: Boolean) : InitializationOutcome
    data class Failed(val message: String) : InitializationOutcome
}

class QuizViewModel(app: Application) : AndroidViewModel(app) {
    val db = QuizDatabase.open(app)
    val repo = QuizRepository(db)
    val vault = KeyVault(app)
    private val prefs = app.getSharedPreferences("settings", 0)
    var questions by mutableStateOf<List<QuestionSummary>>(emptyList())
        private set

    var progress by mutableStateOf<Map<String, Progress>>(emptyMap())
        private set

    var attempts by mutableStateOf<List<Attempt>>(emptyList())
        private set

    private var notesReturnPage = Page.HOME
    var ready by mutableStateOf(false)
        private set

    private lateinit var initializationCompletion: CompletableDeferred<InitializationOutcome>
    private val startupMutex = Mutex()
    private val startupRecovery = StartupRecovery(app, prefs)
    private var initializationAttempt = 0
    private var databaseOpened = false
    private var subscriptionsStarted = false
    private var lastStartupExceptionType = ""
    @Volatile private var cleared = false
    internal var beforeStartupStage: suspend (StartupStage) -> Unit = {}
    var startupState by mutableStateOf<StartupState>(StartupState.Loading(0, StartupStage.DATABASE, "正在打开本机题库"))
        private set
    var startupRecoveryBusy by mutableStateOf(false)
        private set
    private var notificationReviewRequested = false

    /** Main-thread command. Restore the exam first; apply the notification before publishing ready. */
    fun requestNotificationReview() {
        if (ready) page = Page.REVIEW else notificationReviewRequested = true
    }

    /** Captures this attempt; old outcomes stay stable after an explicit retry. */
    suspend fun awaitInitialization(): InitializationOutcome = initializationCompletion.await()

    var busy by mutableStateOf(false)
        private set

    var notice by mutableStateOf("")
    val aiState = AiStateHolder(viewModelScope, repo, vault, prefs) { notice = it }
    val aiEntries
        get() = aiState.entries

    val aiBusy
        get() = aiState.busy

    val model
        get() = aiState.model

    val hasKey
        get() = aiState.hasKey

    val learning = LearningStateHolder(viewModelScope, repo, app)
    val codeDrafts = CodeDraftStateHolder(viewModelScope, repo)
    val errorLog = LocalErrorLog(app)
    var errorLogText by mutableStateOf("")
        private set

    var importProgress by mutableFloatStateOf(0f)
        private set

    var loadingMessage by mutableStateOf("正在准备离线题库")
        private set

    private val fullQuestionCache = android.util.LruCache<String, Question>(8)
    val notesState = NotesStateHolder(viewModelScope, repo, autoStart = false)
    val quickNotes = QuickNotesStateHolder(
        viewModelScope,
        QuickNotesRepository(java.io.File(app.filesDir, "quicknotes-v1")),
        app,
    )

    fun openQuickNotes() = quickNotes.enter()

    val stickyNotes
        get() = notesState.stickyNotes

    val noteDraft
        get() = notesState.noteDraft

    val pendingNoteWrites
        get() = notesState.pendingNoteWrites

    val noteSaveError
        get() = notesState.noteSaveError

    var page by mutableStateOf(Page.HOME)
    var queue by mutableStateOf<List<String>>(emptyList())
    var index by mutableIntStateOf(0)
    var mode by mutableStateOf("顺序练习")
    var practiceSession by mutableIntStateOf(0)
        private set

    var practiceRecords by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    private val questionDrafts = mutableMapOf<String, QuestionDraft>()

    fun questionDraft(q: Question): QuestionDraft =
        questionDrafts.getOrPut(q.id) {
            QuestionDraft(
                if (mode == "模拟考试") examAnswers[q.id].orEmpty().toList() else emptyList(),
                codeDrafts.drafts[q.id]?.code ?: p(q.id).code,
                codeDrafts.drafts[q.id]?.notes ?: p(q.id).notes,
            )
        }

    val examState = ExamStateHolder(prefs, repo).also { it.reserveForRestore() }
    var examDeadline: Long
        get() = examState.deadline
        set(value) {
            examState.deadline = value
        }

    var examAnswers: Map<String, Set<String>>
        get() = examState.answers
        set(value) {
            examState.answers = value
        }

    var examFinished: Boolean
        get() = examState.finished
        set(value) {
            examState.finished = value
        }

    var examResult: String
        get() = examState.result
        set(value) {
            examState.result = value
        }

    init { launchInitialization() }

    /** Main command: one explicit attempt, no retry loop or duplicate subscriptions. */
    fun retryInitialization() {
        if (cleared || startupRecoveryBusy || startupState !is StartupState.Error) return
        launchInitialization()
    }

    private fun launchInitialization() {
        val attempt = ++initializationAttempt
        databaseOpened = false
        lastStartupExceptionType = ""
        val completion = CompletableDeferred<InitializationOutcome>(viewModelScope.coroutineContext[Job])
        initializationCompletion = completion
        ready = false
        startupState = StartupState.Loading(attempt, StartupStage.DATABASE, "正在打开本机题库")
        viewModelScope.launch {
            startupMutex.withLock {
                var stage = StartupStage.DATABASE
                suspend fun phase(value: StartupStage, message: String) {
                    stage = value
                    loadingMessage = message
                    startupState = StartupState.Loading(attempt, value, message)
                    beforeStartupStage(value)
                }
                try {
                    phase(StartupStage.DATABASE, "正在打开本机题库")
                    val count = withContext(Dispatchers.IO) { repo.dao.questionCount() }
                    databaseOpened = true
                    phase(StartupStage.VALIDATION, "正在检查本机历史数据")
                    withContext(Dispatchers.IO) { repo.validateStoredQuestions() }
                    phase(StartupStage.BANK, "正在准备离线题库")
                    withContext(Dispatchers.IO) {
                        if (count == 0 || prefs.getInt("bankRevision", 0) < 4) {
                            importProgress = 0.1f
                            repo.upgradeBundledBank(
                                getApplication<Application>().assets.open("bank.json").bufferedReader().use { it.readText() }
                            ) { done, total -> importProgress = 0.1f + 0.8f * done / total }
                            importProgress = 1f
                            prefs.writeDurably { putInt("bankRevision", 4) }
                        }
                    }
                    phase(StartupStage.INDEX, "正在检查分类索引")
                    withContext(Dispatchers.IO) {
                        if (prefs.getString("categoryMapHash", null) != CategoryMap.fingerprint) {
                            repo.reindexCategories()
                            prefs.writeDurably { putString("categoryMapHash", CategoryMap.fingerprint) }
                        }
                    }
                    phase(StartupStage.EXAM, "正在恢复未完成考试")
                    val restored = examState.restore()
                    if (restored != null) {
                        queue = restored.ids; index = restored.index; mode = "模拟考试"
                    }
                    if (!subscriptionsStarted) {
                        subscriptionsStarted = true
                        viewModelScope.launch { repo.dao.summaries().collect { questions = it } }
                        learning.start()
                        notesState.startObserving()
                        viewModelScope.launch { repo.dao.progress().collect { progress = it.associateBy { p -> p.questionId } } }
                        viewModelScope.launch { repo.dao.attempts().collect { attempts = it } }
                    }
                    // No suspension between restoring exam state, applying notification priority and Ready.
                    if (notificationReviewRequested || page == Page.REVIEW) page = Page.REVIEW
                    else if (restored != null) page = Page.QUESTION
                    notificationReviewRequested = false
                    ready = true
                    startupState = StartupState.Ready(attempt, restored != null)
                    completion.complete(InitializationOutcome.Ready(restored != null))
                } catch (failure: CancellationException) {
                    completion.cancel(failure)
                    throw failure
                } catch (failure: Exception) {
                    lastStartupExceptionType = failure.javaClass.simpleName
                    withContext(Dispatchers.IO) { errorLog.record(LogEvent.LOAD, failure) }
                    val generic = "题库加载失败，请查看本地错误记录"
                    val code = when {
                        failure is StoredQuestionException -> "STORED_DATA_INVALID"
                        stage == StartupStage.DATABASE -> "DATABASE_UNAVAILABLE"
                        stage == StartupStage.EXAM -> "EXAM_UNAVAILABLE"
                        else -> "PREPARATION_FAILED"
                    }
                    val message = when (code) {
                        "DATABASE_UNAVAILABLE" -> "无法打开本机题库，原数据未清除。可重试或导出诊断。"
                        "STORED_DATA_INVALID" -> "历史题目中有无法解释的数据，原始内容已保留。请导出诊断或恢复备份。"
                        "EXAM_UNAVAILABLE" -> "考试恢复失败，原快照已保留。请重试或导出诊断。"
                        else -> "启动准备未完成，原数据已保留。请重试或查看诊断。"
                    }
                    notice = generic
                    startupState = StartupState.Error(attempt, stage, message, code, databaseOpened)
                    completion.complete(InitializationOutcome.Failed(generic))
                }
            }
        }
    }

    suspend fun readStartupDiagnostics(): String {
        val state = startupState
        val type = lastStartupExceptionType
        return withContext(Dispatchers.IO) { startupRecovery.describe(state, type) }
    }

    private suspend fun recoveryAction(action: suspend () -> StartupRecoveryResult): StartupRecoveryResult {
        if (cleared) return StartupRecoveryResult.Rejected("启动会话已结束")
        val task = viewModelScope.async(start = CoroutineStart.UNDISPATCHED) {
            if (startupState !is StartupState.Error) return@async StartupRecoveryResult.Rejected("请在启动失败后使用恢复操作")
            if (!startupMutex.tryLock()) return@async StartupRecoveryResult.Rejected("启动恢复正在进行，请稍候")
            startupRecoveryBusy = true
            try { action() }
            catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) {
                withContext(Dispatchers.IO) { errorLog.record(LogEvent.OPERATION, failure) }
                StartupRecoveryResult.Failed("恢复操作未完成，原数据未被清除，请查看诊断或重试")
            } finally {
                if (!cleared && startupState is StartupState.Error && !examState.active()) examState.reserveForRestore()
                startupRecoveryBusy = false
                startupMutex.unlock()
            }
        }
        return try { task.await() } catch (failure: CancellationException) { task.cancel(failure); throw failure }
    }

    suspend fun exportStartupDiagnostics(uri: Uri): StartupRecoveryResult = recoveryAction {
        val description = readStartupDiagnostics()
        withContext(Dispatchers.IO) { startupRecovery.export(uri, description) }
        StartupRecoveryResult.Completed
    }

    /** Confirmation belongs to the error page; unavailable databases are never silently replaced. */
    suspend fun restoreStartupBackup(uri: Uri, confirmReplace: Boolean = false): StartupRecoveryResult = recoveryAction {
        if (!confirmReplace) return@recoveryAction StartupRecoveryResult.Rejected("恢复会替换学习数据，请先确认")
        if (!databaseOpened) return@recoveryAction StartupRecoveryResult.Rejected("数据库尚不可安全使用，不能恢复备份。请先导出诊断保留原库")
        val text = withContext(Dispatchers.IO) { readUri(uri) }
        val exam = try { examState.restore() }
            catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) { return@recoveryAction StartupRecoveryResult.Rejected("无法确认考试状态，不能恢复备份。请先导出诊断") }
        if (exam != null || examState.active()) return@recoveryAction StartupRecoveryResult.Rejected("请先结束当前考试，再恢复备份")
        withContext(NonCancellable) {
            withContext(Dispatchers.IO) { repo.restore(text) }
            if (!cleared) {
                examState.reserveForRestore()
                launchInitialization()
            }
        }
        StartupRecoveryResult.Completed
    }
    fun p(id: String) = progress[id] ?: Progress(id)

    val todaySummaryState: TodaySummaryState
        get() = learning.todaySummaryState

    val todaySummary: TodaySummary?
        get() = todaySummaryState.value

    val currentQuestionSummary: QuestionSummary?
        get() {
            val id = queue.getOrNull(index) ?: return null
            return questions.firstOrNull { it.id == id }
        }

    fun retryTodaySummary() = learning.retryTodaySummary()

    /** 调用方拥有协程生命周期；取消及查询异常向上传递，不占用全局 busy。 */
    suspend fun searchQuestions(filter: BrowseFilter, page: Page): List<QuestionSummary> {
        require(page in setOf(Page.BROWSE, Page.PROGRAMMING, Page.MISTAKES, Page.EXAM)) {
            "该页面不支持题库搜索"
        }
        return withContext(Dispatchers.IO) {
            repo.search(filter, page == Page.PROGRAMMING, page == Page.MISTAKES)
        }
    }

    suspend fun searchNotes(text: String): List<StickyNote> =
        withContext(Dispatchers.IO) { repo.searchNotes(text) }

    fun navigate(name: Page) {
        if (name == Page.NOTES) openStickyNotes() else page = name
    }

    fun openStickyNotes() {
        if (page != Page.NOTES) notesReturnPage = page
        page = Page.NOTES
    }

    fun leaveStickyNotes() {
        page =
            if (notesReturnPage == Page.QUESTION && queue.isEmpty()) Page.HOME else notesReturnPage
    }

    fun editStickyNote(note: StickyNote) = notesState.editStickyNote(note)

    fun closeStickyNote() = notesState.closeStickyNote()

    fun newStickyNote() = notesState.newStickyNote()

    fun updateStickyNote(title: String, content: String) =
        notesState.updateStickyNote(title, content)

    fun retryStickyNotes() = notesState.retryStickyNotes()

    fun deleteStickyNote(note: StickyNote) = notesState.deleteStickyNote(note)

    suspend fun flushStickyNotes() = notesState.flushStickyNotes()

    fun due(today: Long = LocalDate.now().toEpochDay()) =
        questions
            .filter { it.daily && p(it.id).seen > 0 && p(it.id).due <= today }
            .sortedWith(
                compareByDescending<QuestionSummary> { p(it.id).wrong }.thenBy { p(it.id).due }
            )

    fun launchTask(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
            } catch (e: Exception) {
                withContext(Dispatchers.IO) { errorLog.record(LogEvent.OPERATION, e) }
                notice = "操作未完成：${e.message ?: "请重试"}"
            } finally {
                busy = false
            }
        }
    }

    fun start(list: List<QuestionSummary>, selectedMode: String) {
        if (examState.active()) {
            notice = "请先继续并交卷当前考试，再开始其他练习"
            page = Page.QUESTION
            return
        }
        if (list.isEmpty()) {
            notice = "当前没有符合条件的题目"
            return
        }
        if (selectedMode == "模拟考试" && (busy || !ready)) return
        if (!examState.startSession(selectedMode == "模拟考试")) return
        questionDrafts.clear()
        practiceSession++
        practiceRecords = emptyMap()
        queue = list.map { it.id }
        index = 0
        mode = selectedMode
        page = Page.QUESTION
    }

    fun startExam(list: List<QuestionSummary>, count: Int, minutes: Int) {
        if (examState.active()) {
            notice = "已有未交卷考试，请先完成"
            page = Page.QUESTION
            return
        }
        if (busy || !ready) return
        val eligible = list.filter { it.daily && learning.canGrade(it) }
        if (eligible.isEmpty()) {
            notice = "当前筛选范围没有可判分客观题，请扩大筛选范围或导入含已核对标准答案的 JSON。"
            return
        }
        start(eligible.shuffled().take(count), "模拟考试")
        if (mode != "模拟考试" || !examState.active()) return
        examAnswers = emptyMap()
        examDeadline = System.currentTimeMillis() + minutes * 60000L
        saveExam()
    }

    val examOperationState: ExamOperationState
        get() = examState.operationState

    val examSummary: ExamResult?
        get() = examState.summary

    val examSaveState: ExamSaveState
        get() = examState.saveState

    fun retryExamSave() = examState.retrySave()
    suspend fun flushExamSave(): Boolean = examState.flushSave()

    fun saveExam() = examState.save(queue, index, mode)

    fun answerExam(id: String, answer: Set<String>) {
        if (mode != "模拟考试" || !examState.acceptsAnswers || id !in queue) return
        if (System.currentTimeMillis() >= examDeadline) {
            finishExam()
            return
        }
        examAnswers = examAnswers + (id to answer.toSet())
        saveExam()
    }

    fun finishExam() {
        // In particular, periodic timeout callbacks never implicitly retry a failed operation.
        if (examOperationState != ExamOperationState.Idle || busy || mode != "模拟考试") return
        if (examState.accept(ExamOperation.SUBMIT, queue, index)) launchExamOperation()
    }

    fun abandonExam() {
        if (busy || mode != "模拟考试") return
        if (examState.accept(ExamOperation.ABANDON, queue, index)) launchExamOperation()
    }

    fun retryExamOperation() {
        if (busy) return
        if (examState.acceptRetry()) launchExamOperation()
    }

    internal var examOperationJob: Job? = null
        private set
    internal var examResourcesClosed: Job? = null
        private set

    private fun launchExamOperation() {
        busy = true
        // Enter try/finally even when cancellation arrives before the first dispatch.
        examOperationJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                examState.executeOperation()
                (examOperationState as? ExamOperationState.Failed)?.let { notice = it.message }
            } finally {
                if (examOperationState == ExamOperationState.Abandoned) {
                    queue = emptyList()
                    index = 0
                    mode = "顺序练习"
                    questionDrafts.clear()
                    practiceSession++
                    practiceRecords = emptyMap()
                }
                busy = false
            }
        }
    }

    fun submit(q: Question, text: String, choices: Set<String>, done: () -> Unit) {
        if (practiceRecords.containsKey(q.id)) return
        val session = practiceSession
        val selectedMode = mode
        launchTask {
            require(learning.canGrade(q)) { "本题需要手动判断" }
            repo.record(q, text.ifBlank { choices.joinToString() }, choices, selectedMode)
            withContext(Dispatchers.Main) {
                if (session == practiceSession) {
                    practiceRecords = practiceRecords + (q.id to "AUTO")
                    done()
                }
            }
        }
    }

    fun toggleFavorite(id: String) = launchTask {
        repo.update(id) { it.copy(favorite = !it.favorite) }
    }

    fun assess(
        q: Question,
        text: String,
        choices: Set<String>,
        result: Assessment,
        done: () -> Unit,
    ) {
        if (practiceRecords.containsKey(q.id)) return
        val session = practiceSession
        val selectedMode = mode
        launchTask {
            require(!learning.canGrade(q)) { "本题按标准答案自动判分" }
            repo.record(
                q,
                text.ifBlank { choices.joinToString() },
                choices,
                selectedMode,
                assessment = result,
            )
            withContext(Dispatchers.Main) {
                if (session == practiceSession) {
                    practiceRecords = practiceRecords + (q.id to result.name)
                    done()
                }
            }
        }
    }

    fun toggleFlag(id: String) = launchTask { repo.update(id) { it.copy(flagged = !it.flagged) } }

    fun saveNotes(id: String, code: String, notes: String) {
        codeDrafts.update(id, code, notes)
        launchTask {
            codeDrafts.writer.flush()
            notice = "代码与笔记已保存"
        }
    }

    fun saveKey(value: String) = launchTask {
        vault.save(value)
        withContext(Dispatchers.Main) {
            aiState.refreshKey()
            notice = if (hasKey) "API Key 已加密保存" else "API Key 已删除"
        }
    }

    fun chooseModel(value: String) = aiState.chooseModel(value)

    fun ask(
        q: Question,
        text: String,
        choices: Set<String>,
        type: AiRequestType = AiRequestType.FOLLOW_UP,
    ) = aiState.ask(q, text, choices, type)

    private fun readUri(uri: Uri): String {
        val stream =
            getApplication<Application>().contentResolver.openInputStream(uri) ?: error("无法打开文件")
        val bytes =
            stream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    require(output.size() + n <= 32 * 1024 * 1024) { "文件超过 32 MiB" }
                    output.write(buffer, 0, n)
                }
                output.toByteArray()
            }
        require(bytes.size <= 32 * 1024 * 1024) { "文件超过 32 MiB" }
        return bytes.toString(Charsets.UTF_8)
    }

    fun importUri(uri: Uri) = launchTask {
        val (added, skipped) = repo.importBank(readUri(uri))
        notice = "新增 $added 题，跳过 $skipped 个已有 ID（保留旧答案与进度）"
    }

    fun restoreUri(uri: Uri) = launchTask {
        flushStickyNotes()
        codeDrafts.writer.flush()
        repo.restore(readUri(uri))
        fullQuestionCache.evictAll()
        withContext(Dispatchers.Main) {
            questionDrafts.clear()
            practiceSession++
            practiceRecords = emptyMap()
            queue = emptyList()
            notesState.closeStickyNote()
            codeDrafts.clear()
            page = Page.HOME
            notice = "备份已恢复"
        }
    }

    fun backupUri(uri: Uri) = launchTask {
        flushStickyNotes()
        codeDrafts.writer.flush()
        val text = repo.backup()
        val out =
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                ?: error("无法写入文件")
        out.bufferedWriter().use { it.write(text) }
        notice = "备份完成（不包含 API Key）"
    }

    fun resetProgress() = launchTask {
        repo.reset()
        withContext(Dispatchers.Main) {
            questionDrafts.clear()
            practiceSession++
            practiceRecords = emptyMap()
            queue = emptyList()
            notice = "复习和历史已重置；收藏、笔记和便签保留"
        }
    }

    suspend fun loadQuestion(id: String): Question? =
        withContext(Dispatchers.IO) {
            val storedProgress = repo.dao.getProgress(id)
            if (storedProgress != null)
                withContext(Dispatchers.Main) {
                    if (id !in progress) progress = progress + (id to storedProgress)
                }
            val cached = fullQuestionCache.get(id)
            val expected = questions.firstOrNull { it.id == id }?.contentHash
            if (cached != null && cached.contentHash == expected) cached
            else repo.question(id)?.also { fullQuestionCache.put(id, it) }
        }

    fun updateSettings(transform: (LearningSettings) -> LearningSettings) = launchTask {
        learning.updateSettings(transform)
    }

    fun markNotificationRequested(done: () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    learning.updateSettings { it.copy(notificationPermissionAsked = true) }
                }
                done()
            } catch (e: Exception) {
                notice = "通知设置保存失败，请稍后重试"
            }
        }
    }

    fun savePersonalAnswer(q: Question, selected: Set<String>, notes: String) = launchTask {
        repo.savePersonalAnswer(q, selected, notes)
        notice = "个人核对答案已保存，原题库未修改"
    }

    fun deletePersonalAnswer(id: String) = launchTask {
        repo.dao.deletePersonalAnswer(id)
        notice = "已移除个人核对答案"
    }

    fun savePerceivedDifficulty(id: String, difficulty: String) = launchTask {
        repo.savePerceivedDifficulty(id, difficulty)
        notice = "难度感受已保存"
    }

    fun saveVerifiedSolution(id: String, code: String, verified: Boolean) = launchTask {
        repo.saveVerifiedSolution(id, code, verified)
        notice = "个人参考解法已保存"
    }

    /** Legacy whole-row replacement; new editors must use field-specific save commands. */
    fun saveMetadata(value: QuestionPersonal) = launchTask {
        LearningCodec.parseMetadata(LearningCodec.metadata(value))
        repo.dao.putPersonalMetadata(value)
        notice = "个人标记已保存"
    }

    fun importPersonalUri(uri: Uri) = launchTask {
        val n = repo.importPersonalAnswers(readUri(uri))
        notice = "已导入 $n 条个人核对答案"
    }

    fun exportPersonalUri(uri: Uri) = launchTask {
        val text = repo.exportPersonalAnswers()
        val out =
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                ?: error("无法写入文件")
        out.bufferedWriter().use { it.write(text) }
        notice = "个人核对答案已导出"
    }

    fun loadErrorLog() = launchTask { errorLogText = errorLog.read() }

    fun clearErrorLog() = launchTask {
        errorLog.clear()
        errorLogText = "暂无错误记录"
    }

    fun startWeakPractice() {
        val weak =
            learning.categories
                .filter { it.automatic + it.manual >= 3 && it.rate < 0.65 }
                .map { it.category }
                .toSet()
        val list =
            questions
                .filter { it.daily && it.normalizedCategory in weak }
                .sortedByDescending { p(it.id).wrong }
                .take(20)
        if (list.isEmpty()) {
            notice = "暂无足够的薄弱知识点记录（至少 3 次作答且正确率低于 65%）"
            return
        }
        start(list, "薄弱知识点练习")
    }

    override fun onCleared() {
        cleared = true
        val writer = examState.onOwnerCleared()
        val operation = examOperationJob
        // Cancel collection first; a Room reader already in IO must finish before closing its pool.
        val owner = viewModelScope.coroutineContext[Job]
        owner?.cancel()
        codeDrafts.writer.close()
        notesState.shutdown()
        quickNotes.shutdown()
        if (writer.isCompleted && (operation == null || operation.isCompleted) &&
            (owner == null || owner.isCompleted)) {
            db.close(); examResourcesClosed = Job().apply { complete() }
        }
        else {
            val database = db
            examResourcesClosed = CoroutineScope(Dispatchers.IO).launch {
                writer.join()
                operation?.join()
                owner?.join()
                database.close()
            }
        }
    }
}
