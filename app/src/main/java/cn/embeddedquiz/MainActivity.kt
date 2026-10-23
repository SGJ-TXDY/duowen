package cn.embeddedquiz

import android.os.Bundle
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId

class MainActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent {
        MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFF126B62),secondary=Color(0xFF426278),background=Color(0xFFF5F8F7))) {
            Surface(Modifier.fillMaxSize()) { QuizApp(viewModel()) }
        }
    } }
}
@Composable fun Action(text: String, enabled: Boolean=true, onClick: ()->Unit) { Button(onClick=onClick,enabled=enabled) { Text(text) } }
@Composable fun Heading(text: String) { Text(text,style=MaterialTheme.typography.titleLarge) }
@Composable fun Detail(text: String) { SelectionContainer { Text(text,style=MaterialTheme.typography.bodyLarge,fontFamily=FontFamily.Monospace) } }
@Composable fun QuizApp(vm: QuizViewModel) {
    val practiceState = key(vm.practiceSession) { rememberSaveableStateHolder() }
    BackHandler(enabled=vm.page!="首页") {
        if(vm.page=="便签") {
            if(vm.noteDraft!=null) vm.closeStickyNote() else vm.leaveStickyNotes()
        } else vm.page="首页"
    }
    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) { while(true) { today=LocalDate.now(); delay(15000) } }
    // Keep the exam deadline active even while the question page is not composed.
    LaunchedEffect(vm.ready,vm.mode,vm.examDeadline,vm.examFinished) {
        if(vm.ready && vm.mode=="模拟考试" && !vm.examFinished && vm.examDeadline>0) {
            while(!vm.examFinished) {
                if(System.currentTimeMillis()>=vm.examDeadline) vm.finishExam()
                delay(1000)
            }
        }
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal=16.dp)) {
        Text("EmbeddedQuiz",style=MaterialTheme.typography.headlineSmall,color=MaterialTheme.colorScheme.primary)
        Text("嵌入式面试助手 · 离线学习",style=MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            listOf("首页","便签","分类","智能复习","编程专区","错题收藏","模拟考试","历史统计","设置").forEach { name ->
                FilterChip(selected=vm.page==name,onClick={ vm.navigate(name) },label={Text(name)})
            }
        }
        if(vm.notice.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(8.dp)) { Text(vm.notice,Modifier.weight(1f)); TextButton(onClick={vm.notice=""}) { Text("关闭") } } }
        }
        if(vm.busy || !vm.ready) LinearProgressIndicator(Modifier.fillMaxWidth())
        if(vm.ready) when(vm.page) {
            "首页" -> Home(vm,today)
            "分类" -> Browse(vm,"分类")
            "智能复习" -> Review(vm,today)
            "编程专区" -> Browse(vm,"编程专区")
            "错题收藏" -> Browse(vm,"错题收藏")
            "模拟考试" -> Browse(vm,"模拟考试")
            "历史统计" -> History(vm)
            "设置" -> Settings(vm)
            "便签" -> StickyNotesScreen(vm)
            "刷题" -> practiceState.SaveableStateProvider("question-${vm.index}") { QuestionScreen(vm) }
        }
    }
}
@Composable fun Home(vm: QuizViewModel,today: LocalDate) {
    val daily=vm.questions.filter { it.daily }; val day=today.toEpochDay()
    val done=vm.attempts.filter { it.day==day }
    LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=24.dp)) {
        item { Heading("今天，稳步积累") }
        item { Text("$today · 每一次回忆，都让知识更牢固") }
        item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Heading("${vm.due(day).size} 题待复习")
            Text("今日已完成 ${done.map { it.questionId }.distinct().size} 题")
            Text("新题学习 ${done.filter { it.wasNew }.map { it.questionId }.distinct().size} · 复习完成 ${done.filter { it.mode=="到期复习" }.map { it.questionId }.distinct().size}")
            Action("开始今日复习") { vm.start(vm.due(day),"到期复习") }
        } } }
        item { Heading("继续学习") }
        item { Text("未学新题 ${daily.count { vm.p(it.id).seen==0 }} · 日常题库 ${daily.size} · 编程专区 ${vm.questions.count { !it.daily }}") }
        item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Action("学习新题") { vm.start(daily.filter { vm.p(it.id).seen==0 },"新题") }
            Action("随机练习") { vm.start(daily.shuffled(),"随机练习") }
        } }
        item { Action("顺序练习") { vm.start(daily,"顺序练习") } }
        if(vm.queue.isNotEmpty()) item { Action(if(vm.mode=="模拟考试"&&!vm.examFinished)"继续未交卷考试" else "继续上次练习") {vm.page="刷题"} }
        item { Card { Text("有已核对标准选项的题目提交后直接判定正确或错误；其余题目自行核对后选择正确、错误或不会。答题时可随时打开便签，再继续原题。",Modifier.padding(16.dp)) } }
    }
}
@Composable fun Picker(label: String, value: String, options: List<String>, onChange: (String)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box { OutlinedButton(onClick={expanded=true}) { Text("$label：$value") }
        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false},modifier=Modifier.heightIn(max=360.dp)) {
            options.distinct().forEach { x -> DropdownMenuItem(text={Text(x)},onClick={onChange(x);expanded=false}) }
        }
    }
}
@Composable fun Browse(vm: QuizViewModel,section: String) {
    var search by rememberSaveable(section) { mutableStateOf("") }
    var company by rememberSaveable(section) { mutableStateOf("全部") }
    var category by rememberSaveable(section) { mutableStateOf("全部") }
    var kind by rememberSaveable(section) { mutableStateOf("全部") }
    var difficulty by rememberSaveable(section) { mutableStateOf("全部") }
    var group by rememberSaveable(section) { mutableStateOf("错题") }
    var source by rememberSaveable(section) { mutableStateOf("全部") }
    val base=vm.questions.filter { if(section=="编程专区") !it.daily else it.daily }
    val filtered=base.filter { q -> (company=="全部"||q.company==company) && (category=="全部"||q.category==category) &&
        (kind=="全部"||q.kind.label==kind) && (difficulty=="全部"||q.difficulty==difficulty) && (source=="全部"||q.source==source) &&
        (search.isBlank()||("${q.stem} ${q.answer} ${q.category}").contains(search,true)) &&
        (section!="错题收藏" || when(group){"错题"->vm.p(q.id).wrong;"不会"->vm.p(q.id).wrong&&vm.attempts.firstOrNull {it.questionId==q.id}?.assessment==Assessment.UNKNOWN.name;"收藏"->vm.p(q.id).favorite;else->vm.p(q.id).flagged||q.reviewRequired}) }
    Column {
        OutlinedTextField(value=search,onValueChange={search=it},label={Text("搜索题干、答案、知识点")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Picker("来源",source,listOf("全部")+base.map {it.source}) {source=it}
            Picker("公司",company,listOf("全部")+base.map {it.company}) {company=it}
            Picker("知识点",category,listOf("全部")+base.map {it.category}) {category=it}
            Picker("题型",kind,listOf("全部")+base.map {it.kind.label}) {kind=it}
            Picker("难度",difficulty,listOf("全部")+base.map {it.difficulty}) {difficulty=it}
        }
        if(section=="错题收藏") Picker("查看",group,listOf("错题","不会","收藏","待核对")) {group=it}
        Text("共 ${filtered.size} 题")
        if(section=="模拟考试") {
            var count by rememberSaveable { mutableStateOf("20") }; var minutes by rememberSaveable { mutableStateOf("30") }
            Row { Picker("题量",count,listOf("10","20","50")){count=it}; Spacer(Modifier.width(8.dp)); Picker("分钟",minutes,listOf("15","30","60")){minutes=it} }
            Text("仅抽取有可靠标准答案的客观题，交卷后显示解析。考试计时和作答会保存。")
            Action("开始考试") {vm.startExam(filtered,count.toInt(),minutes.toInt())}
        } else Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Action(if(section=="编程专区")"开始阅读" else "顺序练习") {vm.start(filtered,section)}
            Action("随机") {vm.start(filtered.shuffled(),section)}
        }
        LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(bottom=24.dp)) {
            items(filtered,key={it.id}) {q -> Card(onClick={if(section=="模拟考试")vm.notice="请使用上方“开始考试”按钮，系统会筛选可判分题目" else vm.start(listOf(q),section)},modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Text("${q.kind.label} · ${q.company}",color=MaterialTheme.colorScheme.primary)
                    Text(q.stem,maxLines=3); Text("${q.source} · ${q.difficulty}",style=MaterialTheme.typography.labelSmall)
                    if(q.reviewRequired||vm.p(q.id).flagged) Text("待核对",color=MaterialTheme.colorScheme.error)
                }
            } }
        }
    }
}
@Composable fun Review(vm: QuizViewModel,today: LocalDate) {
    val due=vm.due(today.toEpochDay())
    LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item { Heading("智能复习") }
        item { Text("今日到期 ${due.size} · 连续掌握 ${vm.progress.values.count {it.repetitions>=3}} · 未学 ${vm.questions.count {it.daily&&vm.p(it.id).seen==0}}") }
        item { Text("答错或不会后隔 1 天重学；初次答对隔 1 天、第二次隔 6 天，随后逐步延长复习间隔。") }
        item { Action("错题优先开始") {vm.start(due,"到期复习")} }
        items(vm.questions.filter {it.daily&&vm.p(it.id).seen>0}.sortedBy {vm.p(it.id).due},key={it.id}) {q ->
            val p=vm.p(q.id)
            Card(onClick={vm.start(listOf(q),"复习浏览")},modifier=Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Text(q.stem,maxLines=2); Text("下次 ${LocalDate.ofEpochDay(p.due)} · 连续 ${p.repetitions} 次 · 间隔 ${p.interval} 天 · 系数 ${"%.2f".format(p.ease)}")
            } }
        }
    }
}
@Composable fun QuestionScreen(vm: QuizViewModel) {
    val q=vm.queue.getOrNull(vm.index)?.let {id->vm.questions.find {it.id==id}}
    if(q==null) { Text("题目加载中或已被恢复操作替换"); return }
    key(q.id,vm.mode) { QuestionContent(vm,q) }
}
@Composable fun QuestionContent(vm: QuizViewModel,q: Question) {
    var selected by rememberSaveable(q.id) {mutableStateOf(if(vm.mode=="模拟考试")vm.examAnswers[q.id]?.toList() ?: emptyList() else emptyList())}
    var response by rememberSaveable(q.id) {mutableStateOf("")}
    var revealed by rememberSaveable(q.id) {mutableStateOf(false)}
    val recorded=vm.practiceRecords.containsKey(q.id)
    val savedAssessment=vm.practiceRecords[q.id]?.takeUnless {it=="AUTO"}.orEmpty()
    var code by rememberSaveable(q.id) {mutableStateOf(vm.p(q.id).code)}
    var notes by rememberSaveable(q.id) {mutableStateOf(vm.p(q.id).notes)}
    var prompt by rememberSaveable(q.id) {mutableStateOf("")}
    var remaining by remember {mutableLongStateOf(0)}
    val exam=vm.mode=="模拟考试" && !vm.examFinished
    LaunchedEffect(exam,vm.examDeadline) {
        if(exam) while(!vm.examFinished) {
            remaining=((vm.examDeadline-System.currentTimeMillis())/1000).coerceAtLeast(0)
            delay(1000)
        }
    }
    LazyColumn(Modifier.testTag("question-list"),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=32.dp)) {
        item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("${vm.mode} · ${vm.index+1} / ${vm.queue.size}",color=MaterialTheme.colorScheme.primary,modifier=Modifier.weight(1f))
            OutlinedButton(onClick=vm::openStickyNotes) { Text("打开便签") }
        } }
        if(exam) item { Text("剩余 ${remaining/60}:${(remaining%60).toString().padStart(2,'0')} · 已答 ${vm.examAnswers.count {it.value.isNotEmpty()}} 题") }
        if(vm.examFinished && vm.mode=="模拟考试") item { Heading(vm.examResult) }
        item { Text("${q.kind.label} · ${q.category} · ${q.difficulty}") }
        item { Detail(q.stem) }
        items(q.images) {pic ->
            val bitmap=remember(pic.data) {runCatching {val b=Base64.decode(pic.data,Base64.DEFAULT); BitmapFactory.decodeByteArray(b,0,b.size)?.asImageBitmap()}.getOrNull()}
            if(bitmap!=null) Image(bitmap,contentDescription=pic.name,modifier=Modifier.fillMaxWidth().heightIn(max=420.dp)) else Text("图片无法显示：${pic.name}")
        }
        items(q.options) {o ->
            OutlinedButton(onClick={
                selected=if(q.kind==Kind.MULTIPLE) {if(o.key in selected) selected-o.key else selected+o.key} else listOf(o.key)
                if(exam) vm.answerExam(q.id,selected.toSet())
            },enabled=if(exam)remaining>0&&!vm.busy else !revealed&&!recorded&&!vm.examFinished,modifier=Modifier.fillMaxWidth()) {Text("${if(o.key in selected)"☑" else "□"} ${o.text}")}
        }
        if(!q.objective && q.daily) item {OutlinedTextField(value=response,onValueChange={response=it},label={Text("先写下自己的回答")},modifier=Modifier.fillMaxWidth(),minLines=3,readOnly=revealed||vm.examFinished)}
        if(!exam) {
            item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Action(if(vm.p(q.id).favorite)"已收藏" else "收藏") {vm.toggleFavorite(q.id)}
                Action(if(vm.p(q.id).flagged)"取消质疑" else "标记待核对") {vm.toggleFlag(q.id)}
            } }
            item {Text("来源：${q.source}\n${q.locator}",style=MaterialTheme.typography.labelSmall)}
            if(q.duplicateOf.isNotBlank()) item {Text("同题关联：${q.duplicateOf}（来源和学习记录独立）")}
            if(q.reviewRequired) item {Text("待核对：答案缺失或需结合条件核对，不自动判分，可手动判断。",color=MaterialTheme.colorScheme.error)}
            if(!revealed && !recorded && !vm.examFinished) item {
                Action(if(q.autoGradable)"提交答案" else "查看参考答案",enabled=!vm.busy&&(!q.autoGradable||selected.isNotEmpty())) {
                    if(q.autoGradable) vm.submit(q,response,selected.toSet()) { revealed=true }
                    else revealed=true
                }
            }
            if(revealed || recorded || vm.examFinished) {
                val grade=Grader.grade(q,selected.toSet())
                item { Heading(if(savedAssessment.isNotBlank())"已记录 · 自评：${Assessment.valueOf(savedAssessment).label}" else if(grade==true)"自动判分：正确" else if(grade==false)"自动判分：错误" else "自行核对参考") }
                item { Detail(q.answer.ifBlank {if(q.correct.isNotEmpty())"标准选项：${q.correct.joinToString()}" else "原文未提供答案"}) }
                if(q.explanation.isNotBlank()) item {Detail(q.explanation)}
                if(q.daily && !q.autoGradable && !recorded && !vm.examFinished) item { Column {
                    Text("手动判断本次回答（只记录你的掌握情况，不修改参考答案）")
                    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        Assessment.entries.forEach {a->Action(a.label,enabled=!vm.busy) {vm.assess(q,response,selected.toSet(),a) {}}}
                    }
                    Text("正确会延长复习间隔；错误和不会加入错题本，次日再复习。")
                } }
                if(recorded) item {Text("已保存 · 下次复习 ${LocalDate.ofEpochDay(vm.p(q.id).due)}")}
            }
            if(!q.daily) {
                item {Heading("编程题工作区")}
                item {Text("输入输出示例"); Detail(q.examples.ifBlank {"原文未单列，请查看完整题干"})}
                item {Text("约束条件"); Detail(q.constraints.ifBlank {"原文未单列"})}
                if(revealed) {
                    item {Text("解题思路"); Detail(q.approach.ifBlank {"原文未提供"})}
                    item {Text("参考代码"); Detail(q.referenceCode.ifBlank {"原文未提供"})}
                }
                item {OutlinedTextField(value=code,onValueChange={code=it},label={Text("我的代码（手动保存）")},textStyle=LocalTextStyle.current.copy(fontFamily=FontFamily.Monospace),modifier=Modifier.fillMaxWidth(),minLines=6)}
                item {Text("当前版本不执行或在线判定代码，请在自己的开发环境验证。")}
            }
            item {OutlinedTextField(value=notes,onValueChange={notes=it},label={Text("个人笔记（手动保存）")},modifier=Modifier.fillMaxWidth(),minLines=2)}
            item {Action("保存代码与笔记",!vm.busy) {vm.saveNotes(q.id,code,notes)}}
            item {HorizontalDivider(); Heading("AI 学习助手")}
            item {Text("点击后将本题、参考答案、所选选项和最近讨论发送到 DeepSeek。AI 内容独立存储，不能替代核对。")}
            item {Action(if(vm.aiBusy)"正在分析…" else "生成详细解析",!vm.aiBusy) {vm.ask(q,"生成详细解析",selected.toSet())}}
            items(vm.aiEntries.filter {it.questionId==q.id},key={it.cacheKey}) {a -> Card {Column(Modifier.padding(14.dp)) {
                Text("${a.model} · ${a.prompt}",style=MaterialTheme.typography.titleSmall)
                Detail(a.response); Text("输入 ${a.inputTokens} / 输出 ${a.outputTokens} tokens · 估算 $${"%.6f".format(a.estimatedUsd)}")
            }} }
            item {OutlinedTextField(value=prompt,onValueChange={prompt=it},label={Text("追问、错误选项分析、举例或质疑")},modifier=Modifier.fillMaxWidth())}
            item {Action("发送追问",!vm.aiBusy&&prompt.isNotBlank()) {vm.ask(q,prompt,selected.toSet());prompt=""}}
        }
        item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Action("上一题",vm.index>0) {vm.index--;vm.saveExam()}
            Action("下一题",vm.index<vm.queue.lastIndex) {vm.index++;vm.saveExam()}
            if(exam) Action("交卷",!vm.busy) {vm.finishExam()}
        }}
    }
}
@Composable fun StickyNotesScreen(vm: QuizViewModel) {
    var search by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<StickyNote?>(null) }
    val draft=vm.noteDraft
    deleting?.let { note -> AlertDialog(onDismissRequest={deleting=null},title={Text("删除便签？")},
        text={Text("将删除“${note.title.ifBlank { "未命名便签" }}”，此操作无法撤销。")},
        confirmButton={TextButton(onClick={vm.deleteStickyNote(note);deleting=null}) {Text("删除")}},
        dismissButton={TextButton(onClick={deleting=null}) {Text("取消")}}) }
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Heading("便签")
            if(vm.queue.isNotEmpty()) OutlinedButton(onClick={vm.page="刷题"}) {Text("继续答题")}
            else TextButton(onClick=vm::leaveStickyNotes) {Text("返回")}
        }
        if(vm.mode=="模拟考试" && !vm.examFinished && vm.queue.isNotEmpty()) Text("考试仍在计时，作答进度已保留。",color=MaterialTheme.colorScheme.error)
        if(vm.noteSaveError.isNotBlank()) {
            Text(vm.noteSaveError,color=MaterialTheme.colorScheme.error)
            Action("重试保存",!vm.busy&&vm.pendingNoteWrites==0) {vm.retryStickyNotes()}
        } else Text(if(vm.pendingNoteWrites>0)"正在保存…" else "自动保存到本机",style=MaterialTheme.typography.labelMedium)
        if(draft!=null) {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick=vm::closeStickyNote) {Text("全部便签")}
                TextButton(onClick={deleting=draft},enabled=!vm.busy) {Text("删除便签")}
            }
            key(draft.id) {
                OutlinedTextField(value=draft.title,onValueChange={vm.updateStickyNote(it,draft.content)},label={Text("便签标题")},
                    modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=!vm.busy)
                OutlinedTextField(value=draft.content,onValueChange={vm.updateStickyNote(draft.title,it)},label={Text("记下思路、易错点或待办")},
                    modifier=Modifier.fillMaxWidth().weight(1f).imePadding(),enabled=!vm.busy)
            }
        } else {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Action("新建便签",!vm.busy) {vm.newStickyNote()}
                Text("共 ${vm.stickyNotes.size} 条",Modifier.padding(top=12.dp))
            }
            OutlinedTextField(value=search,onValueChange={search=it},label={Text("搜索标题或内容")},modifier=Modifier.fillMaxWidth(),singleLine=true)
            val filtered=vm.stickyNotes.filter {search.isBlank()||it.title.contains(search,true)||it.content.contains(search,true)}
            LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(bottom=24.dp)) {
                if(filtered.isEmpty()) item {Text(if(search.isBlank())"还没有便签，随时记下学习中的想法。" else "没有匹配的便签")}
                items(filtered,key={it.id}) {note -> Card(onClick={vm.editStickyNote(note)},enabled=!vm.busy&&vm.pendingNoteWrites==0,modifier=Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=Color(0xFFFFF4CD))) {
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text(note.title.ifBlank {"未命名便签"},style=MaterialTheme.typography.titleMedium,maxLines=2)
                        Text(note.content.ifBlank {"空白便签"},maxLines=4)
                        Text("更新于 ${Instant.ofEpochMilli(note.updated).atZone(ZoneId.systemDefault()).toLocalDateTime().withSecond(0).withNano(0)}",style=MaterialTheme.typography.labelSmall)
                    }
                } }
            }
        }
    }
}
@Composable fun History(vm: QuizViewModel) {
    val graded=vm.attempts.filter {it.correct!=null}
    LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item {Heading("学习统计与历史")}
        item {Text("累计 ${vm.attempts.size} 次 · 学习 ${vm.attempts.map {it.questionId}.distinct().size} 题\n已判定/自评 ${graded.size} 次，正确 ${graded.count {it.correct==true}} 次，其中不会 ${graded.count {it.assessment==Assessment.UNKNOWN.name}} 次\n未手动判断且无法自动判分的记录不计入正确率。")}
        items(vm.attempts,key={it.id}) {a -> Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp)) {
            Text(vm.questions.find {it.id==a.questionId}?.stem ?: a.questionId,maxLines=2)
            Text("${Instant.ofEpochMilli(a.timestamp).atZone(ZoneId.systemDefault()).toLocalDateTime()} · ${a.mode}")
            Text(if(a.assessment.isNotBlank())"自评：${Assessment.valueOf(a.assessment).label}" else if(a.correct==null)"未判分" else if(a.correct)"正确" else "错误")
            Text(a.response.ifBlank {"未填写文本回答"},maxLines=4)
        }} }
    }
}
@Composable fun Settings(vm: QuizViewModel) {
    var key by remember {mutableStateOf("")}
    var confirm by remember {mutableStateOf("")}
    val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {it?.let(vm::importUri)}
    val restore=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {it?.let(vm::restoreUri)}
    val backup=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {it?.let(vm::backupUri)}
    if(confirm.isNotBlank()) AlertDialog(onDismissRequest={confirm=""},title={Text(confirm)},text={Text(if(confirm=="重置复习进度")"清除所有答题历史与复习安排，保留收藏、代码、笔记、便签和 AI 缓存。" else "选择备份后将替换全部题库、进度、收藏、笔记、便签和 AI 缓存。旧版备份不含便签时保留当前便签。API Key 保持不变。")},confirmButton={TextButton(onClick={if(confirm=="重置复习进度")vm.resetProgress() else restore.launch(arrayOf("application/json","text/plain","application/octet-stream"));confirm=""}){Text("继续")}},dismissButton={TextButton(onClick={confirm=""}){Text("取消")}})
    LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=30.dp)) {
        item {Heading("DeepSeek 设置")}
        item {Text("官方地址 https://api.deepseek.com\n${if(vm.hasKey)"已保存加密凭据" else "未设置 API Key"}")}
        item {OutlinedTextField(value=key,onValueChange={key=it},visualTransformation=PasswordVisualTransformation(),label={Text("输入新的 API Key")},singleLine=true,modifier=Modifier.fillMaxWidth())}
        item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {Action("保存 Key",key.isNotBlank()&&!vm.busy){vm.saveKey(key);key=""};Action("删除 Key",!vm.busy){vm.saveKey("");key=""}}}
        item {Picker("模型",vm.model,listOf("deepseek-flash","deepseek-v4-pro"),vm::chooseModel)}
        item {Text("累计输入 ${vm.aiEntries.sumOf {it.inputTokens}} / 输出 ${vm.aiEntries.sumOf {it.outputTokens}} tokens\n估算费用 $${"%.6f".format(vm.aiEntries.sumOf {it.estimatedUsd})} USD。按 2026-10-21 高峰费率估算，实际以官方账单为准；读取本地缓存不收费。")}
        item {HorizontalDivider();Heading("题库与数据")}
        item {Text("${vm.questions.size} 题 · ${vm.questions.count {it.reviewRequired}} 题待核对\nJSON 导入采用相同 ID 跳过策略，不覆盖已有答案。备份包含全部题目、图片、进度、历史、笔记、便签和 AI 解析，不含 Key。")}
        item {Action("导入 JSON 题库",!vm.busy){import.launch(arrayOf("application/json","text/plain","application/octet-stream"))}}
        item {Action("导出完整备份",!vm.busy){backup.launch("EmbeddedQuiz-${LocalDate.now()}.json")}}
        item {Action("恢复备份",!vm.busy){confirm="恢复备份"}}
        item {Action("重置复习进度",!vm.busy){confirm="重置复习进度"}}
    }
}
