package cn.embeddedquiz

import android.content.Context
import java.io.RandomAccessFile
import java.time.Instant

enum class LogEvent(val label: String) {
    LOAD("加载失败"),
    OPERATION("本地操作失败"),
    AI("AI 请求失败"),
    REMINDER("提醒失败"),
}

/** Never log exception messages, request bodies, question text, responses or credentials. */
class LocalErrorLog(context: Context) {
    private val file = java.io.File(context.filesDir, "local-errors.log")

    @Synchronized
    fun record(event: LogEvent, error: Throwable) {
        runCatching {
            val line =
                "${Instant.now()} ${event.label} ${error.javaClass.simpleName}\n"
                    .toByteArray(Charsets.UTF_8)
            val previous =
                if (file.exists()) {
                    RandomAccessFile(file, "r").use { input ->
                        val count =
                            minOf(input.length(), (MAX_BYTES - line.size).coerceAtLeast(0).toLong())
                                .toInt()
                        input.seek(input.length() - count)
                        ByteArray(count).also(input::readFully)
                    }
                } else byteArrayOf()
            file.outputStream().use {
                it.write(previous)
                it.write(line)
            }
        }
    }

    @Synchronized fun read() = if (file.exists()) file.readText(Charsets.UTF_8) else "暂无错误记录"

    @Synchronized
    fun clear() {
        if (file.exists()) file.writeText("")
    }

    companion object {
        const val MAX_BYTES = 512 * 1024
    }
}
