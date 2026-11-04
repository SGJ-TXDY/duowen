package cn.embeddedquiz

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal class StoredQuestionException : IllegalArgumentException("历史题目数据无法解释，原始数据已保留")

/** Frozen v3 storage rules. Never route historical data through the evolving import validator. */
internal object HistoricalQuestionV3 {
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    fun read(id: String, raw: String): Question = try {
        parse(JSONObject(raw)).also { require(it.id == id) }
    } catch (failure: Exception) { throw StoredQuestionException() }

    private fun parse(o: JSONObject): Question {
        val kind = Kind.valueOf(o.getString("kind"))
        require(kind.name in setOf("SINGLE", "MULTIPLE", "TRUE_FALSE", "CODE_READING", "SHORT", "PROGRAMMING"))
        val options =
            (o.optJSONArray("options") ?: JSONArray()).objects().map {
                Option(it.getString("key"), it.getString("text"))
            }
        val a = o.optJSONArray("correct") ?: JSONArray()
        val correct = (0 until a.length()).map { a.getString(it) }.toSet()
        val q =
            Question(
                o.getString("id"),
                o.getString("stem"),
                kind,
                o.getString("source"),
                o.optString("company", "未注明"),
                o.optString("category", "未分类"),
                o.optString("difficulty", "未标注"),
                options,
                o.optString("answer"),
                correct,
                o.optString("explanation"),
                o.optBoolean("reviewRequired", true),
                (o.optJSONArray("images") ?: JSONArray()).objects().map {
                    Picture(it.getString("name"), it.getString("data"))
                },
                o.optString("examples"),
                o.optString("constraints"),
                o.optString("approach"),
                o.optString("referenceCode"),
                o.optString("locator"),
                o.optString("duplicateOf"),
                o.optString("raw"),
            )
        require(
            q.id.isNotBlank() && q.id.length <= 200 && q.stem.isNotBlank() && q.source.isNotBlank()
        ) {
            "题目 ID、题干或来源不完整"
        }
        require(options.map { it.key }.distinct().size == options.size) { "选项编号重复" }
        require(kind !in setOf(Kind.SINGLE, Kind.MULTIPLE, Kind.TRUE_FALSE) || q.reviewRequired || options.size >= 2) { "可判分客观题至少需要两个选项" }
        require(correct.all { key -> options.any { it.key == key } }) { "答案引用不存在的选项" }
        if (kind in setOf(Kind.SINGLE, Kind.MULTIPLE, Kind.TRUE_FALSE) && !q.reviewRequired) {
            require(correct.isNotEmpty()) { "可判分客观题必须有标准答案" }
            require(kind == Kind.MULTIPLE || correct.size == 1) { "单选/判断只能有一个答案" }
        }
        require(q.images.all { it.data.length <= 8_000_000 }) { "单张图片过大" }
        return q
    }


    private val categories = mapOf(
        "1 C语言基础与内存管理" to "C语言与内存",
        "1 系统移植 / Linux内核" to "系统移植",
        "1 系统移植 / U Boot引导" to "系统移植",
        "1 系统移植 / 交叉编译" to "系统移植",
        "1 系统移植 / 根文件系统" to "系统移植",
        "10 STM32基础 / 进程线程与并发" to "操作系统与并发",
        "10 数据库" to "数据库",
        "11 其他语言与软件开发" to "其他语言与软件开发",
        "12 逻辑推理与图形规律" to "数据结构与算法",
        "2 C++面向对象与模板" to "C++",
        "2 设备驱动 / 交叉编译" to "Linux驱动与内核",
        "3 数据结构与算法" to "数据结构与算法",
        "3 裸机与硬件基础 / 交叉编译" to "硬件与通信接口",
        "4 C语言基础必备 / 关键字与基础对象" to "C语言与内存",
        "4 操作系统与并发编程" to "操作系统与并发",
        "5 C语言进阶 / 函数与参数" to "C语言与内存",
        "5 C语言进阶 / 函数指针与回调" to "C语言与内存",
        "5 C语言进阶 / 动态内存与其他易错点" to "C语言与内存",
        "5 C语言进阶 / 存储类与存储期" to "C语言与内存",
        "5 C语言进阶 / 指针与间接访问" to "C语言与内存",
        "5 C语言进阶 / 概念与语法" to "C语言与内存",
        "5 C语言进阶 / 编译与链接" to "C语言与内存",
        "5 C语言进阶 / 聚合类型与内存布局" to "C语言与内存",
        "5 C语言进阶 / 递归与数组" to "C语言与内存",
        "5 C语言进阶 / 预处理" to "C语言与内存",
        "5 Linux系统与开发工具" to "Linux基础与工具",
        "6 嵌入式Linux启动与驱动" to "Linux驱动与内核",
        "6 数据结构与算法 / 动态内存与其他易错点" to "数据结构与算法",
        "7 Linux命令与进线程 / 常用命令" to "操作系统与并发",
        "7 Linux命令与进线程 / 进程线程与并发" to "操作系统与并发",
        "7 嵌入式硬件与通信接口" to "硬件与通信接口",
        "8 FreeRTOS与实时系统" to "实时操作系统",
        "8 网络编程 / 进程线程与并发" to "操作系统与并发",
        "9 数据库与库移植 / 进程线程与并发" to "操作系统与并发",
        "9 网络通信与协议" to "网络与协议",
        "C++" to "C++",
        "C语言" to "C语言与内存",
        "C语言，结构体对齐" to "C语言与内存",
        "FreeRTOS" to "实时操作系统",
        "Linux" to "Linux基础与工具",
        "Linux基础与开发工具" to "Linux基础与工具",
        "中断、锁与用户内核交互" to "Linux驱动与内核",
        "启动流程与内核模块" to "Linux驱动与内核",
        "字符设备与设备树" to "Linux驱动与内核",
        "嵌入式Linux驱动" to "Linux驱动与内核",
        "手写代码 / C基础与算法" to "数据结构与算法",
        "手写代码 / 数据结构" to "数据结构与算法",
        "手写代码 / 线程与设备通信" to "操作系统与并发",
        "操作系统" to "操作系统与并发",
        "数据结构" to "数据结构与算法",
        "数据结构与算法 / 图与综合算法" to "数据结构与算法",
        "数据结构与算法 / 复杂度与线性结构" to "数据结构与算法",
        "数据结构与算法 / 排序与查找" to "数据结构与算法",
        "数据结构与算法 / 树与堆" to "数据结构与算法",
        "硬件与通信接口" to "硬件与通信接口",
        "网络" to "网络与协议",
    )
    private fun category(raw: String) = categories[raw]
        ?: raw.replace(Regex("^\\s*\\d+[.、\\s]*"), "").ifBlank { "未分类" }

    fun index(id: String, raw: String): QuestionRow {
        val q = read(id, raw)
        // Freeze data-class option rendering too; a future Option.toString must not change migration.
        val options = q.options.joinToString(prefix = "[", postfix = "]") { "Option(key=${it.key}, text=${it.text})" }
        val hashInput = listOf(q.stem, q.kind.name, options, q.answer,
            q.correct.sorted().joinToString(), q.explanation, q.referenceCode, q.raw).joinToString("\n")
        val hash = MessageDigest.getInstance("SHA-256").digest(hashInput.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return QuestionRow(q.id, raw, q.kind.name, q.source, q.category, category(q.category),
            q.company, q.difficulty, q.reviewRequired, q.stem.take(180),
            q.correct.sorted().joinToString(","),
            listOf(q.stem, q.options.joinToString(" ") { it.text }, q.answer, q.explanation, q.referenceCode).joinToString(" "),
            q.answer.isNotBlank() || q.correct.isNotEmpty(), hash, q.options.joinToString(",") { it.key })
    }
}