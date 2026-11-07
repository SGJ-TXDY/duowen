package cn.embeddedquiz

import androidx.room.withTransaction
import kotlinx.coroutines.ensureActive
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

class QuizRepository(val db: QuizDatabase) {
    val dao = db.dao()
    val aiConversations: AiConversationFacade get() = db.aiServices.conversations()
    val aiCosts: AiCostsRepository get() = db.aiServices.costs()
    val aiCostsState: kotlinx.coroutines.flow.StateFlow<AiCostsUiState> get() = db.aiServices.costs().state

    internal suspend fun validateStoredQuestions() {
        db.withTransaction {
            db.openHelper.readableDatabase.query("SELECT id,json FROM questions ORDER BY id").use { rows ->
                while(rows.moveToNext()) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    HistoricalQuestionV3.read(rows.getString(0),rows.getString(1))
                }
            }
        }
    }

    suspend fun reindexCategories() =
        db.withTransaction {
            dao.categoryRows().forEach {
                dao.setNormalizedCategory(it.id, CategoryMap.normalize(it.category))
            }
            dao.refreshAttemptCategories()
        }

    suspend fun record(
        q: Question,
        response: String,
        selected: Set<String>,
        mode: String,
        today: LocalDate = LocalDate.now(),
        assessment: Assessment? = null,
    ) {
        require(q.daily) { "编程题不进入日常复习" }
        val personal = dao.personalAnswer(q.id)?.takeIf { it.validFor(q) }
        val automatic = Grader.grade(q, selected, personal)
        val manual = if (automatic == null) assessment else null
        require(automatic != null || manual != null) { "本题需要选择正确、错误或不会" }
        val correct = automatic ?: (manual == Assessment.CORRECT)
        val effective = if (correct) Feedback.GOOD else Feedback.AGAIN
        db.withTransaction {
            val old = dao.getProgress(q.id) ?: Progress(q.id)
            val next = Scheduler.next(old.schedule(), effective, today)
            dao.putProgress(
                old.copy(
                    seen = old.seen + 1,
                    correctCount = old.correctCount + if (correct) 1 else 0,
                    repetitions = next.repetitions,
                    interval = next.interval,
                    ease = next.ease,
                    due = next.due,
                    lapses = next.lapses,
                    wrong = !correct,
                )
            )
            dao.addAttempt(
                Attempt(
                    questionId = q.id,
                    timestamp = System.currentTimeMillis(),
                    day = today.toEpochDay(),
                    response = response,
                    correct = correct,
                    feedback = effective.name,
                    mode = mode,
                    wasNew = old.seen == 0,
                    assessment = manual?.name ?: "",
                    answerSource =
                        if (manual != null) "MANUAL"
                        else if (personal != null) "PERSONAL" else "ORIGINAL",
                    normalizedCategory = CategoryMap.normalize(q.category),
                    questionHash = q.contentHash,
                )
            )
        }
    }

    suspend fun update(id: String, transform: (Progress) -> Progress) =
        db.withTransaction { dao.putProgress(transform(dao.getProgress(id) ?: Progress(id))) }

    /** Field-specific saves read the latest row and preserve unrelated personal fields. */
    suspend fun savePerceivedDifficulty(id: String, difficulty: String) =
        updatePersonalMetadata(id) { it.copy(perceivedDifficulty = difficulty) }

    suspend fun saveVerifiedSolution(id: String, code: String, verified: Boolean) =
        updatePersonalMetadata(id) { it.copy(verifiedCode = code, codeVerified = verified) }

    private suspend fun updatePersonalMetadata(
        id: String,
        transform: (QuestionPersonal) -> QuestionPersonal,
    ) = db.withTransaction {
        require(id.isNotBlank() && dao.question(id) != null) { "题目不存在" }
        val next = transform(dao.personalMetadata(id) ?: QuestionPersonal(id))
        LearningCodec.parseMetadata(LearningCodec.metadata(next))
        dao.putPersonalMetadata(next)
    }

    internal suspend fun endExam(
        snapshot: DurableExamSnapshot,
        submit: Boolean,
        afterRecord: suspend (Int) -> Unit = {},
    ): ExamTermination = db.withTransaction {
        // This lookup MUST precede loading questions or invoking any learning side effect.
        dao.examTermination(snapshot.sessionId, snapshot.legacyHash)?.let { return@withTransaction it }
        var result: ExamResult? = null
        if (submit) {
            val ids = snapshot.ids
            if (ids.isEmpty() || ids.distinct().size != ids.size)
                throw ExamValidationException("考试队列无效")
            val questions = ids.map { question(it) ?: throw ExamValidationException("考试题目不存在") }
            val grades = questions.map { q ->
                if (!q.daily) throw ExamValidationException("考试题目不可判分")
                Grader.grade(q, snapshot.answers[q.id].orEmpty(), dao.personalAnswer(q.id))
                    ?: throw ExamValidationException("考试题目不可判分")
            }
            var recorded = 0
            questions.forEach { q ->
                val selected = snapshot.answers[q.id].orEmpty()
                if (selected.isNotEmpty()) {
                    record(q, selected.joinToString(), selected, "模拟考试")
                    afterRecord(++recorded)
                }
            }
            val answered = questions.count { snapshot.answers[it.id].orEmpty().isNotEmpty() }
            val correct = questions.indices.count {
                snapshot.answers[questions[it].id].orEmpty().isNotEmpty() && grades[it]
            }
            result = ExamResult(ids.size, answered, ids.size - answered, correct, correct * 100 / ids.size)
        }
        val terminal = ExamTermination(snapshot.sessionId, if (submit) "SUBMITTED" else "ABANDONED",
            result?.total, result?.answered, result?.unanswered, result?.correct, result?.score,
            System.currentTimeMillis(), snapshot.revision, snapshot.legacyHash)
        dao.addExamTermination(terminal)
        terminal
    }
    suspend fun reset() = db.examAccess.replaceLearning {
        db.withTransaction {
            dao.allProgress().forEach {
                dao.putProgress(
                    Progress(
                        it.questionId,
                        favorite = it.favorite,
                        flagged = it.flagged,
                        code = it.code,
                        notes = it.notes,
                    )
                )
            }
            dao.clearAttempts()
        }

    }

    suspend fun importBank(text: String): Pair<Int, Int> {
        val rows = BankCodec.parseBank(text)
        return db.withTransaction {
            val existing = dao.allQuestions().map { it.id }.toSet()
            dao.addQuestions(rows)
            rows.count { it.id !in existing } to rows.count { it.id in existing }
        }
    }

    // Upgrade bundled questions by stable ID; never replace progress, history or personal content.
    suspend fun upgradeBundledBank(text: String, onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        val rows = BankCodec.parseBank(text, onProgress)
        db.withTransaction {
            val old = dao.allQuestions().associateBy { it.id }
            dao.addQuestions(rows)
            dao.updateQuestions(
                rows.filter { row ->
                    val previous = old[row.id]?.let { HistoricalQuestionV3.read(it.id, it.json) }
                    val incoming = BankCodec.parseQuestion(JSONObject(row.json))
                    previous != null &&
                        previous.source == incoming.source &&
                        previous.stem == incoming.stem
                }
            )
        }
    }

    private val backupFiles get() = AiFullBackupCodec(db.storageContext,
        java.io.File(db.storageContext.cacheDir, "complete-backup"))
    internal var beforeRestoreCommit: () -> Unit = {}
    internal var storageResource: (Long) -> Unit = AiStorageResources.guard(db.storageContext)

    /** Compatibility only: file/URI callers must use streams, not this whole-string adapter. */
    suspend fun backup(): String {
        val output = java.io.ByteArrayOutputStream()
        backupTo { output }
        return output.toString("UTF-8")
    }
    suspend fun backupTo(openNewDestination: () -> java.io.OutputStream) = db.aiLifecycle.operation {
        ensureAiStorage()
        backupFiles.export(db, openNewDestination, storageResource)
        Unit
    }
    suspend fun restore(text: String) = restoreFrom(java.io.ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
    suspend fun restoreFrom(input: java.io.InputStream) {
        // Input ownership begins at the public call, even if owner admission has already closed.
        var transferred = false
        try {
            db.aiLifecycle.operation {
                // Retain the existing editor contract: even a rejected backup first flushes
                // accepted text under the owner's exclusion, without advancing its generation.
                db.aiLifecycle.draftBoundary {
                    storageResource(0)
                    transferred = true
                    backupFiles.prepare(input, storageResource).use { stage ->
                        db.aiLifecycle.replace {
                            db.examAccess.replaceLearning {
                                AiBackupRestorer(db,resource={ storageResource(0) },beforeCommit=beforeRestoreCommit).replace(stage)
                            }
                        }
                    }
                }
            }
        } finally { if (!transferred) input.close() }
    }
    internal suspend fun ensureAiStorage() = initializeAiStorage(force=false)
    internal suspend fun initializeAiStorage(force:Boolean=true) = db.aiLifecycle.operation {
        db.aiLifecycle.validate(force) { db.withTransaction {
            AiStorageValidation.verifyAndRecover(db.openHelper.writableDatabase,resource={ storageResource(0) })
        } }
    }

    suspend fun question(id: String): Question? =
        dao.question(id)?.let { HistoricalQuestionV3.read(it.id, it.json) }

    suspend fun savePersonalAnswer(q: Question, selected: Set<String>, notes: String) {
        val value =
            PersonalAnswer(
                q.id,
                selected.sorted().joinToString(","),
                notes,
                q.contentHash,
                System.currentTimeMillis(),
            )
        require(value.validFor(q)) { "请按题型选择有效选项，至少需要两个原始选项" }
        dao.putPersonalAnswer(value)
    }

    suspend fun exportPersonalAnswers(): String =
        JSONObject()
            .put("personalAnswersVersion", 1)
            .put("answers", JSONArray(dao.allPersonalAnswers().map(LearningCodec::answer)))
            .toString()

    suspend fun importPersonalAnswers(text: String): Int {
        val root = JSONObject(text)
        require(root.getInt("personalAnswersVersion") == 1) { "个人核对答案格式不支持" }
        val answers = LearningCodec.objects(root, "answers").map(LearningCodec::parseAnswer)
        require(answers.map { it.questionId }.distinct().size == answers.size) { "题目编号重复" }
        for (answer in answers) {
            val q = question(answer.questionId) ?: error("个人核对答案中存在未知题号")
            require(answer.validFor(q)) { "题目内容已变化或选项无效，请重新核对" }
        }
        db.withTransaction { answers.forEach { dao.putPersonalAnswer(it) } }
        return answers.size
    }

    fun observeTodaySummary(today: Long) = dao.todaySummary(today)

    suspend fun search(
        filter: BrowseFilter,
        programming: Boolean,
        mistakes: Boolean,
    ): List<QuestionSummary> {
        val clauses =
            mutableListOf(if (programming) "q.kind='PROGRAMMING'" else "q.kind!='PROGRAMMING'")
        val args = mutableListOf<Any>()
        listOf(
                "source" to filter.source,
                "company" to filter.company,
                "normalizedCategory" to filter.category,
                "kind" to filter.kind,
                "difficulty" to filter.difficulty,
            )
            .forEach { (column, value) ->
                if (value != "全部") {
                    clauses += "q.$column=?"
                    args += value
                }
            }
        for (term in SearchTerms.split(filter.search)) {
            clauses +=
                "LOWER(q.searchText||' '||COALESCE(p.notes,'')||' '||COALESCE(p.code,'')||' '||COALESCE(m.tags,'')) LIKE ? ESCAPE '\\'"
            args += SearchTerms.like(term)
        }
        if (mistakes)
            clauses +=
                when (filter.group) {
                    "错题" -> "p.wrong=1"
                    "不会" ->
                        "p.wrong=1 AND (SELECT assessment FROM attempts a WHERE a.questionId=q.id ORDER BY timestamp DESC,id DESC LIMIT 1)='UNKNOWN'"
                    "收藏" -> "p.favorite=1"
                    else -> "p.flagged=1 OR q.reviewRequired=1"
                }.let { "($it)" }
        val haystack =
            "q.searchText||' '||COALESCE(p.notes,'')||' '||COALESCE(p.code,'')||' '||COALESCE(m.tags,'')"
        val terms = SearchTerms.split(filter.search)
        val snippet =
            if (terms.isEmpty()) "'' AS matchSnippet"
            else "substr($haystack,MAX(1,instr(LOWER($haystack),?)-40),180) AS matchSnippet"
        val columns =
            "q.id,q.stem,q.kind,q.source,q.category,q.normalizedCategory,q.company,q.difficulty,q.reviewRequired,q.correctKeys,q.hasAnswer,q.contentHash,q.optionKeys,$snippet"
        val sql =
            "SELECT $columns FROM questions q LEFT JOIN progress p ON p.questionId=q.id LEFT JOIN question_personal m ON m.questionId=q.id WHERE " +
                clauses.joinToString(" AND ") +
                " ORDER BY q.id"
        val allArgs = if (terms.isEmpty()) args else listOf(terms.first()) + args
        return dao.searchSummaries(
            androidx.sqlite.db.SimpleSQLiteQuery(sql, allArgs.toTypedArray())
        )
    }

    suspend fun searchNotes(text: String): List<StickyNote> {
        val terms = SearchTerms.split(text)
        val where =
            if (terms.isEmpty()) "1"
            else terms.joinToString(" AND ") { "LOWER(title||' '||content) LIKE ? ESCAPE '\\'" }
        return dao.searchStickyNotes(
            androidx.sqlite.db.SimpleSQLiteQuery(
                "SELECT * FROM sticky_notes WHERE $where ORDER BY updated DESC",
                terms.map(SearchTerms::like).toTypedArray(),
            )
        )
    }
}
