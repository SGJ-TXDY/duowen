package cn.embeddedquiz

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Additive production 5→6 migration, also used by disk preflight and isolated fixtures. */
object AiConversationMigration : Migration(5, 6) {
    val statements: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `ai_sessions` (`sessionId` TEXT NOT NULL, `questionId` TEXT NOT NULL, `contentHash` TEXT NOT NULL, `questionSnapshotVersion` INTEGER NOT NULL, `questionSnapshot` TEXT NOT NULL, `title` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `sessionRevision` INTEGER NOT NULL, `contextRevision` INTEGER NOT NULL, `draftText` TEXT NOT NULL, `draftRevision` INTEGER NOT NULL, `lastEditorEpoch` TEXT, `lastLocalEditVersion` INTEGER, `encodedBytes` INTEGER NOT NULL, `activeEditorEpoch` TEXT, PRIMARY KEY(`sessionId`))",
        "CREATE INDEX IF NOT EXISTS `index_ai_sessions_questionId_contentHash_updatedAt_sessionId` ON `ai_sessions` (`questionId`, `contentHash`, `updatedAt`, `sessionId`)",
        "CREATE INDEX IF NOT EXISTS `index_ai_sessions_encodedBytes` ON `ai_sessions` (`encodedBytes`)",
        "CREATE TABLE IF NOT EXISTS `ai_messages` (`messageId` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `seq` INTEGER NOT NULL, `role` TEXT NOT NULL, `text` TEXT NOT NULL, `completion` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `encodedBytes` INTEGER NOT NULL, PRIMARY KEY(`messageId`), FOREIGN KEY(`sessionId`) REFERENCES `ai_sessions`(`sessionId`) ON UPDATE NO ACTION ON DELETE RESTRICT)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_ai_messages_sessionId_seq` ON `ai_messages` (`sessionId`, `seq`)",
        "CREATE INDEX IF NOT EXISTS `index_ai_messages_encodedBytes` ON `ai_messages` (`encodedBytes`)",
        "CREATE TABLE IF NOT EXISTS `ai_runs` (`requestId` TEXT NOT NULL, `commandId` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `userMessageId` TEXT NOT NULL, `assistantMessageId` TEXT NOT NULL, `model` TEXT NOT NULL, `requestType` TEXT NOT NULL, `coordinatorEpoch` TEXT NOT NULL, `contextSnapshot` TEXT NOT NULL, `contextDigest` TEXT NOT NULL, `cacheKey` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `billingDay` INTEGER NOT NULL, `billingZone` TEXT NOT NULL, `contextSnapshotVersion` INTEGER NOT NULL, `status` TEXT NOT NULL, `source` TEXT NOT NULL, `sourceRequestId` TEXT, `retryOf` TEXT, `terminalReason` TEXT, `promptVersion` TEXT NOT NULL, `effectiveParameters` TEXT NOT NULL, `reservedBytes` INTEGER NOT NULL, `eventSeq` INTEGER NOT NULL, `usageCutoffSeq` INTEGER, `networkDispatched` INTEGER NOT NULL, `finishedAt` INTEGER, `providerResponseId` TEXT, `finishReason` TEXT, `inputTokens` INTEGER, `outputTokens` INTEGER, `hitTokens` INTEGER, `usageStatus` TEXT NOT NULL, `priceVersion` TEXT, `missRate` TEXT, `hitRate` TEXT, `outputRate` TEXT, `estimatedUsd` TEXT, `currency` TEXT NOT NULL, `errorCode` TEXT, `encodedBytes` INTEGER NOT NULL, PRIMARY KEY(`requestId`), FOREIGN KEY(`sessionId`) REFERENCES `ai_sessions`(`sessionId`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`userMessageId`) REFERENCES `ai_messages`(`messageId`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`assistantMessageId`) REFERENCES `ai_messages`(`messageId`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`retryOf`) REFERENCES `ai_runs`(`requestId`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`sourceRequestId`) REFERENCES `ai_runs`(`requestId`) ON UPDATE NO ACTION ON DELETE RESTRICT)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_ai_runs_commandId` ON `ai_runs` (`commandId`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_ai_runs_assistantMessageId` ON `ai_runs` (`assistantMessageId`)",
        "CREATE INDEX IF NOT EXISTS `index_ai_runs_userMessageId_createdAt_requestId` ON `ai_runs` (`userMessageId`, `createdAt`, `requestId`)",
        "CREATE INDEX IF NOT EXISTS `index_ai_runs_sessionId_createdAt_requestId` ON `ai_runs` (`sessionId`, `createdAt`, `requestId`)",
        "CREATE INDEX IF NOT EXISTS `index_ai_runs_encodedBytes` ON `ai_runs` (`encodedBytes`)",
        "CREATE INDEX IF NOT EXISTS `index_ai_runs_status_reservedBytes` ON `ai_runs` (`status`, `reservedBytes`)",
        "CREATE INDEX IF NOT EXISTS `index_ai_runs_retryOf` ON `ai_runs` (`retryOf`)",
        "CREATE INDEX IF NOT EXISTS `index_ai_runs_sourceRequestId` ON `ai_runs` (`sourceRequestId`)",
        "CREATE TABLE IF NOT EXISTS `ai_commands` (`commandId` TEXT NOT NULL, `kind` TEXT NOT NULL, `payloadDigest` TEXT NOT NULL, `receiptJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `sessionId` TEXT, `requestId` TEXT, `encodedBytes` INTEGER NOT NULL, PRIMARY KEY(`commandId`), FOREIGN KEY(`sessionId`) REFERENCES `ai_sessions`(`sessionId`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`requestId`) REFERENCES `ai_runs`(`requestId`) ON UPDATE NO ACTION ON DELETE RESTRICT)",
        "CREATE INDEX IF NOT EXISTS `index_ai_commands_sessionId_createdAt_commandId` ON `ai_commands` (`sessionId`, `createdAt`, `commandId`)",
        "CREATE INDEX IF NOT EXISTS `index_ai_commands_encodedBytes` ON `ai_commands` (`encodedBytes`)",
        "CREATE INDEX IF NOT EXISTS `index_ai_commands_requestId` ON `ai_commands` (`requestId`)",
        "CREATE TABLE IF NOT EXISTS `ai_response_cache` (`cacheKey` TEXT NOT NULL, `sourceRequestId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `protocolVersion` INTEGER NOT NULL, `encodedBytes` INTEGER NOT NULL, PRIMARY KEY(`cacheKey`), FOREIGN KEY(`sourceRequestId`) REFERENCES `ai_runs`(`requestId`) ON UPDATE NO ACTION ON DELETE RESTRICT)",
        "CREATE INDEX IF NOT EXISTS `index_ai_response_cache_encodedBytes` ON `ai_response_cache` (`encodedBytes`)",
        "CREATE INDEX IF NOT EXISTS `index_ai_response_cache_sourceRequestId` ON `ai_response_cache` (`sourceRequestId`)",
    )
    override fun migrate(db: SupportSQLiteDatabase) { statements.forEach(db::execSQL) }
}
