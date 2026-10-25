package cn.embeddedquiz

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
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
}
