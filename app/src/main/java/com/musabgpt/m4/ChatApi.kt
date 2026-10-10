package com.musabgpt.m4

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

data class ChatMessage(val role: String, val content: String)
sealed interface ChatEvent {
    data class Text(val value: String) : ChatEvent
    data class Route(val name: String) : ChatEvent
    data class ToolStatus(val text: String) : ChatEvent
}
data class ModelToolCall(val id: String, val name: String, val arguments: JSONObject)

private class GatewayFailure(val code: Int, message: String) : Exception(message)
private class WrongReplyLanguage : Exception("أجاب المزود بلغة غير لغة المستخدم")

/** The real Android tools exposed to models; commands are NEVER executed without approval. */
object ChatApi {
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var stopped=false
    @Volatile private var preferredRoute: String?=null
    private val cooldown=ConcurrentHashMap<String,Long>()
    private const val MAX_TOOL_ROUNDS=12
    private const val MAX_TOOL_CALLS=30

    fun stop() { stopped=true; connection?.disconnect() }

    private val functions=JSONArray().apply {
        put(tool("open_app","Open an installed Android application using the Arabic Terminal's Android launcher. Pass the visible application name.",
            JSONObject().put("type","object").put("properties",JSONObject()
                .put("name",JSONObject().put("type","string"))).put("required",JSONArray().put("name"))))
        put(tool("list_apps","List installed Android apps that have a launcher icon.",
            JSONObject().put("type","object").put("properties",JSONObject())))
        put(tool("terminal_command","Run a real command in M4's embedded Alpine PTY, after a visible user confirmation. Never use to read credentials.",
            JSONObject().put("type","object").put("properties",JSONObject()
                .put("command",JSONObject().put("type","string"))
                .put("timeout_seconds",JSONObject().put("type","integer")
                    .put("minimum",5).put("maximum",180)))
                .put("required",JSONArray().put("command"))))
        put(tool("github_status","Read GitHub CLI authentication status using the native Alpine terminal.",
            JSONObject().put("type","object").put("properties",JSONObject())))
        put(tool("github_connect","Open M4's integrated terminal to connect GitHub with official gh device login.",
            JSONObject().put("type","object").put("properties",JSONObject())))
    }

    private fun tool(name: String, description: String, parameters: JSONObject) =
        JSONObject().put("type","function").put("function",JSONObject()
            .put("name",name).put("description",description).put("parameters",parameters))

    private data class MutableTool(
        val id: StringBuilder=StringBuilder(),
        val name: StringBuilder=StringBuilder(),
        val args: StringBuilder=StringBuilder()
    )

    private data class Turn(val textSent: Boolean, val calls: List<ModelToolCall>,
                            val toolJson: JSONArray, val bodyText: String)

    private fun shouldRejectSpanish(text: String, expectsArabic: Boolean): Boolean {
        if (!expectsArabic) return false
        val first=text.trimStart().lowercase()
        // Never interpret English-language code fences or proper names as a Spanish response.
        return listOf("claro,","claro ","aquí tienes","aqui tienes","por supuesto",
            "puedo ayudarte","te dejo un ejemplo","hola,","para abrir").any { first.startsWith(it) }
    }

    private fun routes(selected: Provider): List<GatewayRoute> {
        val now=System.currentTimeMillis()
        val listed=ProviderCatalog.routingOrder(selected)
        val candidates=listed.filter { (cooldown[it.baseUrl + "|" + it.model] ?: 0L)<now }
        val available=if(candidates.isNotEmpty()) candidates else listed
        return available.sortedByDescending { if(it.baseUrl==preferredRoute) 1 else 0 }
    }

    /**
     * Model returns actual tool calls; a result is sent back as a tool-role
     * message in the same bounded conversation. No fake execution claims.
     */
    fun stream(
        selected: Provider,
        history: List<ChatMessage>,
        systemPrompt: String,
        execute: suspend (ModelToolCall) -> String
    ): Flow<ChatEvent> = flow {
        stopped=false
        val conversation=JSONArray().put(JSONObject()
            .put("role","system").put("content",systemPrompt.take(11000)))
        history.takeLast(18).forEach { m ->
            if(m.role=="user" || m.role=="assistant") conversation.put(JSONObject()
                .put("role",m.role).put("content",m.content.take(10000)))
        }
        val original=history.lastOrNull { it.role=="user" }?.content.orEmpty()
        val arabic=original.any { it in '\u0600'..'\u06FF' }
        var toolCallsExecuted=0
        var lastObservation=""
        var identicalObservations=0
        for (round in 0..MAX_TOOL_ROUNDS) {
            var response: Turn?=null
            var problem: Exception?=null
            for (route in routes(selected)) {
                if (stopped || !currentCoroutineContext().isActive)
                    throw CancellationException("تم إيقاف المحادثة")
                var visible=false
                try {
                    val turn=request(route,conversation,arabic,true) { event ->
                        if(event is ChatEvent.Text) visible=true
                        emit(event)
                    }
                    preferredRoute=route.baseUrl
                    cooldown.remove(route.baseUrl+"|"+route.model)
                    response=turn
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if(visible) throw e // Never splice multiple models into one visible response.
                    if(stopped) throw CancellationException("تم إيقاف الرد")
                    problem=e
                    cooldown[route.baseUrl+"|"+route.model]=System.currentTimeMillis()+70000L
                }
            }
            val result=response ?: throw IllegalStateException(
                "المزودات المجانية لم تستجب: " + (problem?.message ?: "تحقق من الإنترنت"))
            if(result.calls.isEmpty()) {
                if (!result.textSent) throw IllegalStateException("المزود أعاد إجابة فارغة")
                return@flow
            }
            if(round==MAX_TOOL_ROUNDS ||
                toolCallsExecuted + result.calls.size > MAX_TOOL_CALLS) {
                emit(ChatEvent.Text("\nتوقفت المهمة عند حدّ الأدوات الآمن. بعض الخطوات غير مكتملة؛ أعد المحاولة بعد مراجعة النتائج."))
                return@flow
            }
            conversation.put(JSONObject().put("role","assistant")
                .put("content",result.bodyText).put("tool_calls",result.toolJson))
            for(call in result.calls) {
                if(stopped) throw CancellationException("تم إيقاف الأدوات")
                emit(ChatEvent.ToolStatus("تشغيل " + call.name + " باستخدام أدوات M4…"))
                val resultText=try { execute(call) } catch(e: CancellationException) {
                    throw e
                } catch(e: Exception) {
                    "فشل تنفيذ الأداة: " + (e.message ?: "غير معروف")
                }
                toolCallsExecuted++
                conversation.put(JSONObject().put("role","tool")
                    .put("tool_call_id",call.id).put("content",resultText.take(7000)))
                emit(ChatEvent.ToolStatus(call.name + ": " + resultText.take(500)))
                val observation=call.name + ":" + call.arguments.toString() +
                    ":" + resultText.trim().take(1800)
                identicalObservations=if(observation==lastObservation) identicalObservations+1 else 0
                lastObservation=observation
                if(identicalObservations>=2) {
                    emit(ChatEvent.Text("\nتوقفت بعد تكرار الأداة والنتيجة دون تقدّم ثلاث مرات. لم أعتبر المهمة ناجحة."))
                    return@flow
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun request(
        route: GatewayRoute, conversation: JSONArray, expectsArabic: Boolean,
        withTools: Boolean, deliver: suspend (ChatEvent) -> Unit
    ): Turn {
        val base=URL(route.baseUrl)
        val loopback=base.host=="127.0.0.1" || base.host=="localhost"
        require((!route.localService && base.protocol=="https") ||
            (route.localService && loopback && base.protocol=="http")) {
            "تم رفض عنوان غير موثوق"
        }
        require(base.userInfo==null && base.query==null && base.ref==null)
        val body=JSONObject().put("model",route.model)
            .put("messages",conversation).put("stream",true)
            .put("max_tokens",1100)
        if(withTools) body.put("tools",functions).put("tool_choice","auto")
        val url=URL(route.baseUrl.trimEnd('/') + "/chat/completions")
        val c=url.openConnection() as HttpURLConnection
        connection=c
        try {
            c.instanceFollowRedirects=false
            c.requestMethod="POST"
            c.doOutput=true
            c.connectTimeout=if(route.localService) 1800 else 5500
            c.readTimeout=if(route.localService) 5000 else 13000
            c.setRequestProperty("Content-Type","application/json; charset=utf-8")
            c.setRequestProperty("Accept","text/event-stream, application/json")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status=c.responseCode
            if(status !in 200..299) {
                if(withTools && status in listOf(400,404,422)) {
                    // A text-only provider may not implement OpenAI function calling.
                    return request(route,conversation,expectsArabic,false,deliver)
                }
                throw GatewayFailure(status,route.name + " HTTP " + status)
            }
            val buffer=StringBuilder()
            val first=StringBuilder()
            val toolparts=linkedMapOf<Int,MutableTool>()
            var sent=false
            var finished=false
            var routeShown=false
            suspend fun showRoute() {
                if(!routeShown) { deliver(ChatEvent.Route(route.name));routeShown=true }
            }
            suspend fun appendText(value: String) {
                if(value.isEmpty())return
                buffer.append(value)
                if(toolparts.isNotEmpty())return
                if(!sent) {
                    first.append(value)
                    if(first.length<42 && !first.contains("\n")) return
                    if(shouldRejectSpanish(first.toString(),expectsArabic))throw WrongReplyLanguage()
                    showRoute()
                    deliver(ChatEvent.Text(first.toString()))
                    first.setLength(0)
                    sent=true
                } else {
                    deliver(ChatEvent.Text(value))
                }
            }
            val contentType=c.contentType.orEmpty()
            if(contentType.contains("application/json") && !contentType.contains("event-stream")) {
                val json=JSONObject(c.inputStream.bufferedReader().use { it.readText().take(250000) })
                val choice=json.optJSONArray("choices")?.optJSONObject(0)
                    ?: throw IllegalStateException("رد JSON غير صالح")
                val message=choice.optJSONObject("message")?:throw IllegalStateException("محتوى مفقود")
                val jsonCalls=message.optJSONArray("tool_calls")
                if(jsonCalls!=null)for(i in 0 until jsonCalls.length()){
                    val obj=jsonCalls.optJSONObject(i)?:continue
                    val fn=obj.optJSONObject("function")?:continue
                    val part=MutableTool()
                    part.id.append(obj.optString("id","m4-call-"+i))
                    part.name.append(fn.optString("name",""))
                    part.args.append(fn.optString("arguments","{}"))
                    toolparts[i]=part
                }
                appendText(message.optString("content",""))
                finished=true
            } else {
                c.inputStream.bufferedReader().use { reader ->
                    while(!stopped && currentCoroutineContext().isActive) {
                        val line=reader.readLine()?:break
                        if(!line.startsWith("data:"))continue
                        val data=line.substring(5).trim()
                        if(data=="[DONE]") { finished=true;break }
                        val delta=try { JSONObject(data).optJSONArray("choices")
                            ?.optJSONObject(0)?.optJSONObject("delta") } catch(_:Exception){null}
                            ?:continue
                        val chunks=delta.optJSONArray("tool_calls")
                        if(chunks!=null)for(i in 0 until chunks.length()){
                            val chunk=chunks.optJSONObject(i)?:continue
                            val index=chunk.optInt("index",i).coerceIn(0,8)
                            val part=toolparts.getOrPut(index){MutableTool()}
                            part.id.append(chunk.optString("id",""))
                            val function=chunk.optJSONObject("function")
                            if(function!=null){
                                part.name.append(function.optString("name",""))
                                part.args.append(function.optString("arguments",""))
                            }
                        }
                        val text=delta.optString("content","")
                        if(text.isNotEmpty())appendText(text)
                        if(buffer.length>120000)break
                        if(sent) c.readTimeout=30000
                    }
                }
            }
            if(stopped)throw CancellationException("تم الإيقاف")
            if(toolparts.isEmpty() && first.isNotEmpty()){
                if(shouldRejectSpanish(first.toString(),expectsArabic))throw WrongReplyLanguage()
                showRoute()
                deliver(ChatEvent.Text(first.toString()))
                sent=true
            }
            val calls=ArrayList<ModelToolCall>()
            val rawCalls=JSONArray()
            for((index,part) in toolparts) {
                val name=part.name.toString()
                if(name !in listOf("open_app","list_apps","github_status","github_connect","terminal_command"))continue
                val args=try { JSONObject(part.args.toString().ifEmpty { "{}" }) }
                    catch(_:Exception){JSONObject()}
                val id=part.id.toString().ifEmpty { "m4-tool-"+index }
                calls.add(ModelToolCall(id,name,args))
                rawCalls.put(JSONObject().put("id",id).put("type","function").put("function",
                    JSONObject().put("name",name).put("arguments",args.toString())))
            }
            if(!sent && calls.isEmpty())throw IllegalStateException(route.name + " أرجع استجابة فارغة")
            return Turn(sent,calls,rawCalls,buffer.toString().take(2000))
        } finally {
            c.disconnect()
            if(connection===c) connection=null
        }
    }
}
