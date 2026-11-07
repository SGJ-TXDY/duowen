package cn.embeddedquiz

import android.content.Context
import java.io.File

/** Conservative live local headroom check. Provider capacity is decided by actual stream IO. */
internal object AiStorageResources {
    /** Check live space on entry, 1 MiB growth, progress reset, 128 rows, or 500 ms.
     * Statting both filesystems for every 8 KiB chunk is itself an avoidable IO hot path. */
    fun guard(context:Context,clock:()->Long=System::nanoTime,inspect:()->Unit={ check(context,0) }):(Long)->Unit {
        val lock=Any();var lastBytes:Long?=null;var lastTime=0L;var calls=0
        return { bytes -> synchronized(lock) {
            require(bytes>=0);val now=clock();val prior=lastBytes;calls++
            if(prior==null || bytes<prior || bytes-prior>=1024L*1024 || calls>=128 || now-lastTime>=500_000_000L) {
                inspect();lastBytes=bytes;lastTime=now;calls=0
            }
        } }
    }
    fun check(context:Context, growingBytes:Long) {
        require(growingBytes>=0)
        val work=File(context.cacheDir,"complete-backup")
        check(work.isDirectory || work.mkdirs()) { "无法创建备份工作目录" }
        check(work.canWrite()) { "备份工作目录不可写" }
        // In addition to current free space retain 16 MiB for indexes/WAL. Spool, staging and
        // target growth invoke this again; no promise that another process cannot consume space.
        check(work.usableSpace >= 16L*1024*1024) { "本机空间不足，备份或恢复未完成" }
        check(context.filesDir.usableSpace >= 16L*1024*1024) { "数据库空间不足，恢复未完成" }
    }
}
