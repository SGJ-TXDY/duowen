package cn.embeddedquiz

import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject

data class ExamSnapshot(val ids: List<String>, val index: Int)

class ExamStateHolder(private val prefs: SharedPreferences, private val repo: QuizRepository) {
    var deadline by mutableLongStateOf(0)
    var answers by mutableStateOf<Map<String, Set<String>>>(emptyMap())
    var finished by mutableStateOf(false)
    var result by mutableStateOf("")

    fun restore(): ExamSnapshot? {
        val saved = prefs.getString("exam", null) ?: return null
        return runCatching {
                val root = JSONObject(saved)
                val ids =
                    root.getJSONArray("ids").let { a -> (0 until a.length()).map(a::getString) }
                deadline = root.getLong("deadline")
                val values = root.getJSONObject("answers")
                answers =
                    values.keys().asSequence().associateWith { id ->
                        val a = values.getJSONArray(id)
                        (0 until a.length()).map(a::getString).toSet()
                    }
                ExamSnapshot(ids, root.getInt("index").coerceIn(0, (ids.size - 1).coerceAtLeast(0)))
            }
            .getOrElse {
                clear()
                null
            }
    }

    fun save(ids: List<String>, index: Int, mode: String) {
        if (mode != "模拟考试" || finished) return
        val root =
            JSONObject().put("ids", JSONArray(ids)).put("index", index).put("deadline", deadline)
        val values = JSONObject()
        answers.forEach { (id, selected) -> values.put(id, JSONArray(selected.toList())) }
        root.put("answers", values)
        prefs.writeDurably { putString("exam", root.toString()) }
    }

    suspend fun grade(questions: List<Question>): String {
        var correct = 0
        repo.db.withTransaction {
            questions.forEach { q ->
                val selected = answers[q.id].orEmpty()
                if (Grader.grade(q, selected) == true) correct++
                repo.record(q, selected.joinToString(), selected, "模拟考试")
            }
        }
        clear()
        return "答对 $correct / ${questions.size}，得分 ${if (questions.isEmpty()) 0 else correct * 100 / questions.size}"
    }

    fun clear() = prefs.writeDurably { remove("exam") }

    fun active() = prefs.contains("exam") && !finished
}
