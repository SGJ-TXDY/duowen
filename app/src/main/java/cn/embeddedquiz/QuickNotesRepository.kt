package cn.embeddedquiz

import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Independent internal files and atomic index; never reads or writes QuizDatabase. */
class QuickNotesRepository(private val directory: File) {
    companion object {
        const val MAX_MARKDOWN = 4 * 1024 * 1024
        const val MAX_PACKAGE = 32 * 1024 * 1024
        const val MAX_BACKUP = 64 * 1024 * 1024
        const val MAX_ENTRIES = 512
        const val MAX_DOCUMENTS = 200
        fun readBounded(input: InputStream, limit: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(out.size().toLong() + count <= limit) { "文件超过速记大小限制" }
                out.write(buffer, 0, count)
            }
            return out.toByteArray()
        }
        fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun imageMime(path: String): String? = when (path.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> null
        }
        private fun unzip(bytes: ByteArray, limit: Int): Map<String, ByteArray> {
            require(bytes.size <= limit) { "压缩包过大" }
            val files = linkedMapOf<String, ByteArray>()
            var total = 0L
            var entries = 0
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++entries <= MAX_ENTRIES) { "压缩包文件过多" }
                    val name = QuickNotePaths.normalize(entry.name)
                    if (!entry.isDirectory) {
                        require(name !in files) { "压缩包存在重复路径" }
                        val data = readBounded(zip, limit - total.toInt())
                        total += data.size
                        require(total <= limit) { "压缩包解压后过大" }
                        files[name] = data
                    }
                    zip.closeEntry()
                }
            }
            return files
        }
        fun parsePackage(name: String, bytes: ByteArray): QuickNotePackage {
            require(name.length <= 255 && '/' !in name && '\\' !in name) { "文件名无效" }
            if (name.endsWith(".md", true)) {
                require(bytes.size <= MAX_MARKDOWN) { "Markdown 文件超过 4 MiB" }
                return QuickNotePackage(name, name, bytes).also { validatePackage(it) }
            }
            require(name.endsWith(".zip", true)) { "请选择 .md 或 Markdown 配套 .zip 文件" }
            val files = unzip(bytes, MAX_PACKAGE)
            val markdown = files.keys.filter { it.endsWith(".md", true) }
            require(markdown.size == 1) { "Markdown 配套 ZIP 必须恰好包含一个 .md 文件" }
            val path = markdown.single()
            val images = files.filterKeys { imageMime(it) != null }
            return QuickNotePackage(path.substringAfterLast('/'), path, files.getValue(path), images).also { validatePackage(it) }
        }
        private fun validatePackage(pack: QuickNotePackage) {
            require(pack.sourceName.isNotBlank() && pack.sourceName.endsWith(".md", true) && '/' !in pack.sourceName && '\\' !in pack.sourceName) { "文档文件名无效" }
            require(QuickNotePaths.normalize(pack.markdownPath) == pack.markdownPath) { "文档路径无效" }
            require(pack.markdown.size <= MAX_MARKDOWN && pack.markdown.isNotEmpty()) { "Markdown 为空或超过 4 MiB" }
            val decoded = try { QuickNotePaths.utf8(pack.markdown) } catch (e: Exception) { throw IllegalArgumentException("Markdown 必须是有效 UTF-8 编码", e) }
            require(decoded.isNotBlank()) { "Markdown 内容为空" }
            require(pack.images.size < MAX_ENTRIES) { "图片过多" }
            require(pack.images.values.sumOf { it.size.toLong() } + pack.markdown.size <= MAX_PACKAGE) { "文档及图片总计超过 32 MiB" }
            pack.images.forEach { (path, data) ->
                require(QuickNotePaths.normalize(path) == path && imageMime(path) != null && data.isNotEmpty()) { "不支持的图片资源" }
                val prefix = data.take(12).map { it.toInt() and 255 }
                val valid = when (imageMime(path)) {
                    "image/png" -> prefix.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10)
                    "image/jpeg" -> prefix.take(3) == listOf(255, 216, 255)
                    "image/gif" -> data.take(6).toByteArray().toString(Charsets.US_ASCII) in listOf("GIF87a", "GIF89a")
                    "image/webp" -> data.size >= 12 && data.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" && data.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP"
                    else -> false
                }
                require(valid) { "图片格式与扩展名不符：$path" }
            }
        }
    }

    private data class ImageInfo(val path: String, val file: String)
    private data class Entry(val summary: QuickNoteSummary, val markdownPath: String, val markdownHash: String, val images: List<ImageInfo>)
    private val mutex = Mutex()
    private val renderer = QuickNoteMarkdown()
    private val anchors = mutableMapOf<String, Set<String>>()
    private val index get() = AtomicFile(File(directory, "index.json"))
    private suspend fun <T> locked(block: () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }
    private fun folder(id: String): File {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "文档编号无效" }
        val target = File(directory, id)
        require(target.canonicalFile.parentFile == directory.canonicalFile) { "文档路径无效" }
        return target
    }
    private fun encode(entries: List<Entry>): JSONObject = JSONObject().put("quickNotesVersion", 1).put("documents", JSONArray(entries.map { e ->
        JSONObject().put("id", e.summary.id).put("title", e.summary.title).put("sourceName", e.summary.sourceName)
            .put("contentHash", e.summary.contentHash).put("importedAt", e.summary.importedAt)
            .put("markdownPath", e.markdownPath).put("markdownHash", e.markdownHash)
            .put("position", JSONObject().put("anchor", e.summary.position.anchor).put("offset", e.summary.position.offset).put("fraction", e.summary.position.fraction))
            .put("images", JSONArray(e.images.map { JSONObject().put("path", it.path).put("file", it.file) }))
    }))
    private fun decode(root: JSONObject): List<Entry> {
        require(root.getInt("quickNotesVersion") == 1) { "速记存储格式不支持" }
        val values = root.getJSONArray("documents")
        require(values.length() <= MAX_DOCUMENTS) { "速记文档过多" }
        val entries = (0 until values.length()).map { n ->
            val o = values.getJSONObject(n)
            val p = o.getJSONObject("position")
            val position = QuickNotePosition(p.getString("anchor"), p.getDouble("offset"), p.getDouble("fraction"))
            validatePosition(position)
            val summary = QuickNoteSummary(o.getString("id"), o.getString("title"), o.getString("sourceName"), o.getString("contentHash"), o.getLong("importedAt"), position)
            folder(summary.id)
            require(summary.title.isNotBlank() && summary.title.length <= 1024 && summary.sourceName.length <= 255 && summary.contentHash.matches(Regex("[a-f0-9]{64}"))) { "速记索引无效" }
            val path = o.getString("markdownPath")
            require(QuickNotePaths.normalize(path) == path) { "文档路径无效" }
            val hash = o.getString("markdownHash")
            require(hash.matches(Regex("[a-f0-9]{64}"))) { "文档校验值无效" }
            val images = o.getJSONArray("images")
            require(images.length() < MAX_ENTRIES) { "图片过多" }
            val items = (0 until images.length()).map { i ->
                val image = images.getJSONObject(i)
                val item = ImageInfo(image.getString("path"), image.getString("file"))
                require(QuickNotePaths.normalize(item.path) == item.path && imageMime(item.path) != null && item.file.matches(Regex("[a-f0-9]{64}"))) { "图片索引无效" }
                item
            }
            require(items.map { it.path }.distinct().size == items.size) { "图片路径重复" }
            Entry(summary, path, hash, items)
        }
        require(entries.map { it.summary.id }.distinct().size == entries.size) { "文档编号重复" }
        return entries
    }
    private fun readIndex(): List<Entry> {
        if (!File(directory, "index.json").exists() && !File(directory, "index.json.bak").exists()) return emptyList()
        return try { decode(JSONObject(index.openRead().use { QuickNotePaths.utf8(readBounded(it, MAX_MARKDOWN)) })) }
        catch (e: Exception) { throw IllegalStateException("速记索引无法读取，请保留文件并重试或恢复独立速记备份", e) }
    }
    private fun writeIndex(entries: List<Entry>) {
        require(entries.size <= MAX_DOCUMENTS) { "最多保存 200 份速记文档" }
        check(directory.exists() || directory.mkdirs()) { "无法建立速记目录" }
        val bytes = encode(entries).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_MARKDOWN) { "速记索引过大" }
        val file = index
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) } catch (e: Exception) { file.failWrite(output); throw e }
    }
    private fun fingerprint(pack: QuickNotePackage): String = sha((sha(pack.markdown) + JSONArray(pack.images.toSortedMap().map { JSONArray(listOf(it.key, sha(it.value))) }).toString()).toByteArray(Charsets.UTF_8))
    private fun install(pack: QuickNotePackage, existing: List<Entry>, title: String? = null, position: QuickNotePosition = QuickNotePosition()): Entry {
        validatePackage(pack)
        val id = UUID.randomUUID().toString()
        val rendered = renderer.render(QuickNotePaths.utf8(pack.markdown))
        val initial = (title ?: rendered.headings.firstOrNull { it.level == 1 }?.title ?: pack.sourceName.dropLast(3)).take(1000).ifBlank { "未命名文档" }
        var display = initial
        var suffix = 2
        while (existing.any { it.summary.title == display }) display = "$initial (${suffix++})"
        val entry = Entry(QuickNoteSummary(id, display, pack.sourceName, fingerprint(pack), System.currentTimeMillis(), position), pack.markdownPath, sha(pack.markdown), pack.images.map { ImageInfo(it.key, sha(it.value)) })
        val target = folder(id)
        check(target.mkdirs()) { "无法创建文档存储目录" }
        try {
            File(target, "document.md").writeBytes(pack.markdown)
            if (pack.images.isNotEmpty()) check(File(target, "assets").mkdirs())
            pack.images.forEach { (_, bytes) -> File(target, "assets/${sha(bytes)}").writeBytes(bytes) }
        } catch (e: Exception) { target.deleteRecursively(); throw e }
        anchors[id] = rendered.headings.map { it.id }.toSet()
        return entry
    }
    private fun validatePosition(position: QuickNotePosition) {
        require(position.anchor.length <= 2048 && position.offset.isFinite() && position.offset in 0.0..10000000.0 && position.fraction.isFinite() && position.fraction in 0.0..1.0) { "阅读位置无效" }
    }

    suspend fun listDocuments(): List<QuickNoteSummary> = locked {
        val entries = readIndex()
        // Incomplete imports can leave unreferenced private folders; never reset a bad index.
        val active = entries.map { it.summary.id }.toSet()
        directory.listFiles()?.filter { it.isDirectory && it.name !in active && runCatching { UUID.fromString(it.name).toString() == it.name }.getOrDefault(false) }
            ?.forEach { folder(it.name).deleteRecursively() }
        entries.map { it.summary }.sortedWith(compareByDescending<QuickNoteSummary> { it.importedAt }.thenBy { it.id })
    }

    suspend fun importDocument(pack: QuickNotePackage, keepBoth: Boolean = false): QuickNoteImportResult = locked {
        validatePackage(pack)
        val current = readIndex()
        val hash = fingerprint(pack)
        val matches = current.filter { it.summary.contentHash == hash || it.summary.sourceName == pack.sourceName }
        if (matches.isNotEmpty() && !keepBoth) return@locked QuickNoteImportResult.Conflict(matches.map { it.summary })
        require(current.size < MAX_DOCUMENTS) { "最多保存 200 份速记文档" }
        val entry = install(pack, current)
        try { writeIndex(current + entry) } catch (e: Exception) { folder(entry.summary.id).deleteRecursively(); anchors.remove(entry.summary.id); throw e }
        QuickNoteImportResult.Imported(entry.summary)
    }

    private fun markdown(entry: Entry): ByteArray = File(folder(entry.summary.id), "document.md").inputStream().use { readBounded(it, MAX_MARKDOWN) }.also {
        require(sha(it) == entry.markdownHash) { "文档内容校验失败，请重新导入或恢复速记备份" }
    }

    suspend fun openDocument(id: String): QuickNoteDocument = locked {
        val entry = readIndex().firstOrNull { it.summary.id == id } ?: error("速记文档不存在")
        val available = entry.images.filter { File(folder(id), "assets/${it.file}").isFile }.map { it.path }.toSet()
        val rendered = renderer.render(QuickNotePaths.utf8(markdown(entry)), id, entry.markdownPath, available)
        anchors[id] = rendered.headings.map { it.id }.toSet()
        QuickNoteDocument(entry.summary, rendered)
    }

    suspend fun readAsset(id: String, encodedPath: String): QuickNoteAsset? = locked {
        val entry = readIndex().firstOrNull { it.summary.id == id } ?: return@locked null
        val path = QuickNotePaths.normalize(QuickNotePaths.decode(encodedPath))
        val image = entry.images.firstOrNull { it.path == path } ?: return@locked null
        val target = File(folder(id), "assets/${image.file}")
        if (!target.isFile) return@locked null
        val bytes = target.inputStream().use { readBounded(it, MAX_PACKAGE) }
        require(sha(bytes) == image.file) { "图片校验失败" }
        QuickNoteAsset(bytes, requireNotNull(imageMime(image.path)))
    }

    suspend fun savePosition(id: String, position: QuickNotePosition) = locked {
        validatePosition(position)
        val current = readIndex()
        val entry = current.firstOrNull { it.summary.id == id } ?: error("速记文档不存在")
        val known = anchors.getOrPut(id) { renderer.render(QuickNotePaths.utf8(markdown(entry))).headings.map { it.id }.toSet() }
        require(position.anchor.isEmpty() || position.anchor in known) { "阅读锚点不属于当前文档" }
        writeIndex(current.map { if (it.summary.id == id) it.copy(summary = it.summary.copy(position = position)) else it })
    }

    suspend fun deleteDocument(id: String) = locked {
        val current = readIndex()
        require(current.any { it.summary.id == id }) { "速记文档不存在" }
        writeIndex(current.filterNot { it.summary.id == id })
        anchors.remove(id)
        folder(id).deleteRecursively()
        Unit
    }

    /** Independent ZIP backup. Existing quiz backupVersion=3 intentionally remains untouched. */
    suspend fun exportBackup(): ByteArray = locked {
        val current = readIndex()
        val output = ByteArrayOutputStream()
        var total = 0L
        var count = 0
        ZipOutputStream(output).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                require(++count <= MAX_ENTRIES) { "速记备份文件超过 512 项，请减少文档或资源" }
                total += bytes.size
                require(total <= MAX_BACKUP) { "速记备份超过 64 MiB，请减少文档后重试" }
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                require(output.size() <= MAX_BACKUP) { "速记备份过大" }
            }
            put("manifest.json", encode(current).toString().toByteArray(Charsets.UTF_8))
            current.forEach { entry ->
                put("documents/${entry.summary.id}/document.md", markdown(entry))
                entry.images.distinctBy { it.file }.forEach { image ->
                    val data = File(folder(entry.summary.id), "assets/${image.file}").inputStream().use { readBounded(it, MAX_PACKAGE) }
                    require(sha(data) == image.file) { "图片校验失败，备份未完成" }
                    put("documents/${entry.summary.id}/assets/${image.file}", data)
                }
            }
        }
        require(output.size() <= MAX_BACKUP) { "速记备份过大" }
        output.toByteArray()
    }

    /** Default is merge-and-skip-identical; replace requires an explicit UI confirmation. */
    suspend fun restoreBackup(bytes: ByteArray, replace: Boolean = false): Int = locked {
        val files = unzip(bytes, MAX_BACKUP)
        val manifest = files["manifest.json"] ?: error("不是独立速记备份")
        require(manifest.size <= MAX_MARKDOWN) { "速记备份索引过大" }
        val incoming = decode(JSONObject(QuickNotePaths.utf8(manifest)))
        val packs = incoming.map { entry ->
            val prefix = "documents/${entry.summary.id}/"
            val md = files[prefix + "document.md"] ?: error("备份缺少 Markdown 正文")
            require(sha(md) == entry.markdownHash) { "备份正文校验失败" }
            val images = entry.images.associate { image ->
                val imageBytes = files[prefix + "assets/${image.file}"] ?: error("备份缺少图片")
                require(sha(imageBytes) == image.file) { "备份图片校验失败" }
                image.path to imageBytes
            }
            val pack = QuickNotePackage(entry.summary.sourceName, entry.markdownPath, md, images)
            validatePackage(pack)
            require(fingerprint(pack) == entry.summary.contentHash) { "速记备份内容校验失败" }
            val headings = renderer.render(QuickNotePaths.utf8(md)).headings.map { it.id }.toSet()
            require(entry.summary.position.anchor.isEmpty() || entry.summary.position.anchor in headings) { "备份阅读锚点无效" }
            entry to pack
        }
        val original = if (replace) runCatching { readIndex() }.getOrDefault(emptyList()) else readIndex()
        val updated = if (replace) mutableListOf() else original.toMutableList()
        val created = mutableListOf<Entry>()
        try {
            packs.forEach { (old, pack) ->
                if (replace || updated.none { it.summary.contentHash == old.summary.contentHash }) {
                    require(updated.size < MAX_DOCUMENTS) { "恢复后速记文档超过 200 份" }
                    val entry = install(pack, updated, old.summary.title, old.summary.position)
                        .let { it.copy(summary = it.summary.copy(importedAt = old.summary.importedAt)) }
                    created.add(entry); updated.add(entry)
                }
            }
            writeIndex(updated)
        } catch (e: Exception) {
            created.forEach { folder(it.summary.id).deleteRecursively(); anchors.remove(it.summary.id) }
            throw e
        }
        if (replace) original.forEach { folder(it.summary.id).deleteRecursively(); anchors.remove(it.summary.id) }
        created.size
    }
}
