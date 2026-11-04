package cn.embeddedquiz

/** Prepare the exact v5 reader columns before publishing Ready. No rows or source data are changed.
 * Room checks its stored identity hash on an already-current DB; that is not a live check that a
 * damaged/deleted dependent table is still present. Keep this list aligned with schema 5.
 */
internal object StartupStorageCheck {
    private val queries = listOf(
        "SELECT `id`,`json`,`kind`,`source`,`category`,`normalizedCategory`,`company`,`difficulty`,`reviewRequired`,`stem`,`correctKeys`,`searchText`,`hasAnswer`,`contentHash`,`optionKeys` FROM `questions` LIMIT 0",
        "SELECT `sessionId`,`status`,`total`,`answered`,`unanswered`,`correct`,`score`,`endedAt`,`revision`,`legacyHash` FROM `exam_terminations` LIMIT 0",
        "SELECT `questionId`,`seen`,`correctCount`,`repetitions`,`interval`,`ease`,`due`,`lapses`,`wrong`,`favorite`,`flagged`,`code`,`notes` FROM `progress` LIMIT 0",
        "SELECT `id`,`questionId`,`timestamp`,`day`,`response`,`correct`,`feedback`,`mode`,`wasNew`,`assessment`,`answerSource`,`normalizedCategory`,`questionHash` FROM `attempts` LIMIT 0",
        "SELECT `cacheKey`,`questionId`,`model`,`prompt`,`response`,`inputTokens`,`outputTokens`,`hitTokens`,`estimatedUsd`,`created`,`contentHash`,`requestType`,`day` FROM `ai` LIMIT 0",
        "SELECT `id`,`title`,`content`,`created`,`updated` FROM `sticky_notes` LIMIT 0",
        "SELECT `id`,`dailyGoal`,`theme`,`fontSize`,`reminderEnabled`,`reminderMinute`,`notificationPermissionAsked`,`lastNotifiedDay` FROM `learning_settings` LIMIT 0",
        "SELECT `questionId`,`choices`,`notes`,`questionHash`,`updated` FROM `personal_answers` LIMIT 0",
        "SELECT `questionId`,`tags`,`perceivedDifficulty`,`verifiedCode`,`codeVerified` FROM `question_personal` LIMIT 0",
    )
    fun verify(db: QuizDatabase) {
        val storage = db.openHelper.readableDatabase
        queries.forEach { sql -> storage.query(sql).use { } }
    }
}