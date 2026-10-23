package cn.embeddedquiz

import androidx.room.withTransaction
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

class QuizRepository(val db: QuizDatabase) {
    val dao = db.dao()

    suspend fun record(
        q: Question,
        response: String,
        selected: Set<String>,
        mode: String,
        today: LocalDate = LocalDate.now(),
        assessment: Assessment? = null,
    ) {
        require(q.daily) { "编程题不进入日常复习" }
        val automatic = Grader.grade(q, selected)
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
                )
            )
        }
    }

    suspend fun update(id: String, transform: (Progress) -> Progress) =
        db.withTransaction { dao.putProgress(transform(dao.getProgress(id) ?: Progress(id))) }

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
    suspend fun upgradeBundledBank(text: String) {
        val rows = BankCodec.parseBank(text)
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
            JSONObject()
                .put("schemaVersion", 1)
                .put("backupVersion", 2)
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
                        }
                    ),
                )
                .toString()
        }

    suspend fun restore(text: String) {
        val root = JSONObject(text)
        val version = root.getInt("backupVersion")
        require(version in 1..2) { "不是有效备份" }
        val rows = BankCodec.parseBank(text)
        val ids = rows.map { it.id }.toSet()
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
}
