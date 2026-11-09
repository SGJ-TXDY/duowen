package cn.embeddedquiz

import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        private fun validatePackage(pack: QuickNotePackage, allowBlank: Boolean = false) {
            require(pack.sourceName.isNotBlank() && pack.sourceName.endsWith(".md", true) && '/' !in pack.sourceName && '\\' !in pack.sourceName) { "文档文件名无效" }
            require(QuickNotePaths.normalize(pack.markdownPath) == pack.markdownPath) { "文档路径无效" }
            require(pack.markdown.size <= MAX_MARKDOWN) { "Markdown 超过 4 MiB" }
            val decoded = try { QuickNotePaths.utf8(pack.markdown) } catch (e: Exception) { throw IllegalArgumentException("Markdown 必须是有效 UTF-8 编码", e) }
            require(allowBlank || decoded.isNotBlank()) { "Markdown 内容为空" }
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
    private data class Entry(
        val summary: QuickNoteSummary,
        val markdownPath: String,
        val markdownHash: String,
        val images: List<ImageInfo>,
        val storageId: String = summary.id,
        val editVersion: String = storageId,
        val creationDigest: String? = null,
    )
    private val mutex = Mutex()
    private val renderer = QuickNoteMarkdown()
    private val anchors = mutableMapOf<String, Set<String>>()
    private val changes = MutableStateFlow(0L)
    internal val aiChanges = changes.asStateFlow()
    private val index get() = AtomicFile(File(directory, "index.json"))
    private suspend fun <T> locked(block: () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }
    private fun folder(id: String): File {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "文档编号无效" }
        val target = File(directory, id)
        require(target.canonicalFile.parentFile == directory.canonicalFile) { "文档路径无效" }
        return target
    }
    private fun encode(entries: List<Entry>, local: Boolean = false): JSONObject {
        return JSONObject().put("quickNotesVersion", 2).put("documents", JSONArray(entries.map { e ->
            JSONObject().put("id", e.summary.id).put("title", e.summary.title).put("sourceName", e.summary.sourceName)
                .put("contentHash", e.summary.contentHash).put("importedAt", e.summary.importedAt)
                .put("updatedAt", e.summary.updatedAt).put("pinned", e.summary.pinned)
                .put("markdownPath", e.markdownPath).put("markdownHash", e.markdownHash)
                .put("position", JSONObject().put("anchor", e.summary.position.anchor).put("offset", e.summary.position.offset).put("fraction", e.summary.position.fraction))
                .put("images", JSONArray(e.images.map { JSONObject().put("path", it.path).put("file", it.file) }))
                .apply {
                    e.creationDigest?.let { put("creationDigest", it) }
                    if (local) put("storageId", e.storageId).put("editVersion", e.editVersion).put("excerpt", e.summary.excerpt)
                }
        }))
    }
    private fun decode(root: JSONObject, local: Boolean = false): List<Entry> {
        val version = root.getInt("quickNotesVersion")
        require(version in 1..2) { "速记存储格式不支持" }
        val values = root.getJSONArray("documents")
        require(values.length() <= MAX_DOCUMENTS) { "速记文档过多" }
        val entries = (0 until values.length()).map { n ->
            val o = values.getJSONObject(n)
            val p = o.getJSONObject("position")
            val position = QuickNotePosition(p.getString("anchor"), p.getDouble("offset"), p.getDouble("fraction"))
            validatePosition(position)
            val importedAt = o.getLong("importedAt")
            val summary = QuickNoteSummary(o.getString("id"), o.getString("title"), o.getString("sourceName"),
                o.getString("contentHash"), importedAt, position,
                updatedAt = if (o.has("updatedAt")) o.getLong("updatedAt") else importedAt,
                pinned = o.optBoolean("pinned"),
                excerpt = if (local) boundedAiText(o.optString("excerpt"), 160) else "")
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
            val storageId = if (local && version == 2) o.getString("storageId") else summary.id
            folder(storageId)
            val editVersion = if (local) o.optString("editVersion", storageId) else storageId
            require(UUID.fromString(editVersion).toString() == editVersion) { "编辑版本无效" }
            val creationDigest = if (o.has("creationDigest")) o.getString("creationDigest").also(AiJson::hash) else null
            Entry(summary, path, hash, items, storageId, editVersion, creationDigest)
        }
        require(entries.map { it.summary.id }.distinct().size == entries.size) { "文档编号重复" }
        require(entries.map { it.storageId }.distinct().size == entries.size) { "文档文件夹重复" }
        return entries
    }
    private fun readIndex(): List<Entry> {
        if (!File(directory, "index.json").exists() && !File(directory, "index.json.bak").exists()) return emptyList()
        return try { decode(JSONObject(index.openRead().use { QuickNotePaths.utf8(readBounded(it, MAX_MARKDOWN)) }), local = true) }
        catch (e: Exception) { throw IllegalStateException("速记索引无法读取，请保留文件并重试或恢复独立速记备份", e) }
    }
    private fun writeIndex(entries: List<Entry>) {
        require(entries.size <= MAX_DOCUMENTS) { "最多保存 200 份速记文档" }
        check(directory.exists() || directory.mkdirs()) { "无法建立速记目录" }
        val bytes = encode(entries, local = true).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_MARKDOWN) { "速记索引过大" }
        val file = index
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) } catch (e: Exception) { file.failWrite(output); throw e }
    }
    private fun fingerprint(pack: QuickNotePackage): String = sha((sha(pack.markdown) + JSONArray(pack.images.toSortedMap().map { JSONArray(listOf(it.key, sha(it.value))) }).toString()).toByteArray(Charsets.UTF_8))
    private fun install(
        pack: QuickNotePackage, existing: List<Entry>, restored: QuickNoteSummary? = null, creationDigest: String? = null,
    ): Entry {
        validatePackage(pack, allowBlank = creationDigest != null)
        val id = restored?.id ?: UUID.randomUUID().toString()
        // Write new files before publishing the index, even when replacing the same logical ID.
        val storageId = if (folder(id).exists()) UUID.randomUUID().toString() else id
        val text = QuickNotePaths.utf8(pack.markdown)
        val rendered = if (restored == null) renderer.render(text) else null
        val title = rendered?.headings?.firstOrNull { it.level == 1 }?.title ?: pack.sourceName.dropLast(3)
        val initial = boundedAiText(title, 1000).ifBlank { "未命名文档" }
        var display = restored?.title ?: initial
        var suffix = 2
        if (restored == null) while (existing.any { it.summary.title == display }) display = "$initial (${suffix++})"
        val hash = fingerprint(pack)
        val summary = (restored ?: QuickNoteSummary(id, display, pack.sourceName, hash, System.currentTimeMillis()))
            .copy(contentHash = hash, excerpt = excerpt(text))
        val entry = Entry(summary, pack.markdownPath, sha(pack.markdown), pack.images.map { ImageInfo(it.key, sha(it.value)) },
            storageId, UUID.randomUUID().toString(), creationDigest)
        val target = folder(storageId)
        check(target.mkdirs()) { "无法创建文档存储目录" }
        try {
            File(target, "document.md").writeBytes(pack.markdown)
            if (pack.images.isNotEmpty()) check(File(target, "assets").mkdirs())
            pack.images.forEach { (_, bytes) -> File(target, "assets/${sha(bytes)}").writeBytes(bytes) }
        } catch (e: Exception) { target.deleteRecursively(); throw e }
        return entry
    }
    private fun validatePosition(position: QuickNotePosition) {
        require(position.anchor.length <= 2048 && position.offset.isFinite() && position.offset in 0.0..10000000.0 && position.fraction.isFinite() && position.fraction in 0.0..1.0) { "阅读位置无效" }
    }

    suspend fun listDocuments(): List<QuickNoteSummary> = locked {
        val entries = readIndex()
        // Incomplete imports can leave unreferenced private folders; never reset a bad index.
        val active = entries.map { it.storageId }.toSet()
        directory.listFiles()?.filter { it.isDirectory && it.name !in active && runCatching { UUID.fromString(it.name).toString() == it.name }.getOrDefault(false) }
            ?.forEach { folder(it.name).deleteRecursively() }
        entries.map(::listSummary).sortedWith(compareByDescending<QuickNoteSummary> { it.pinned }
            .thenByDescending { it.updatedAt }.thenBy { it.id })
    }

    suspend fun importDocument(pack: QuickNotePackage, keepBoth: Boolean = false): QuickNoteImportResult = locked {
        validatePackage(pack)
        val current = readIndex()
        val hash = fingerprint(pack)
        val matches = current.filter { it.summary.contentHash == hash || it.summary.sourceName == pack.sourceName }
        if (matches.isNotEmpty() && !keepBoth) return@locked QuickNoteImportResult.Conflict(matches.map { it.summary })
        require(current.size < MAX_DOCUMENTS) { "最多保存 200 份速记文档" }
        val entry = install(pack, current)
        try { writeIndex(current + entry) } catch (e: Exception) { folder(entry.storageId).deleteRecursively(); anchors.remove(entry.summary.id); throw e }
        changes.value++
        QuickNoteImportResult.Imported(entry.summary)
    }

    /** Human editing input. The version also changes on title edits and restore, independently of AI context. */
    suspend fun editSource(id: String): QuickNoteEdit = locked {
        val entry = findDocument(readIndex(), id)
        val text = QuickNotePaths.utf8(markdown(entry))
        QuickNoteEdit(entry.summary.copy(excerpt = excerpt(text)), text, entry.markdownPath,
            entry.images.map { it.path }.toSet(), entry.editVersion)
    }

    /** Reusing a command ID with the original input returns the existing document. */
    suspend fun createDocument(command: QuickNoteCreate): QuickNoteSummary? = locked {
        AiJson.uuid(command.commandId)
        val title = editTitle(command.title)
        val bytes = editBytes(command.markdown)
        val digest = DeepSeekClient.hash(title + "\n" + sha(bytes))
        val current = readIndex()
        current.firstOrNull { it.summary.id == command.commandId }?.let {
            require(it.creationDigest == digest) { "新建速记命令已用于其他内容" }
            return@locked it.summary
        }
        if (title.isEmpty() && command.markdown.isBlank()) return@locked null
        require(current.size < MAX_DOCUMENTS) { "最多保存 200 份速记文档" }
        val filename = "${command.commandId}.md"
        val headings = renderer.render(command.markdown).headings
        val display = title.ifEmpty {
            boundedAiText(headings.firstOrNull { it.level == 1 }?.title.orEmpty(), 1000).ifBlank { "未命名速记" }
        }
        val pack = QuickNotePackage(filename, filename, bytes)
        val summary = QuickNoteSummary(command.commandId, display, filename, fingerprint(pack), System.currentTimeMillis())
        val entry = install(pack, current, summary, digest)
        publishEdit(current + entry, entry)
        entry.summary
    }

    /** Saves raw Markdown as supplied. Editors replace only the edited range before calling this. */
    suspend fun updateDocument(command: QuickNoteUpdate): QuickNoteSummary = locked {
        val current = readIndex()
        val old = findDocument(current, command.documentId)
        require(old.editVersion == command.expectedVersion) { "速记已修改，请重新打开后再保存" }
        val title = editTitle(command.title)
        require(title.isNotEmpty() || command.markdown.isNotBlank()) { "速记内容为空" }
        val bytes = editBytes(command.markdown)
        val sameContent = bytes.contentEquals(markdown(old))
        val display = title.ifEmpty { "未命名速记" }
        if (sameContent && display == old.summary.title) return@locked old.summary
        val summary = old.summary.copy(title = display, updatedAt = maxOf(old.summary.updatedAt, System.currentTimeMillis()))
        if (sameContent) {
            val updated = old.copy(summary = summary, editVersion = UUID.randomUUID().toString())
            publishEdit(current.map { if (it === old) updated else it })
            return@locked summary
        }

        // Validate the retained assets before writing a new storage directory, as for an import.
        val assets = File(folder(old.storageId), "assets")
        var remaining = MAX_PACKAGE - bytes.size
        val images = old.images.associate { image ->
            val file = File(assets, image.file)
            val data = file.inputStream().use { readBounded(it, remaining) }
            remaining -= data.size
            require(sha(data) == image.file) { "图片校验失败，未保存" }
            image.path to data
        }
        val known = renderer.render(command.markdown).headings.map { it.id }.toSet()
        val position = summary.position.let {
            if (it.anchor.isNotEmpty() && it.anchor !in known) QuickNotePosition(fraction = it.fraction) else it
        }
        val pack = QuickNotePackage(summary.sourceName, old.markdownPath, bytes, images)
        val updated = install(pack, current, summary.copy(position = position), old.creationDigest)
        publishEdit(current.map { if (it === old) updated else it }, updated)
        anchors[old.summary.id] = known
        folder(old.storageId).deleteRecursively()
        updated.summary
    }

    suspend fun setPinned(id: String, pinned: Boolean): QuickNoteSummary = locked {
        val current = readIndex()
        val old = findDocument(current, id)
        if (old.summary.pinned == pinned) return@locked old.summary
        val updated = old.copy(summary = old.summary.copy(pinned = pinned))
        publishEdit(current.map { if (it === old) updated else it })
        listSummary(updated)
    }

    suspend fun searchDocuments(query: String, limit: Int = MAX_DOCUMENTS): List<QuickNoteSearchHit> {
        val term = query.trim()
        require(limit in 1..MAX_DOCUMENTS && term.length <= 160) { "速记搜索范围无效" }
        if (term.isEmpty()) return emptyList()
        val context = currentCoroutineContext()
        return locked {
            val hits = mutableListOf<QuickNoteSearchHit>()
            for (entry in readIndex().sortedWith(compareByDescending<Entry> { it.summary.pinned }
                .thenByDescending { it.summary.updatedAt }.thenBy { it.summary.id })) {
                context.ensureActive()
                if (entry.summary.title.contains(term, ignoreCase = true)) {
                    hits += QuickNoteSearchHit(listSummary(entry), entry.summary.title)
                } else {
                    val text = QuickNotePaths.utf8(markdown(entry))
                    val at = text.indexOf(term, ignoreCase = true)
                    if (at < 0) continue
                    val start = (at - 40).coerceAtLeast(0).let { if (text[it].isLowSurrogate()) it - 1 else it }
                    val end = (at + term.length + 64).coerceAtMost(text.length)
                    val safeEnd = if (end < text.length && text[end].isLowSurrogate()) end + 1 else end
                    val snippet = text.substring(start, safeEnd).replace('\n', ' ').replace('\r', ' ')
                    hits += QuickNoteSearchHit(entry.summary.copy(excerpt = excerpt(text)), snippet)
                }
                if (hits.size == limit) break
            }
            hits
        }
    }

    private fun findDocument(entries: List<Entry>, id: String) =
        entries.firstOrNull { it.summary.id == id } ?: error("速记文档不存在")

    private fun editTitle(input: String): String = input.trim().also {
        require(it.length <= 1000 && it.none(Char::isISOControl)) { "速记标题无效或过长" }
        AiJson.utf8(it)
    }

    private fun editBytes(text: String): ByteArray {
        require(text.length <= MAX_MARKDOWN && AiJson.utf8(text) <= MAX_MARKDOWN) { "Markdown 超过 4 MiB" }
        return text.toByteArray(Charsets.UTF_8)
    }

    private fun excerpt(text: String): String {
        val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).iterator()
        if (!lines.hasNext()) return ""
        val first = lines.next()
        var body = first
        while (body.startsWith("#") && body.trimStart('#').startsWith(" ") && lines.hasNext()) body = lines.next()
        return boundedAiText(body, 160)
    }

    private fun listSummary(entry: Entry): QuickNoteSummary {
        if (entry.summary.excerpt.isNotEmpty() || markdownFile(entry).length() == 0L) return entry.summary
        return try { entry.summary.copy(excerpt = excerpt(QuickNotePaths.utf8(markdown(entry)))) }
        catch (_: java.io.IOException) { entry.summary }
        catch (_: IllegalArgumentException) { entry.summary }
    }

    private fun publishEdit(entries: List<Entry>, staged: Entry? = null) {
        try {
            // Count and validate the same ZIP stream that export uses, without retaining it.
            writeBackup(entries, null)
            writeIndex(entries)
        } catch (e: Exception) {
            staged?.let { folder(it.storageId).deleteRecursively() }
            throw e
        }
        changes.value++
    }

    private fun markdownFile(entry: Entry): File = File(folder(entry.storageId), "document.md")

    private fun markdown(entry: Entry): ByteArray = markdownFile(entry).inputStream().use { readBounded(it, MAX_MARKDOWN) }.also {
        require(sha(it) == entry.markdownHash) { "文档内容校验失败，请重新导入或恢复速记备份" }
    }

    private suspend fun <T> aiRead(block: () -> T): T = try {
        locked(block)
    } catch (e: CancellationException) {
        throw e
    } catch (e: AiStoreFailure) {
        throw e
    } catch (_: Exception) {
        throw AiStoreFailure(RejectCode.STORAGE_FAILED)
    }

    internal suspend fun aiSummary(id: String): QuickNoteSummary? = aiRead {
        readIndex().firstOrNull { it.summary.id == id }?.takeIf { markdownFile(it).isFile }?.summary
    }

    internal suspend fun aiContext(id: String, hash: String, sectionId: String?, query: String,
        previous: QuickNoteAiTurn? = null): QuickNoteAiContext = aiRead {
        val entry = readIndex().firstOrNull { it.summary.id == id } ?: throw AiStoreFailure(RejectCode.NOT_FOUND)
        if (entry.summary.contentHash != hash) throw AiStoreFailure(RejectCode.STALE_OWNER)
        if (!markdownFile(entry).isFile) throw AiStoreFailure(RejectCode.NOT_FOUND)
        val document = renderer.aiDocument(QuickNotePaths.utf8(markdown(entry)))
        selectQuickNoteAiContext(document, sectionId, query, previous)
    }

    internal suspend fun previewAiContext(document: QuickNoteSummary, section: QuickNoteHeading?, query: String): ResultOf<QuickNoteAiContext> =
        aiLocalResult { aiContext(document.id, document.contentHash, section?.id, query) }

    suspend fun openDocument(id: String): QuickNoteDocument = locked {
        val entry = readIndex().firstOrNull { it.summary.id == id } ?: error("速记文档不存在")
        val available = entry.images.filter { File(folder(entry.storageId), "assets/${it.file}").isFile }.map { it.path }.toSet()
        val rendered = renderer.render(QuickNotePaths.utf8(markdown(entry)), id, entry.markdownPath, available)
        anchors[id] = rendered.headings.map { it.id }.toSet()
        QuickNoteDocument(entry.summary, rendered)
    }

    suspend fun readAsset(id: String, encodedPath: String): QuickNoteAsset? = locked {
        val entry = readIndex().firstOrNull { it.summary.id == id } ?: return@locked null
        val path = QuickNotePaths.normalize(QuickNotePaths.decode(encodedPath))
        val image = entry.images.firstOrNull { it.path == path } ?: return@locked null
        val target = File(folder(entry.storageId), "assets/${image.file}")
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
        val entry = current.firstOrNull { it.summary.id == id } ?: error("速记文档不存在")
        writeIndex(current.filterNot { it.summary.id == id })
        anchors.remove(id)
        folder(entry.storageId).deleteRecursively()
        changes.value++
        Unit
    }

    /** Independent ZIP backup of Markdown, display metadata and reader positions. */
    suspend fun exportBackup(): ByteArray = locked {
        val output = ByteArrayOutputStream()
        writeBackup(readIndex(), output)
        output.toByteArray()
    }

    private data class BackupFile(val name: String, val file: File, val hash: String, val limit: Int)

    private class BoundedBackupOutput(private val output: OutputStream?) : OutputStream() {
        private var written = 0L
        private fun count(bytes: Int) {
            written += bytes
            require(written <= MAX_BACKUP) { "速记备份超过 64 MiB，请减少文档后重试" }
        }
        override fun write(value: Int) {
            count(1)
            output?.write(value)
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            count(length)
            output?.write(bytes, offset, length)
        }
    }

    private fun writeBackup(entries: List<Entry>, output: OutputStream?) {
        val manifest = encode(entries).toString().toByteArray(Charsets.UTF_8)
        require(manifest.size <= MAX_MARKDOWN) { "速记备份索引过大" }
        val files = buildList {
            for (entry in entries) {
                val prefix = "documents/${entry.summary.id}"
                add(BackupFile("$prefix/document.md", markdownFile(entry), entry.markdownHash, MAX_MARKDOWN))
                for (image in entry.images.distinctBy { it.file }) {
                    val path = "assets/${image.file}"
                    add(BackupFile("$prefix/$path", File(folder(entry.storageId), path), image.file, MAX_PACKAGE))
                }
            }
        }
        require(files.size + 1 <= MAX_ENTRIES) { "速记备份文件超过 512 项，请减少文档或资源" }
        var total = manifest.size.toLong()
        for (item in files) {
            require(item.file.isFile && item.file.length() <= item.limit) { "速记备份文件损坏或超过大小限制" }
            total += item.file.length()
            require(total <= MAX_BACKUP) { "速记备份超过 64 MiB，请减少文档后重试" }
        }
        ZipOutputStream(BoundedBackupOutput(output)).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest)
            zip.closeEntry()
            val buffer = ByteArray(8192)
            total = manifest.size.toLong()
            for (item in files) {
                zip.putNextEntry(ZipEntry(item.name))
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                item.file.inputStream().use { input ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        total += count
                        require(size <= item.limit && total <= MAX_BACKUP) { "速记备份文件超过大小限制" }
                        digest.update(buffer, 0, count)
                        zip.write(buffer, 0, count)
                    }
                }
                require(digest.digest().joinToString("") { "%02x".format(it) } == item.hash) { "速记备份文件校验失败" }
                zip.closeEntry()
            }
        }
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
            validatePackage(pack, allowBlank = entry.creationDigest != null)
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
                val duplicate = updated.any { other ->
                    other.summary.contentHash == old.summary.contentHash &&
                        (old.creationDigest == null || other.summary.id == old.summary.id)
                }
                if (replace || !duplicate) {
                    require(updated.size < MAX_DOCUMENTS) { "恢复后速记文档超过 200 份" }
                    val summary = if (updated.any { it.summary.id == old.summary.id })
                        old.summary.copy(id = UUID.randomUUID().toString()) else old.summary
                    val entry = install(pack, updated, summary, old.creationDigest)
                    created.add(entry); updated.add(entry)
                }
            }
            writeIndex(updated)
        } catch (e: Exception) {
            created.forEach { folder(it.storageId).deleteRecursively() }
            throw e
        }
        if (replace) {
            val active = updated.map { it.storageId }.toSet()
            original.forEach {
                if (it.storageId !in active) folder(it.storageId).deleteRecursively()
                anchors.remove(it.summary.id)
            }
        }
        if (replace || created.isNotEmpty()) changes.value++
        created.size
    }
}
