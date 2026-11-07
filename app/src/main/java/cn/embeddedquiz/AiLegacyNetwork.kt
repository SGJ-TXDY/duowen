package cn.embeddedquiz

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient

/** Keep the original cancellable DeepSeek client. A per-call transport lease closes only when
 * OkHttp has released the response/connection, not when its continuation first cancels. The
 * callback may finish parsing immutable text afterwards; it owns no database or UI authority. */
internal class AiLegacyNetwork {
    private val base=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS)
        .readTimeout(120,TimeUnit.SECONDS).callTimeout(150,TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    suspend fun complete(factory:(OkHttpClient)->DeepSeekClient,key:String,model:String,prompt:String):AiReply {
        val started=AtomicBoolean(false);val exited=CompletableDeferred<Unit>()
        val http=base.newBuilder().eventListener(object:EventListener() {
            override fun callStart(call:Call) { started.set(true) }
            override fun callEnd(call:Call) { exited.complete(Unit) }
            override fun callFailed(call:Call,ioe:IOException) { exited.complete(Unit) }
        }).build()
        try { return factory(http).completeCancellable(key,model,prompt) }
        finally { if(started.get()) withContext(NonCancellable) { exited.await() } }
    }
}
