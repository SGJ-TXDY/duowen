package cn.embeddedquiz

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import androidx.compose.runtime.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import androidx.room.withTransaction

class QuizViewModel(app: Application): AndroidViewModel(app) {
    val db = QuizDatabase.open(app)
    val repo = QuizRepository(db)
    val vault = KeyVault(app)
    private val prefs=app.getSharedPreferences("settings",0)
    var questions by mutableStateOf<List<Question>>(emptyList()); private set
    var progress by mutableStateOf<Map<String,Progress>>(emptyMap()); private set
    var attempts by mutableStateOf<List<Attempt>>(emptyList()); private set
    var aiEntries by mutableStateOf<List<AiEntry>>(emptyList()); private set
    var stickyNotes by mutableStateOf<List<StickyNote>>(emptyList()); private set
    var noteDraft by mutableStateOf<StickyNote?>(null); private set
    var pendingNoteWrites by mutableIntStateOf(0); private set
    var noteSaveError by mutableStateOf(""); private set
    private data class NoteWrite(val note: StickyNote? = null, val delete: Boolean = false, val barrier: CompletableDeferred<Unit>? = null)
    private val noteWrites = Channel<NoteWrite>(Channel.UNLIMITED)
    private val failedNoteWrites = mutableMapOf<String,NoteWrite>()
    private var notesReturnPage = "首页"
    var ready by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var aiBusy by mutableStateOf(false); private set
    var notice by mutableStateOf("")
    var model by mutableStateOf(prefs.getString("model","deepseek-flash")!!); private set
    var hasKey by mutableStateOf(vault.exists()); private set
    var page by mutableStateOf("首页")
    var queue by mutableStateOf<List<String>>(emptyList())
    var index by mutableIntStateOf(0)
    var mode by mutableStateOf("顺序练习")
    var practiceSession by mutableIntStateOf(0); private set
    var practiceRecords by mutableStateOf<Map<String,String>>(emptyMap()); private set
    var examDeadline by mutableLongStateOf(0)
    var examAnswers by mutableStateOf<Map<String,Set<String>>>(emptyMap())
    var examFinished by mutableStateOf(false)
    var examResult by mutableStateOf("")
    init {
        // One ordered writer prevents an older keystroke from overwriting a newer edit or deletion.
        viewModelScope.launch {
            for (write in noteWrites) {
                if (write.barrier != null) { write.barrier.complete(Unit); continue }
                try {
                    withContext(Dispatchers.IO) {
                        val note = requireNotNull(write.note)
                        if (write.delete) repo.dao.deleteStickyNote(note.id) else repo.dao.putStickyNote(note)
                    }
                    failedNoteWrites.remove(requireNotNull(write.note).id)
                    if (failedNoteWrites.isEmpty()) noteSaveError = ""
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failedNoteWrites[requireNotNull(write.note).id] = write
                    noteSaveError = "便签保存失败，请重试：${e.message ?: "存储不可用"}"
                } finally { pendingNoteWrites-- }
            }
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if(repo.dao.allQuestions().isEmpty() || prefs.getInt("bankRevision",0)<4) {
                        repo.upgradeBundledBank(app.assets.open("bank.json").bufferedReader().use { it.readText() })
                        check(prefs.edit().putInt("bankRevision",4).commit())
                    }
                }
                launch { repo.dao.questions().collect { rows -> questions=withContext(Dispatchers.Default) { rows.map { BankCodec.parseQuestion(JSONObject(it.json)) } } } }
                launch { repo.dao.progress().collect { progress=it.associateBy { p -> p.questionId } } }
                launch { repo.dao.attempts().collect { attempts=it } }
                launch { repo.dao.ai().collect { aiEntries=it } }
                launch { repo.dao.stickyNotes().collect { stickyNotes=it } }
                prefs.getString("exam",null)?.let { saved -> runCatching {
                    val o=JSONObject(saved); val ids=o.getJSONArray("ids")
                    queue=(0 until ids.length()).map { ids.getString(it) }; index=o.getInt("index").coerceIn(0,(queue.size-1).coerceAtLeast(0))
                    examDeadline=o.getLong("deadline"); val a=o.getJSONObject("answers")
                    examAnswers=a.keys().asSequence().associateWith { k -> val v=a.getJSONArray(k); (0 until v.length()).map { v.getString(it) }.toSet() }
                    mode="模拟考试"; page="刷题"
                }.onFailure { prefs.edit().remove("exam").apply() } }
                ready=true
            } catch(e: Exception) { notice="题库加载失败：${e.message}" }
        }
    }
    fun p(id: String) = progress[id] ?: Progress(id)
    fun navigate(name: String) { if (name == "便签") openStickyNotes() else page=name }
    fun openStickyNotes() {
        if (page != "便签") notesReturnPage=page
        page="便签"
    }
    fun leaveStickyNotes() { page=if (notesReturnPage=="刷题" && queue.isEmpty()) "首页" else notesReturnPage }
    fun editStickyNote(note: StickyNote) { noteDraft=note }
    fun closeStickyNote() { noteDraft=null }
    fun newStickyNote() {
        val now=System.currentTimeMillis()
        noteDraft=StickyNote(java.util.UUID.randomUUID().toString(),created=now,updated=now)
        enqueueNote(requireNotNull(noteDraft))
    }
    fun updateStickyNote(title: String, content: String) {
        val old=noteDraft ?: return
        val note=old.copy(title=title,content=content,updated=maxOf(old.created,System.currentTimeMillis()))
        noteDraft=note
        enqueueNote(note)
    }
    fun retryStickyNotes() {
        failedNoteWrites.values.toList().forEach { enqueueNote(requireNotNull(it.note),it.delete) }
    }
    fun deleteStickyNote(note: StickyNote) {
        if (noteDraft?.id == note.id) noteDraft=null
        enqueueNote(note,delete=true)
    }
    private fun enqueueNote(note: StickyNote, delete: Boolean=false) {
        pendingNoteWrites++
        check(noteWrites.trySend(NoteWrite(note,delete)).isSuccess)
    }
    suspend fun flushStickyNotes() {
        val barrier=CompletableDeferred<Unit>()
        noteWrites.send(NoteWrite(barrier=barrier)); barrier.await()
        check(noteSaveError.isEmpty()) { noteSaveError }
    }
    fun due(today: Long=LocalDate.now().toEpochDay()) = questions.filter { it.daily && p(it.id).seen>0 && p(it.id).due<=today }.sortedWith(compareByDescending<Question> { p(it.id).wrong }.thenBy { p(it.id).due })
    fun launchTask(action: suspend ()->Unit) {
        if(busy) return
        busy=true
        viewModelScope.launch { try { withContext(Dispatchers.IO) { action() } } catch(e: Exception) { notice="操作未完成：${e.message ?: "请重试"}" } finally { busy=false } }
    }
    fun start(list: List<Question>, selectedMode: String) {
        if(prefs.contains("exam") && !examFinished) { notice="请先继续并交卷当前考试，再开始其他练习"; page="刷题"; return }
        if(list.isEmpty()) { notice="当前没有符合条件的题目"; return }
        practiceSession++; practiceRecords=emptyMap(); queue=list.map { it.id }; index=0; mode=selectedMode; page="刷题"; examFinished=false; examResult=""
    }
    fun startExam(list: List<Question>, count: Int, minutes: Int) {
        if(prefs.contains("exam") && !examFinished) {notice="已有未交卷考试，请先完成";page="刷题";return}
        val eligible=list.filter { it.daily && it.autoGradable }
        if(eligible.isEmpty()) { notice="当前筛选范围没有可判分客观题，请扩大筛选范围或导入含已核对标准答案的 JSON。"; return }
        start(eligible.shuffled().take(count),"模拟考试"); examAnswers=emptyMap(); examDeadline=System.currentTimeMillis()+minutes*60000L; saveExam()
    }
    fun saveExam() {
        if(mode!="模拟考试" || examFinished) return
        val o=JSONObject().put("ids",JSONArray(queue)).put("index",index).put("deadline",examDeadline)
        val answers=JSONObject(); examAnswers.forEach { (k,v)->answers.put(k,JSONArray(v.toList())) }; o.put("answers",answers)
        prefs.edit().putString("exam",o.toString()).commit()
    }
    fun answerExam(id: String, answer: Set<String>) {
        if(System.currentTimeMillis()>=examDeadline) {finishExam();return}
        examAnswers=examAnswers+(id to answer); saveExam()
    }
    fun finishExam() {
        if(busy || examFinished) return
        launchTask {
            var correct=0
            val selected=queue.mapNotNull { id -> questions.find { it.id==id } }
            db.withTransaction {
                selected.forEach { q ->
                    val a=examAnswers[q.id] ?: emptySet(); val good=Grader.grade(q,a)==true
                    if(good) correct++
                    repo.record(q,a.joinToString(),a,"模拟考试")
                }
            }
            withContext(Dispatchers.Main) { examFinished=true; examResult="答对 $correct / ${selected.size}，得分 ${if(selected.isEmpty())0 else correct*100/selected.size}"; prefs.edit().remove("exam").commit() }
        }
    }
    fun submit(q: Question, text: String, choices: Set<String>, done: ()->Unit) {
        if(practiceRecords.containsKey(q.id)) return
        val session=practiceSession; val selectedMode=mode
        launchTask {
            require(q.autoGradable) { "本题需要手动判断" }
            repo.record(q,text.ifBlank { choices.joinToString() },choices,selectedMode)
            withContext(Dispatchers.Main) {
                if(session==practiceSession) {practiceRecords=practiceRecords+(q.id to "AUTO");done()}
            }
        }
    }
    fun toggleFavorite(id: String) = launchTask { repo.update(id) { it.copy(favorite=!it.favorite) } }
    fun assess(q: Question, text: String, choices: Set<String>, result: Assessment, done: ()->Unit) {
        if(practiceRecords.containsKey(q.id)) return
        val session=practiceSession; val selectedMode=mode
        launchTask {
            require(!q.autoGradable) { "本题按标准答案自动判分" }
            repo.record(q,text.ifBlank {choices.joinToString()},choices,selectedMode,assessment=result)
            withContext(Dispatchers.Main) {
                if(session==practiceSession) {practiceRecords=practiceRecords+(q.id to result.name);done()}
            }
        }
    }
    fun toggleFlag(id: String) = launchTask { repo.update(id) { it.copy(flagged=!it.flagged) } }
    fun saveNotes(id: String, code: String, notes: String) = launchTask { repo.update(id) { it.copy(code=code,notes=notes) }; notice="代码与笔记已保存" }
    fun saveKey(value: String) = launchTask { vault.save(value); withContext(Dispatchers.Main) { hasKey=vault.exists(); notice=if(hasKey)"API Key 已加密保存" else "API Key 已删除" } }
    fun chooseModel(value: String) { model=value; prefs.edit().putString("model",value).apply() }
    fun ask(q: Question, text: String, choices: Set<String>) {
        if(aiBusy) return
        aiBusy=true
        val requestedModel=model
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val history=aiEntries.filter { it.questionId==q.id }.takeLast(4).joinToString("\n") { "用户：${it.prompt}\n助手：${it.response}" }
                    val prompt="题目：${q.stem}\n选项：${q.options}\n我的选择：$choices\n原始参考答案：${q.answer.ifBlank { "原文缺失" }}\n代码：${q.referenceCode}\n历史讨论：$history\n本次问题：$text"
                    // Stable standalone explanation cache; follow-ups include the last four exchanges.
                    val actual=if(text=="生成详细解析") prompt.substringBefore("\n历史讨论：")+"\n本次问题：$text" else prompt
                    val key=DeepSeekClient.hash(requestedModel+"\n"+actual)
                    if(repo.dao.cached(key)!=null) { notice="已使用缓存解析，没有产生新费用"; return@withContext }
                    val reply=DeepSeekClient().complete(vault.read(),requestedModel,actual)
                    repo.dao.putAi(AiEntry(key,q.id,requestedModel,text,reply.text,reply.input,reply.output,reply.hit,DeepSeekClient.estimate(requestedModel,reply),System.currentTimeMillis()))
                }
            } catch(e: Exception) { notice=if(e is AiFailure)e.message!! else "AI 凭据或服务不可用，请重新设置 Key 后重试" }
            finally { aiBusy=false }
        }
    }
    private fun readUri(uri: Uri): String {
        val stream=getApplication<Application>().contentResolver.openInputStream(uri) ?: error("无法打开文件")
        val bytes=stream.use { input ->
            val output=java.io.ByteArrayOutputStream()
            val buffer=ByteArray(8192)
            while(true) {val n=input.read(buffer); if(n<0)break; require(output.size()+n<=32*1024*1024) {"文件超过 32 MiB"};output.write(buffer,0,n)}
            output.toByteArray()
        }
        require(bytes.size<=32*1024*1024) { "文件超过 32 MiB" }
        return bytes.toString(Charsets.UTF_8)
    }
    fun importUri(uri: Uri) = launchTask { val (added,skipped)=repo.importBank(readUri(uri)); notice="新增 $added 题，跳过 $skipped 个已有 ID（保留旧答案与进度）" }
    fun restoreUri(uri: Uri) = launchTask { flushStickyNotes(); repo.restore(readUri(uri)); prefs.edit().remove("exam").commit(); withContext(Dispatchers.Main) { practiceSession++; practiceRecords=emptyMap(); queue=emptyList(); noteDraft=null; page="首页"; notice="备份已恢复" } }
    fun backupUri(uri: Uri) = launchTask {
        flushStickyNotes()
        val text=repo.backup()
        val out=getApplication<Application>().contentResolver.openOutputStream(uri,"wt") ?: error("无法写入文件")
        out.bufferedWriter().use { it.write(text) }; notice="备份完成（不包含 API Key）"
    }
    fun resetProgress() = launchTask { repo.reset(); prefs.edit().remove("exam").commit(); withContext(Dispatchers.Main) { practiceSession++; practiceRecords=emptyMap(); queue=emptyList(); notice="复习和历史已重置；收藏、笔记和便签保留" } }
    override fun onCleared() { noteWrites.close(); db.close() }
}
