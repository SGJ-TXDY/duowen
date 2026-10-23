package cn.embeddedquiz

import org.json.JSONArray
import org.json.JSONObject

data class LearningBackup(
    val settings: LearningSettings,
    val answers: List<PersonalAnswer>,
    val metadata: List<QuestionPersonal>,
)

object LearningCodec {
    fun answer(value: PersonalAnswer) =
        JSONObject()
            .put("questionId", value.questionId)
            .put("choices", value.choices)
            .put("notes", value.notes)
            .put("questionHash", value.questionHash)
            .put("updated", value.updated)

    fun parseAnswer(value: JSONObject) =
        PersonalAnswer(
                value.getString("questionId"),
                value.getString("choices"),
                value.getString("notes"),
                value.getString("questionHash"),
                value.getLong("updated"),
            )
            .also {
                require(
                    it.questionId.isNotBlank() &&
                        it.questionId.length <= 200 &&
                        it.selected.isNotEmpty() &&
                        it.questionHash.matches(Regex("[a-f0-9]{64}")) &&
                        it.updated >= 0
                ) {
                    "个人核对答案数据无效"
                }
            }

    fun settings(value: LearningSettings) =
        JSONObject()
            .put("dailyGoal", value.dailyGoal)
            .put("theme", value.theme)
            .put("fontSize", value.fontSize)
            .put("reminderEnabled", value.reminderEnabled)
            .put("reminderMinute", value.reminderMinute)
            .put("notificationPermissionAsked", value.notificationPermissionAsked)
            .put("lastNotifiedDay", value.lastNotifiedDay)

    fun parseSettings(o: JSONObject) =
        LearningSettings(
                dailyGoal = o.getInt("dailyGoal"),
                theme = o.getString("theme"),
                fontSize = o.getString("fontSize"),
                reminderEnabled = o.getBoolean("reminderEnabled"),
                reminderMinute = o.getInt("reminderMinute"),
                notificationPermissionAsked = o.optBoolean("notificationPermissionAsked"),
                lastNotifiedDay = o.optLong("lastNotifiedDay", -1),
            )
            .also {
                require(
                    it.dailyGoal in 1..1000 &&
                        it.reminderMinute in 0..1439 &&
                        ThemeChoice.entries.any { v -> v.name == it.theme } &&
                        FontChoice.entries.any { v -> v.name == it.fontSize }
                ) {
                    "学习设置无效"
                }
            }

    fun metadata(value: QuestionPersonal) =
        JSONObject()
            .put("questionId", value.questionId)
            .put("tags", value.tags)
            .put("perceivedDifficulty", value.perceivedDifficulty)
            .put("verifiedCode", value.verifiedCode)
            .put("codeVerified", value.codeVerified)

    fun parseMetadata(o: JSONObject) =
        QuestionPersonal(
                o.getString("questionId"),
                o.getString("tags"),
                o.getString("perceivedDifficulty"),
                o.getString("verifiedCode"),
                o.getBoolean("codeVerified"),
            )
            .also {
                require(
                    it.questionId.isNotBlank() &&
                        it.perceivedDifficulty in listOf("", "易", "中", "难") &&
                        (!it.codeVerified || it.verifiedCode.isNotBlank())
                ) {
                    "题目个人资料无效"
                }
            }

    fun objects(root: JSONObject, key: String): List<JSONObject> =
        root.getJSONArray(key).let { a -> (0 until a.length()).map(a::getJSONObject) }

    fun parse(root: JSONObject): LearningBackup {
        val answers = objects(root, "personalAnswers").map(::parseAnswer)
        val metadata = objects(root, "questionPersonal").map(::parseMetadata)
        require(
            answers.map { it.questionId }.distinct().size == answers.size &&
                metadata.map { it.questionId }.distinct().size == metadata.size
        ) {
            "个人数据编号重复"
        }
        return LearningBackup(
            parseSettings(root.getJSONObject("learningSettings")),
            answers,
            metadata,
        )
    }

    suspend fun append(root: JSONObject, dao: QuizDao): JSONObject =
        root
            .put("learningSettings", settings(dao.getSettings() ?: LearningSettings()))
            .put("personalAnswers", JSONArray(dao.allPersonalAnswers().map(::answer)))
            .put("questionPersonal", JSONArray(dao.allPersonalMetadata().map(::metadata)))
}
