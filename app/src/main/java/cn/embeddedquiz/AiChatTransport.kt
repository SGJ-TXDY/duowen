package cn.embeddedquiz

import android.util.JsonReader
import android.util.JsonToken
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject

internal data class AiChatResult(val text:String="",val complete:Boolean=false,val error:String?=null,
    val finish:String?=null,val responseId:String?=null,val usage:AiUsageEvidence=AiUsageEvidence())

internal object AiChatPrices {
    fun estimatePolicy(model:String):AiUsageEvidence = when(model) {
        DeepSeekClient.FLASH -> AiUsageEvidence(priceVersion="deepseek-20261107-peak-usd-v1",missRate="0.30",hitRate="0.006",outputRate="1.20")
        DeepSeekClient.PRO -> AiUsageEvidence(priceVersion="deepseek-20261107-peak-usd-v1",missRate="1.32",hitRate="0.044",outputRate="3.96")
        else -> AiUsageEvidence()
    }
}

/** No automatic retry/redirect and no reconstructed prompt. One call owns one callback/response. */
internal class AiChatTransport(endpoint:String="https://api.deepseek.com/chat/completions",
    private val beforeExit:()->Unit={}) {
    internal val url=endpoint
    private val http=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(120,TimeUnit.SECONDS)
        .callTimeout(150,TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    fun prepare(payload:String,key:String,reading:()->Unit,received:(AiChatResult)->Unit):Exchange {
        val frozen=payload.toRequestBody("application/json; charset=utf-8".toMediaType())
        // OkHttp's HTTP follow-up rules (for example 503/Retry-After: 0) are separate
        // from connection retries. A one-shot body disallows reissuing this accepted attempt.
        val oneShot=object:RequestBody() {
            override fun contentType()=frozen.contentType()
            override fun contentLength()=frozen.contentLength()
            override fun isOneShot()=true
            override fun writeTo(sink:BufferedSink)=frozen.writeTo(sink)
        }
        val request=Request.Builder().url(url).header("Authorization","Bearer $key").post(oneShot).build()
        return Exchange(http.newCall(request),key,reading,received,beforeExit)
    }
    internal class Exchange(private val call:Call,private val key:String,private val reading:()->Unit,private val received:(AiChatResult)->Unit,
        private val beforeExit:()->Unit) {
        private val lock=Any();private var started=false;private var stopped=false
        private val exited=CompletableDeferred<Unit>()
        fun cancel()=synchronized(lock) { stopped=true;call.cancel();if(!started) exited.complete(Unit);Unit }
        suspend fun awaitClosed() { exited.await() }
        suspend fun receive()=suspendCancellableCoroutine<Unit> { continuation ->
            continuation.invokeOnCancellation { cancel() }
            fun deliver(result:AiChatResult) {
                try { received(result);if(continuation.isActive) continuation.resumeWith(Result.success(Unit)) }
                finally { try { beforeExit() } finally { exited.complete(Unit) } }
            }
            synchronized(lock) {
                if(stopped) continuation.cancel()
                else {
                    started=true
                    try { call.enqueue(object:Callback {
                        override fun onFailure(call:Call,e:IOException)=deliver(AiChatResult(error="NETWORK_FAILED"))
                        override fun onResponse(call:Call,response:Response) {
                            val result=try { response.use { reading();read(it,key) } }
                            catch(_:OutputLimit) { AiChatResult(error="OUTPUT_LIMIT") }
                            catch(_:IOException) { AiChatResult(error="NETWORK_FAILED") }
                            catch(_:Exception) { AiChatResult(error="INVALID_RESPONSE") }
                            deliver(result)
                        }
                    }) } catch(e:Exception) { exited.complete(Unit);continuation.resumeWith(Result.failure(e)) }
                }
            }
        }
    }
    private class OutputLimit:IOException()
    companion object {
        private const val BODY_LIMIT=6*1024*1024+256*1024
        private val finishes=setOf("stop","length","content_filter","tool_calls","insufficient_system_resource","aborted")
        private fun read(response:Response,key:String):AiChatResult {
            if(!response.isSuccessful) return AiChatResult(error="HTTP_${response.code}")
            val body=requireNotNull(response.body)
            if(body.contentLength()>BODY_LIMIT) throw OutputLimit()
            val buffer=ByteArray(8192);val bytes=ByteArrayOutputStream()
            body.byteStream().use { stream ->
                while(true) { val n=stream.read(buffer);if(n<0) break
                    if(bytes.size()+n>BODY_LIMIT) throw OutputLimit();bytes.write(buffer,0,n) }
            }
            val text=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
            return decode(text,key)
        }
        internal fun decode(body:String,key:String=""):AiChatResult {
            val root=JsonReader(StringReader(body)).use { reader ->
                reader.isLenient=false;val value=json(reader,0) as JSONObject
                require(reader.peek()==JsonToken.END_DOCUMENT);value
            }
            val usage=usage(root)
            val id=(root.opt("id") as? String)?.takeIf { it.matches(Regex("[A-Za-z0-9_.:-]{1,128}")) && (key.isEmpty() || !it.contains(key)) }
            val choices=root.optJSONArray("choices")
            val choice=choices?.takeIf { it.length()==1 }?.optJSONObject(0)
            val message=choice?.optJSONObject("message")
            if(choice==null || message==null || message.opt("role")!="assistant" || choice.opt("index").let { it!=0 && it!=0L })
                return AiChatResult(error="INVALID_RESPONSE",responseId=id,usage=usage)
            val finish=(choice.opt("finish_reason") as? String)?.takeIf { it in finishes }
            val raw=message.opt("content") as? String ?: ""
            val safe=if(key.isEmpty()) raw else raw.replace(key,"[已隐藏凭据]")
            val bounded=bound(safe)
            val code=when {
                bounded.length!=safe.length -> "OUTPUT_LIMIT"
                finish=="stop" && bounded.isNotBlank() -> null
                finish=="stop" -> "EMPTY_RESPONSE"
                finish=="length" -> "OUTPUT_TRUNCATED"
                finish=="content_filter" -> "CONTENT_FILTERED"
                finish=="insufficient_system_resource" -> "PROVIDER_BUSY"
                finish=="aborted" -> "PROVIDER_ABORTED"
                else -> "INVALID_RESPONSE"
            }
            return AiChatResult(bounded,code==null,code,finish,id,usage)
        }
        private fun bound(value:String):String {
            var i=0;var size=0L
            while(i<value.length) {
                val cp=value.codePointAt(i);require(cp !in 0xd800..0xdfff)
                val n=when { cp<=0x7f -> 1;cp<=0x7ff -> 2;cp<=0xffff -> 3;else -> 4 }
                if(size+n>AiConversationCodec.TEXT_BYTES) break
                size+=n;i+=Character.charCount(cp)
            }
            return value.substring(0,i)
        }
        private fun usage(root:JSONObject):AiUsageEvidence {
            if(root.isNull("usage")) return AiUsageEvidence()
            return try {
                val u=root.getJSONObject("usage")
                fun number(o:JSONObject,k:String)=if(o.isNull(k)) null else AiJson.long(o,k).also { require(it>=0) }
                val i=number(u,"prompt_tokens");val o=number(u,"completion_tokens")
                val explicit=number(u,"prompt_cache_hit_tokens")
                val details=if(u.isNull("prompt_tokens_details")) null else number(u.getJSONObject("prompt_tokens_details"),"cached_tokens")
                require(explicit==null || details==null || explicit==details)
                val h=explicit ?: details
                require(h==null || i!=null && h<=i)
                number(u,"prompt_cache_miss_tokens")?.let { if(h!=null && i!=null) require(Math.addExact(h,it)==i) }
                number(u,"total_tokens")?.let { if(i!=null && o!=null) require(Math.addExact(i,o)==it) }
                val state=when(listOf(i,o,h).count { it!=null }) { 0 -> UsageState.UNKNOWN;3 -> UsageState.KNOWN;else -> UsageState.PARTIAL }
                AiUsageEvidence(i,o,h,state)
            } catch(_:Exception) { AiUsageEvidence(state=UsageState.INVALID) }
        }
        private fun json(reader:JsonReader,depth:Int):Any {
            require(depth<=32)
            return when(reader.peek()) {
                JsonToken.BEGIN_OBJECT -> JSONObject().apply { reader.beginObject();while(reader.hasNext()) {
                    val name=reader.nextName();require(!has(name));put(name,json(reader,depth+1)) };reader.endObject() }
                JsonToken.BEGIN_ARRAY -> JSONArray().apply { reader.beginArray();while(reader.hasNext()) put(json(reader,depth+1));reader.endArray() }
                JsonToken.NUMBER -> reader.nextString().let { it.toLongOrNull() ?: it.toDouble().also { n-> require(n.isFinite()) } }
                JsonToken.STRING -> reader.nextString()
                JsonToken.BOOLEAN -> reader.nextBoolean()
                JsonToken.NULL -> { reader.nextNull();JSONObject.NULL }
                else -> error("INVALID_RESPONSE")
            }
        }
    }
}
