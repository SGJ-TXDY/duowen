package cn.embeddedquiz

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object StickyNoteSourceMigration : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        for (column in listOf("sourceRequestId", "sourceCommandId", "sourceQuestionId", "sourceQuestionHash", "sourceQuestionLabel")) {
            db.execSQL("ALTER TABLE sticky_notes ADD COLUMN $column TEXT")
        }
        db.execSQL("CREATE UNIQUE INDEX index_sticky_notes_sourceRequestId ON sticky_notes(sourceRequestId)")
        db.execSQL("CREATE UNIQUE INDEX index_sticky_notes_sourceCommandId ON sticky_notes(sourceCommandId)")
    }
}
