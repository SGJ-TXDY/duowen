package cn.embeddedquiz

import android.annotation.SuppressLint
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** A completed call means the change reached disk. Do not replace this with apply(). */
@SuppressLint("ApplySharedPref")
fun SharedPreferences.writeDurably(edit: SharedPreferences.Editor.() -> Unit) {
    runBlocking(Dispatchers.IO) {
        check(this@writeDurably.edit().apply(edit).commit()) { "本地设置保存失败" }
    }
}
