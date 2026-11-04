package cn.embeddedquiz

import android.database.sqlite.SQLiteDatabaseCorruptException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory

/** A damaged database is evidence to preserve, never permission to delete and recreate user data. */
internal class PreservingOpenHelperFactory : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val original = configuration.callback
        val callback = object : SupportSQLiteOpenHelper.Callback(original.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = original.onConfigure(db)
            override fun onCreate(db: SupportSQLiteDatabase) = original.onCreate(db)
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                original.onUpgrade(db, oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                original.onDowngrade(db, oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) = original.onOpen(db)
            override fun onCorruption(db: SupportSQLiteDatabase) {
                throw SQLiteDatabaseCorruptException("本机数据库损坏，文件已保留")
            }
        }
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name).callback(callback)
                .noBackupDirectory(configuration.useNoBackupDirectory)
                .allowDataLossOnRecovery(false).build()
        )
    }
}