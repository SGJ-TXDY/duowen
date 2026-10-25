package cn.embeddedquiz

/** Standalone reading data: none of these types belongs to the quiz database or SRS. */
data class QuickNotePosition(
    val anchor: String = "",
    val offset: Double = 0.0,
    val fraction: Double = 0.0,
)

data class QuickNoteSummary(
    val id: String,
    val title: String,
    val sourceName: String,
    val contentHash: String,
    val importedAt: Long,
    val position: QuickNotePosition = QuickNotePosition(),
)

data class QuickNoteHeading(val id: String, val level: Int, val title: String)
data class QuickNoteRendered(
    val htmlBody: String,
    val headings: List<QuickNoteHeading>,
    val codeBlocks: Map<String, String>,
    val warnings: List<String>,
)
data class QuickNoteDocument(val summary: QuickNoteSummary, val rendered: QuickNoteRendered)
data class QuickNoteAsset(val bytes: ByteArray, val mimeType: String)
data class QuickNotePackage(
    val sourceName: String,
    val markdownPath: String,
    val markdown: ByteArray,
    val images: Map<String, ByteArray> = emptyMap(),
)
sealed class QuickNoteImportResult {
    data class Imported(val document: QuickNoteSummary) : QuickNoteImportResult()
    data class Conflict(val existing: List<QuickNoteSummary>) : QuickNoteImportResult()
}
data class QuickNotesState(
    val visible: Boolean = false,
    val loading: Boolean = true,
    val working: Boolean = false,
    val documents: List<QuickNoteSummary> = emptyList(),
    val reader: QuickNoteDocument? = null,
    val conflict: List<QuickNoteSummary> = emptyList(),
    val error: String = "",
)
