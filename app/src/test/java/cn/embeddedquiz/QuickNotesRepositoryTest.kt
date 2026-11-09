package cn.embeddedquiz

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class QuickNotesRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aD1sAAAAASUVORK5CYII=")
    private fun pack(text: String = "# 中文标题\n\n## 下一节\n正文", name: String = "知识.md") = QuickNotesRepository.parsePackage(name, text.toByteArray(Charsets.UTF_8))
    private suspend fun withDirectory(action: suspend (File, QuickNotesRepository) -> Unit) {
        val directory = File(context.filesDir, "quicknotes-test-${UUID.randomUUID()}")
        try { action(directory, QuickNotesRepository(directory)) } finally { directory.deleteRecursively() }
    }
    private fun zip(files: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip -> files.forEach { (name, data) -> zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() } }
        return out.toByteArray()
    }
    private fun imported(result: QuickNoteImportResult) = (result as QuickNoteImportResult.Imported).document

    @Test
    fun copiedUtf8DocumentAndPerDocumentPositionSurviveRepositoryRestart() = runBlocking {
        withDirectory { directory, repo ->
            val input = pack()
            val first = imported(repo.importDocument(input))
            val second = imported(repo.importDocument(pack("# 另一个文档", "另一份.md")))
            val document = repo.openDocument(first.id)
            val position = QuickNotePosition(document.rendered.headings.last().id, 24.5, 0.6)
            repo.savePosition(first.id, position)
            input.markdown.fill(0)
            val restarted = QuickNotesRepository(directory)
            assertEquals(2, restarted.listDocuments().size)
            assertTrue(restarted.openDocument(first.id).rendered.htmlBody.contains("中文标题"))
            assertEquals(position, restarted.openDocument(first.id).summary.position)
            assertEquals(QuickNotePosition(), restarted.openDocument(second.id).summary.position)
        }
    }

    @Test
    fun duplicateImportRequiresExplicitKeepBothAndNeverReplacesOriginal() = runBlocking {
        withDirectory { _, repo ->
            val first = imported(repo.importDocument(pack()))
            val position = QuickNotePosition("中文标题", 10.0, 0.2)
            repo.savePosition(first.id, position)
            assertTrue(repo.importDocument(pack()) is QuickNoteImportResult.Conflict)
            assertTrue(repo.importDocument(pack("# 已变更的同名文件")) is QuickNoteImportResult.Conflict)
            assertEquals(1, repo.listDocuments().size)
            val second = imported(repo.importDocument(pack(), keepBoth = true))
            assertNotEquals(first.id, second.id)
            assertNotEquals(first.title, second.title)
            assertEquals(position, repo.openDocument(first.id).summary.position)
            assertEquals(QuickNotePosition(), repo.openDocument(second.id).summary.position)
        }
    }

    @Test
    fun zippedRelativeChineseImagesAreCopiedAndResolveAfterRestart() = runBlocking {
        withDirectory { directory, repo ->
            val bytes = zip(mapOf("docs/main.md" to "# 图文\n\n![中文图](../assets/示例图.png)".toByteArray(), "assets/示例图.png" to png))
            val item = imported(repo.importDocument(QuickNotesRepository.parsePackage("图文.zip", bytes)))
            val restarted = QuickNotesRepository(directory)
            val document = restarted.openDocument(item.id)
            assertTrue(document.rendered.htmlBody.contains("<img "))
            assertTrue(document.rendered.warnings.isEmpty())
            val asset = restarted.readAsset(item.id, QuickNotePaths.encode("assets/示例图.png"))!!
            assertEquals("image/png", asset.mimeType)
            assertArrayEquals(png, asset.bytes)
            assertNull(restarted.readAsset(item.id, "assets/missing.png"))
            assertTrue(runCatching { restarted.readAsset(item.id, "../outside.png") }.isFailure)
        }
    }

    @Test
    fun missingImagesAndInvalidInputsAreExplicitWithoutChangingExistingDocuments() = runBlocking {
        withDirectory { _, repo ->
            val item = imported(repo.importDocument(pack("# 缺图\n\n![截图](assets/missing.png)")))
            assertTrue(repo.openDocument(item.id).rendered.warnings.any { it.contains("图片未载入") })
            val before = repo.listDocuments()
            assertTrue(runCatching { QuickNotesRepository.parsePackage("bad.md", byteArrayOf(0xC3.toByte(), 0x28)) }.isFailure)
            assertTrue(runCatching { QuickNotesRepository.parsePackage("bad.md", ByteArray(QuickNotesRepository.MAX_MARKDOWN + 1)) }.isFailure)
            assertTrue(runCatching { QuickNotesRepository.parsePackage("bad.txt", "# a".toByteArray()) }.isFailure)
            assertTrue(runCatching { QuickNotesRepository.parsePackage("bad.zip", zip(mapOf("../outside.png" to png, "a.md" to "# a".toByteArray()))) }.isFailure)
            assertTrue(runCatching { QuickNotesRepository.parsePackage("bad.zip", zip(mapOf("a.md" to "# a".toByteArray(), "b.md" to "# b".toByteArray()))) }.isFailure)
            assertEquals(before, repo.listDocuments())
        }
    }

    @Test
    fun deletionRemovesOnlySelectedDocumentAndLatePositionCannotResurrectIt() = runBlocking {
        withDirectory { directory, repo ->
            val first = imported(repo.importDocument(pack()))
            val second = imported(repo.importDocument(pack("# 保留", "保留.md")))
            repo.deleteDocument(first.id)
            assertFalse(File(directory, first.id).exists())
            assertEquals(listOf(second.id), repo.listDocuments().map { it.id })
            assertTrue(runCatching { repo.savePosition(first.id, QuickNotePosition()) }.isFailure)
            assertTrue(runCatching { repo.openDocument(first.id) }.isFailure)
            assertEquals(1, QuickNotesRepository(directory).listDocuments().size)
        }
    }

    @Test
    fun independentBackupIncludesImagesAndPositionsWithMergeAndExplicitReplace() = runBlocking {
        withDirectory { directory, source ->
            val imagePack = QuickNotesRepository.parsePackage("bundle.zip", zip(mapOf("main.md" to "# 图文\n\n![图](a.png)".toByteArray(), "a.png" to png)))
            val first = imported(source.importDocument(imagePack))
            val position = QuickNotePosition("图文", 100.0, 0.75)
            source.savePosition(first.id, position)
            val backup = source.exportBackup()
            val target = QuickNotesRepository(File(directory, "target"))
            val retained = imported(target.importDocument(pack("# 原有", "原有.md")))
            assertEquals(1, target.restoreBackup(backup))
            assertEquals(2, target.listDocuments().size)
            assertEquals(0, target.restoreBackup(backup))
            val restored = target.listDocuments().first { it.id != retained.id }
            assertEquals(position, target.openDocument(restored.id).summary.position)
            assertArrayEquals(png, target.readAsset(restored.id, "a.png")!!.bytes)
            assertEquals(1, target.restoreBackup(backup, replace = true))
            assertEquals(1, target.listDocuments().size)
            assertFalse(target.listDocuments().any { it.id == retained.id })
        }
    }

    @Test
    fun replacingBackupRepairsMissingDocumentFolderWithoutLosingItsId() = runBlocking {
        withDirectory { directory, repo ->
            val original = imported(repo.importDocument(pack("# DMA\n\nDescriptor ring")))
            val backup = repo.exportBackup()
            assertTrue(File(directory, original.id).deleteRecursively())
            assertEquals(1, repo.restoreBackup(backup, replace = true))
            assertEquals(original.id, repo.listDocuments().single().id)
            assertEquals("DMA", repo.openDocument(original.id).rendered.headings.single().title)
        }
    }

    @Test
    fun damagedBackupAndIndexDoNotSilentlyEraseDataAndExplicitRestoreCanRecover() = runBlocking {
        withDirectory { directory, repo ->
            val original = imported(repo.importDocument(pack()))
            val backup = repo.exportBackup()
            val before = repo.listDocuments()
            assertTrue(runCatching { repo.restoreBackup(zip(mapOf("manifest.json" to "{}".toByteArray()))) }.isFailure)
            assertEquals(before, repo.listDocuments())
            File(directory, "index.json").writeText("damaged")
            assertTrue(runCatching { QuickNotesRepository(directory).listDocuments() }.isFailure)
            assertTrue(File(directory, "${original.id}/document.md").isFile)
            val restarted = QuickNotesRepository(directory)
            assertEquals(1, restarted.restoreBackup(backup, replace = true))
            assertTrue(restarted.openDocument(restarted.listDocuments().single().id).rendered.htmlBody.contains("中文标题"))
        }
    }

    @Test
    fun invalidReadingPositionsAreRejectedWithoutChangingLastSavedPosition() = runBlocking {
        withDirectory { _, repo ->
            val item = imported(repo.importDocument(pack()))
            val position = QuickNotePosition("中文标题", 4.25, 0.25)
            repo.savePosition(item.id, position)
            listOf(QuickNotePosition("not-a-heading"), QuickNotePosition(offset = Double.NaN), QuickNotePosition(fraction = 1.1), QuickNotePosition(offset = -1.0)).forEach {
                assertTrue(runCatching { repo.savePosition(item.id, it) }.isFailure)
            }
            assertEquals(position, repo.openDocument(item.id).summary.position)
        }
    }

    @Test
    fun manualCreationIsReplaySafeAndPreservesSameNamedNotesAcrossBackup() = runBlocking {
        withDirectory { directory, repo ->
            assertNull(repo.createDocument(QuickNoteCreate(UUID.randomUUID().toString(), " ", "\n")))
            assertTrue(repo.listDocuments().isEmpty())
            val command = QuickNoteCreate(UUID.randomUUID().toString(), " 同名速记 ", "首行\n设备 USART")
            val results = coroutineScope { List(2) { async { repo.createDocument(command) } }.awaitAll() }
            val first = requireNotNull(results.first())
            assertEquals(first, results.last())
            val restarted = QuickNotesRepository(directory)
            assertEquals(first, restarted.createDocument(command))
            assertTrue(runCatching { restarted.createDocument(command.copy(markdown = "另一份内容")) }.isFailure)
            val second = requireNotNull(restarted.createDocument(command.copy(commandId = UUID.randomUUID().toString())))
            val titleOnly = requireNotNull(restarted.createDocument(QuickNoteCreate(UUID.randomUUID().toString(), "只有标题", "")))
            assertEquals(first.title, second.title)
            assertEquals(first.contentHash, second.contentHash)
            assertNotEquals(first.id, second.id)
            assertEquals("", restarted.editSource(titleOnly.id).text)
            val pinned = restarted.setPinned(first.id, true)
            assertEquals(first.updatedAt, pinned.updatedAt)
            assertEquals(first.id, restarted.listDocuments().first().id)
            val hits = restarted.searchDocuments("usart")
            assertEquals(setOf(first.id, second.id), hits.map { it.summary.id }.toSet())
            assertTrue(hits.all { it.snippet.contains("USART") })
            assertEquals(titleOnly.id, restarted.searchDocuments("只有标题").single().summary.id)

            val restored = QuickNotesRepository(File(directory, "restored"))
            val backup = restarted.exportBackup()
            assertEquals(3, restored.restoreBackup(backup))
            assertEquals(0, restored.restoreBackup(backup))
            assertEquals(restarted.listDocuments(), restored.listDocuments())
            assertEquals(pinned, restored.createDocument(command))
            assertEquals("", restored.editSource(titleOnly.id).text)
        }
    }

    @Test
    fun partialEditPreservesCrLfTablesCodeImagesAndIdentity() = runBlocking {
        withDirectory { directory, repo ->
            val raw = "# 原标题\r\n\r\n## 旧章节\r\n\r\n旧段落\r\n\r\n" +
                "| 表头 | 列 |\r\n| --- | --- |\r\n| A | B |\r\n\r\n" +
                "~~~c\r\nint x;\r\n~~~\r\n\r\n![图](../a.png)\r\n"
            val original = imported(repo.importDocument(QuickNotePackage("main.md", "docs/main.md",
                raw.toByteArray(), mapOf("a.png" to png))))
            val oldAnchor = repo.openDocument(original.id).rendered.headings.last().id
            repo.savePosition(original.id, QuickNotePosition(oldAnchor, 12.0, 0.6))
            val draft = repo.editSource(original.id)
            assertEquals(raw, draft.text)
            assertEquals("docs/main.md", draft.markdownPath)
            assertEquals(setOf("a.png"), draft.imagePaths)
            val expected = raw.replace("旧段落", "修改段落")
            val updated = repo.updateDocument(QuickNoteUpdate(original.id, draft.version, draft.summary.title, expected))
            assertEquals(original.id, updated.id)
            assertEquals(original.title, updated.title)
            assertNotEquals(original.contentHash, updated.contentHash)
            assertEquals(QuickNotePosition(oldAnchor, 12.0, 0.6), updated.position)
            assertEquals(expected, repo.editSource(updated.id).text)
            assertFalse(File(directory, original.id).exists())
            assertTrue(repo.openDocument(updated.id).rendered.warnings.isEmpty())
            assertArrayEquals(png, repo.readAsset(updated.id, "a.png")!!.bytes)

            val latest = repo.editSource(updated.id)
            val removed = repo.updateDocument(QuickNoteUpdate(updated.id, latest.version, latest.summary.title,
                expected.replace("## 旧章节\r\n\r\n", "")))
            assertEquals(QuickNotePosition(fraction = 0.6), removed.position)
            assertTrue(runCatching { repo.savePosition(updated.id, QuickNotePosition(oldAnchor)) }.isFailure)
            val restored = QuickNotesRepository(File(directory, "restored"))
            assertEquals(1, restored.restoreBackup(repo.exportBackup()))
            assertEquals(removed, restored.listDocuments().single())
            assertEquals(repo.editSource(updated.id).text, restored.editSource(updated.id).text)
            assertArrayEquals(png, restored.readAsset(updated.id, "a.png")!!.bytes)
        }
    }

    @Test
    fun oldIndexAndPortableBackupDefaultNewMetadata() = runBlocking {
        withDirectory { directory, repo ->
            val original = imported(repo.importDocument(pack()))
            repo.setPinned(original.id, true)
            val files = mutableMapOf<String, ByteArray>()
            ZipInputStream(repo.exportBackup().inputStream()).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    files[entry.name] = input.readBytes()
                }
            }
            val legacy = JSONObject(String(files.getValue("manifest.json"), Charsets.UTF_8)).put("quickNotesVersion", 1)
            legacy.getJSONArray("documents").getJSONObject(0).apply {
                remove("updatedAt")
                remove("pinned")
            }
            files["manifest.json"] = legacy.toString().toByteArray()
            File(directory, "index.json").writeText(legacy.toString())
            val old = QuickNotesRepository(directory)
            assertEquals(original, old.listDocuments().single())
            assertEquals(original.id, old.editSource(original.id).version)

            val restored = QuickNotesRepository(File(directory, "legacy-restored"))
            assertEquals(1, restored.restoreBackup(zip(files)))
            assertEquals(original, restored.listDocuments().single())
            assertEquals(repo.editSource(original.id).text, restored.editSource(original.id).text)
        }
    }

    @Test
    fun corruptedAssetRejectsEditBeforePublishingVersion() = runBlocking {
        withDirectory { directory, repo ->
            val original = imported(repo.importDocument(QuickNotePackage("a.md", "a.md",
                "原文 ![图](a.png)".toByteArray(), mapOf("a.png" to png))))
            val before = repo.editSource(original.id)
            val image = File(directory, "${original.id}/assets/${QuickNotesRepository.sha(png)}")
            image.writeBytes(png.copyOf().also { it[it.lastIndex] = 0 })
            val failure = runCatching {
                repo.updateDocument(QuickNoteUpdate(original.id, before.version, original.title, "修改原文"))
            }.exceptionOrNull()
            assertNotNull(failure)
            assertEquals(before, repo.editSource(original.id))
            assertEquals(listOf(original.id), directory.listFiles()!!.filter(File::isDirectory).map { it.name })
        }
    }

    @Test
    fun backupLimitRejectsEditWithoutReplacingPublishedFiles() = runBlocking {
        withDirectory { directory, repo ->
            val image = ByteArray(21 * 1024 * 1024).also { png.copyInto(it) }
            val docs = (1..3).map { n -> imported(repo.importDocument(QuickNotePackage("$n.md", "$n.md",
                "# 文档$n".toByteArray(), mapOf("a.png" to image)))) }
            val before = repo.listDocuments()
            val edited = repo.editSource(docs.first().id)
            val failure = runCatching { repo.updateDocument(QuickNoteUpdate(edited.summary.id, edited.version,
                edited.summary.title, "新正文\n\n" + "x".repeat(2 * 1024 * 1024))) }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure!!.message.orEmpty().contains("64 MiB"))
            assertEquals(before, repo.listDocuments())
            assertEquals(edited, repo.editSource(edited.summary.id))
            assertEquals(docs.map { it.id }.toSet(), directory.listFiles()!!.filter(File::isDirectory).map { it.name }.toSet())
            assertTrue(repo.exportBackup().isNotEmpty())
        }
    }

    @Test
    fun editVersionIsBoundToDocumentAndProtectsConcurrentAndRestoredEdits() = runBlocking {
        withDirectory { _, repo ->
            val first = imported(repo.importDocument(pack()))
            val second = imported(repo.importDocument(pack("# 其他文档", "其他.md")))
            val draft = repo.editSource(first.id)
            repo.setPinned(first.id, true)
            assertEquals(draft.version, repo.editSource(first.id).version)
            val noChange = repo.updateDocument(QuickNoteUpdate(first.id, draft.version, draft.summary.title, draft.text))
            assertEquals(first.updatedAt, noChange.updatedAt)
            assertEquals(draft.version, repo.editSource(first.id).version)
            val renamed = repo.updateDocument(QuickNoteUpdate(first.id, draft.version, "显示名称", draft.text))
            assertEquals(first.contentHash, renamed.contentHash)
            assertTrue(renamed.pinned)
            val edit = repo.editSource(first.id)
            assertNotEquals(draft.version, edit.version)
            assertTrue(runCatching { repo.updateDocument(QuickNoteUpdate(second.id, edit.version, "错文档", "不应保存")) }.isFailure)
            assertEquals("# 其他文档", repo.editSource(second.id).text)
            val attempts = coroutineScope {
                listOf("第一份并发修改", "第二份并发修改").map { text ->
                    async { runCatching { repo.updateDocument(QuickNoteUpdate(first.id, edit.version, edit.summary.title, text)) } }
                }.awaitAll()
            }
            assertEquals(1, attempts.count { it.isSuccess })
            val saved = repo.editSource(first.id)
            for (text in listOf(" ", "x".repeat(QuickNotesRepository.MAX_MARKDOWN + 1))) {
                assertTrue(runCatching { repo.updateDocument(QuickNoteUpdate(first.id, saved.version, saved.summary.title, text)) }.isFailure)
                assertEquals(saved, repo.editSource(first.id))
            }
            repo.restoreBackup(repo.exportBackup(), replace = true)
            assertTrue(runCatching { repo.updateDocument(QuickNoteUpdate(first.id, saved.version, "旧草稿", "冲突")) }.isFailure)
            assertEquals(saved.text, repo.editSource(first.id).text)
        }
    }
}
