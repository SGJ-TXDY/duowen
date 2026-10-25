package cn.embeddedquiz

import androidx.room.withTransaction
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

class QuizRepository(val db: QuizDatabase) {
    val dao = db.dao()

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

    suspend fun reset() =
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
                    val previous = old[row.id]?.let { BankCodec.parseQuestion(JSONObject(it.json)) }
                    val incoming = BankCodec.parseQuestion(JSONObject(row.json))
                    previous != null &&
                        previous.source == incoming.source &&
                        previous.stem == incoming.stem
                }
            )
        }
    }

    suspend fun backup(): String =
        db.withTransaction {
            LearningCodec.append(
                    JSONObject()
                        .put("schemaVersion", 1)
                        .put("backupVersion", 3)
                        .put(
                            "stickyNotes",
                            JSONArray(
                                dao.allStickyNotes().map { n ->
                                    JSONObject()
                                        .put("id", n.id)
                                        .put("title", n.title)
                                        .put("content", n.content)
                                        .put("created", n.created)
                                        .put("updated", n.updated)
                                }
                            ),
                        )
                        .put("questions", JSONArray(dao.allQuestions().map { JSONObject(it.json) }))
                        .put(
                            "progress",
                            JSONArray(
                                dao.allProgress().map { p ->
                                    JSONObject()
                                        .put("questionId", p.questionId)
                                        .put("seen", p.seen)
                                        .put("correctCount", p.correctCount)
                                        .put("repetitions", p.repetitions)
                                        .put("interval", p.interval)
                                        .put("ease", p.ease)
                                        .put("due", p.due)
                                        .put("lapses", p.lapses)
                                        .put("wrong", p.wrong)
                                        .put("favorite", p.favorite)
                                        .put("flagged", p.flagged)
                                        .put("code", p.code)
                                        .put("notes", p.notes)
                                }
                            ),
                        )
                        .put(
                            "attempts",
                            JSONArray(
                                dao.allAttempts().map { a ->
                                    JSONObject()
                                        .put("questionId", a.questionId)
                                        .put("timestamp", a.timestamp)
                                        .put("day", a.day)
                                        .put("response", a.response)
                                        .put("correct", a.correct ?: JSONObject.NULL)
                                        .put("feedback", a.feedback)
                                        .put("mode", a.mode)
                                        .put("wasNew", a.wasNew)
                                        .put("assessment", a.assessment)
                                        .put("answerSource", a.answerSource)
                                        .put("normalizedCategory", a.normalizedCategory)
                                        .put("questionHash", a.questionHash)
                                }
                            ),
                        )
                        .put(
                            "ai",
                            JSONArray(
                                dao.allAi().map { a ->
                                    JSONObject()
                                        .put("cacheKey", a.cacheKey)
                                        .put("questionId", a.questionId)
                                        .put("model", a.model)
                                        .put("prompt", a.prompt)
                                        .put("response", a.response)
                                        .put("inputTokens", a.inputTokens)
                                        .put("outputTokens", a.outputTokens)
                                        .put("hitTokens", a.hitTokens)
                                        .put("estimatedUsd", a.estimatedUsd)
                                        .put("created", a.created)
                                        .put("contentHash", a.contentHash)
                                        .put("requestType", a.requestType)
                                        .put("day", a.day)
                                }
                            ),
                        ),
                    dao,
                )
                .toString()
        }

    suspend fun restore(text: String) {
        val root = JSONObject(text)
        val version = root.getInt("backupVersion")
        require(version in 1..3) { "不是有效备份" }
        val rows = BankCodec.parseBank(text)
        val ids = rows.map { it.id }.toSet()
        val categories = rows.associate { it.id to it.normalizedCategory }
        val kinds = rows.associate { it.id to it.kind }
        val learning = if (version >= 3) LearningCodec.parse(root) else null
        fun objects(name: String): List<JSONObject> {
            val arr = root.getJSONArray(name)
            return (0 until arr.length()).map { arr.getJSONObject(it) }
        }
        val stickyNotes =
            if (version >= 2)
                objects("stickyNotes").map { n ->
                    StickyNote(
                        n.getString("id"),
                        n.getString("title"),
                        n.getString("content"),
                        n.getLong("created"),
                        n.getLong("updated"),
                    )
                }
            else emptyList()
        require(
            stickyNotes.map { it.id }.distinct().size == stickyNotes.size &&
                stickyNotes.all {
                    it.id.isNotBlank() &&
                        it.id.length <= 200 &&
                        it.created >= 0 &&
                        it.updated >= it.created
                }
        ) {
            "便签数据无效"
        }
        val progress =
            objects("progress").map { p ->
                Progress(
                    p.getString("questionId"),
                    p.getInt("seen"),
                    p.getInt("correctCount"),
                    p.getInt("repetitions"),
                    p.getInt("interval"),
                    p.getDouble("ease"),
                    p.getLong("due"),
                    p.getInt("lapses"),
                    p.getBoolean("wrong"),
                    p.getBoolean("favorite"),
                    p.getBoolean("flagged"),
                    p.getString("code"),
                    p.getString("notes"),
                )
            }
        require(progress.map { it.questionId }.distinct().size == progress.size)
        require(
            progress.all {
                it.questionId in ids &&
                    it.seen >= 0 &&
                    it.correctCount in 0..it.seen &&
                    it.interval in 0..3650 &&
                    it.ease in 1.3..3.5 &&
                    it.repetitions >= 0 &&
                    it.lapses >= 0 &&
                    it.due in 0..3650000
            }
        ) {
            "复习状态无效"
        }
        val attempts =
            objects("attempts").map { a ->
                Attempt(
                    questionId = a.getString("questionId"),
                    timestamp = a.getLong("timestamp"),
                    day = a.getLong("day"),
                    response = a.getString("response"),
                    correct = if (a.isNull("correct")) null else a.getBoolean("correct"),
                    feedback = a.getString("feedback"),
                    mode = a.getString("mode"),
                    wasNew = a.getBoolean("wasNew"),
                    assessment = a.optString("assessment", ""),
                    answerSource =
                        a.optString(
                            "answerSource",
                            if (
                                a.optString("assessment").isNotEmpty() ||
                                    (!a.isNull("correct") &&
                                        kinds[a.getString("questionId")] !in
                                            setOf("SINGLE", "MULTIPLE", "TRUE_FALSE"))
                            )
                                "MANUAL"
                            else if (a.isNull("correct")) "UNSCORED" else "ORIGINAL",
                        ),
                    normalizedCategory =
                        a.optString(
                            "normalizedCategory",
                            categories[a.getString("questionId")].orEmpty(),
                        ),
                    questionHash = a.optString("questionHash"),
                )
            }
        require(
            attempts.all {
                it.assessment.isEmpty() ||
                    runCatching { Assessment.valueOf(it.assessment) }.isSuccess
            }
        )
        require(
            attempts.all {
                it.questionId in ids && runCatching { Feedback.valueOf(it.feedback) }.isSuccess
            }
        )
        val ai =
            objects("ai").map { a ->
                AiEntry(
                    a.getString("cacheKey"),
                    a.getString("questionId"),
                    a.getString("model"),
                    a.getString("prompt"),
                    a.getString("response"),
                    a.getInt("inputTokens"),
                    a.getInt("outputTokens"),
                    a.getInt("hitTokens"),
                    a.getDouble("estimatedUsd"),
                    a.getLong("created"),
                    contentHash = a.optString("contentHash"),
                    requestType = a.optString("requestType", "FOLLOW_UP"),
                    day =
                        a.optLong(
                            "day",
                            java.time.Instant.ofEpochMilli(a.getLong("created"))
                                .atZone(java.time.ZoneId.systemDefault())
                                .toLocalDate()
                                .toEpochDay(),
                        ),
                )
            }
        require(
            ai.all {
                it.questionId in ids &&
                    it.inputTokens >= 0 &&
                    it.outputTokens >= 0 &&
                    it.hitTokens in 0..it.inputTokens &&
                    it.estimatedUsd.isFinite() &&
                    it.estimatedUsd >= 0
            }
        )
        // Validate everything before touching the database; the replacement is atomic.
        db.withTransaction {
            if (learning != null) {
                val asked = dao.getSettings()?.notificationPermissionAsked == true
                dao.putSettings(
                    learning.settings.copy(
                        notificationPermissionAsked =
                            asked || learning.settings.notificationPermissionAsked
                    )
                )
                dao.clearPersonalAnswers()
                learning.answers.forEach { dao.putPersonalAnswer(it) }
                dao.clearPersonalMetadata()
                learning.metadata.forEach { dao.putPersonalMetadata(it) }
            }
            dao.clearAttempts()
            dao.clearProgress()
            dao.clearAi()
            dao.clearQuestions()
            // Old backups predate standalone notes; keep those notes when importing one.
            if (version >= 2) {
                dao.clearStickyNotes()
                stickyNotes.forEach { dao.putStickyNote(it) }
            }
            dao.addQuestions(rows)
            progress.forEach { dao.putProgress(it) }
            attempts.forEach { dao.addAttempt(it) }
            ai.forEach { dao.putAi(it) }
        }
    }

    suspend fun question(id: String): Question? =
        dao.question(id)?.let { BankCodec.parseQuestion(JSONObject(it.json)) }

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
