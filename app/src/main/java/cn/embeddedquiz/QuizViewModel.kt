package cn.embeddedquiz

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject

class QuizViewModel(app: Application) : AndroidViewModel(app) {
    val db = QuizDatabase.open(app)
    val repo = QuizRepository(db)
    val vault = KeyVault(app)
    private val prefs = app.getSharedPreferences("settings", 0)
    var questions by mutableStateOf<List<Question>>(emptyList())
        private set

    var progress by mutableStateOf<Map<String, Progress>>(emptyMap())
        private set

    var attempts by mutableStateOf<List<Attempt>>(emptyList())
        private set

    private var notesReturnPage = Page.HOME
    var ready by mutableStateOf(false)
        private set

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

    val notesState = NotesStateHolder(viewModelScope, repo)
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

    val examState = ExamStateHolder(prefs, repo)
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

    init {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (repo.dao.allQuestions().isEmpty() || prefs.getInt("bankRevision", 0) < 4) {
                        repo.upgradeBundledBank(
                            app.assets.open("bank.json").bufferedReader().use { it.readText() }
                        )
                        prefs.writeDurably { putInt("bankRevision", 4) }
                    }
                }
                launch {
                    repo.dao.questions().collect { rows ->
                        questions =
                            withContext(Dispatchers.Default) {
                                rows.map { BankCodec.parseQuestion(JSONObject(it.json)) }
                            }
                    }
                }
                launch {
                    repo.dao.progress().collect { progress = it.associateBy { p -> p.questionId } }
                }
                launch { repo.dao.attempts().collect { attempts = it } }
                examState.restore()?.let { saved ->
                    queue = saved.ids
                    index = saved.index
                    mode = "模拟考试"
                    page = Page.QUESTION
                }
                ready = true
            } catch (e: Exception) {
                notice = "题库加载失败：${e.message}"
            }
        }
    }

    fun p(id: String) = progress[id] ?: Progress(id)

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
            .sortedWith(compareByDescending<Question> { p(it.id).wrong }.thenBy { p(it.id).due })

    fun launchTask(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
            } catch (e: Exception) {
                notice = "操作未完成：${e.message ?: "请重试"}"
            } finally {
                busy = false
            }
        }
    }

    fun start(list: List<Question>, selectedMode: String) {
        if (prefs.contains("exam") && !examFinished) {
            notice = "请先继续并交卷当前考试，再开始其他练习"
            page = Page.QUESTION
            return
        }
        if (list.isEmpty()) {
            notice = "当前没有符合条件的题目"
            return
        }
        practiceSession++
        practiceRecords = emptyMap()
        queue = list.map { it.id }
        index = 0
        mode = selectedMode
        page = Page.QUESTION
        examFinished = false
        examResult = ""
    }

    fun startExam(list: List<Question>, count: Int, minutes: Int) {
        if (prefs.contains("exam") && !examFinished) {
            notice = "已有未交卷考试，请先完成"
            page = Page.QUESTION
            return
        }
        val eligible = list.filter { it.daily && it.autoGradable }
        if (eligible.isEmpty()) {
            notice = "当前筛选范围没有可判分客观题，请扩大筛选范围或导入含已核对标准答案的 JSON。"
            return
        }
        start(eligible.shuffled().take(count), "模拟考试")
        examAnswers = emptyMap()
        examDeadline = System.currentTimeMillis() + minutes * 60000L
        saveExam()
    }

    fun saveExam() = examState.save(queue, index, mode)

    fun answerExam(id: String, answer: Set<String>) {
        if (System.currentTimeMillis() >= examDeadline) {
            finishExam()
            return
        }
        examAnswers = examAnswers + (id to answer)
        saveExam()
    }

    fun finishExam() {
        if (busy || examFinished) return
        launchTask {
            val selected = queue.mapNotNull { id -> questions.find { it.id == id } }
            val result = examState.grade(selected)
            withContext(Dispatchers.Main) {
                examFinished = true
                examResult = result
            }
        }
    }

    fun submit(q: Question, text: String, choices: Set<String>, done: () -> Unit) {
        if (practiceRecords.containsKey(q.id)) return
        val session = practiceSession
        val selectedMode = mode
        launchTask {
            require(q.autoGradable) { "本题需要手动判断" }
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
            require(!q.autoGradable) { "本题按标准答案自动判分" }
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

    fun saveNotes(id: String, code: String, notes: String) = launchTask {
        repo.update(id) { it.copy(code = code, notes = notes) }
        notice = "代码与笔记已保存"
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
        repo.restore(readUri(uri))
        examState.clear()
        withContext(Dispatchers.Main) {
            practiceSession++
            practiceRecords = emptyMap()
            queue = emptyList()
            notesState.closeStickyNote()
            page = Page.HOME
            notice = "备份已恢复"
        }
    }

    fun backupUri(uri: Uri) = launchTask {
        flushStickyNotes()
        val text = repo.backup()
        val out =
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                ?: error("无法写入文件")
        out.bufferedWriter().use { it.write(text) }
        notice = "备份完成（不包含 API Key）"
    }

    fun resetProgress() = launchTask {
        repo.reset()
        examState.clear()
        withContext(Dispatchers.Main) {
            practiceSession++
            practiceRecords = emptyMap()
            queue = emptyList()
            notice = "复习和历史已重置；收藏、笔记和便签保留"
        }
    }

    override fun onCleared() {
        notesState.shutdown()
        db.close()
    }
}
