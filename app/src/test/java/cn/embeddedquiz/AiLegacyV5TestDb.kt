package cn.embeddedquiz

import android.content.Context
import androidx.room.*

/** Frozen real v5 schema for upgrades; it never follows production's current version. */
@Database(entities=[QuestionRow::class,ExamTermination::class,Progress::class,Attempt::class,
    AiEntry::class,StickyNote::class,LearningSettings::class,PersonalAnswer::class,QuestionPersonal::class],version=5,exportSchema=false)
abstract class AiLegacyV5TestDb:RoomDatabase() {
    abstract fun dao():QuizDao
    companion object {
        fun open(context:Context,name:String):AiLegacyV5TestDb = Room.databaseBuilder(context,AiLegacyV5TestDb::class.java,name).allowMainThreadQueries().build()
    }
}
