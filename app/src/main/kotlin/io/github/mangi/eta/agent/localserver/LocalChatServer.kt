package io.github.mangi.eta.agent.localserver

import android.content.Context
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.datastore.SettingsDataStore
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

internal object LocalChatServer {
    private const val MAX_PORT_ATTEMPTS = 12
    private const val LIST_PAGE_LIMIT = 200
    private const val DEFAULT_SHARE_TTL_MS = 24 * 60 * 60 * 1000L
    private const val MAX_SHARE_TTL_MS = 7 * 24 * 60 * 60 * 1000L

    private val serverSocketRef = AtomicReference<ServerSocket?>()
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable).apply {
            isDaemon = true
            name = "EtaLocalChatServer"
        }
    }

    private class ShareEntry(
        val conversationIds: List<String>,
        val createdAt: Long,
        val expiresAt: Long,
    )

    private val shares = ConcurrentHashMap<String, ShareEntry>()

    @Volatile
    var boundEndpoint: String = ""
        private set

    val isRunning: Boolean
        get() = serverSocketRef.get() != null

    val currentPort: Int
        get() = serverSocketRef.get()?.localPort ?: -1

    fun start(context: Context): Boolean {
        if (serverSocketRef.get() != null) return true
        return synchronized(this) {
            if (serverSocketRef.get() != null) return@synchronized true
            runCatching {
                val preferredPort = runBlocking(Dispatchers.IO) {
                    withTimeoutOrNull(800) { SettingsDataStore.localChatServerPort() }
                } ?: SettingsDataStore.DEFAULT_LOCAL_CHAT_SERVER_PORT
                var opened: ServerSocket? = null
                var port = preferredPort
                repeat(MAX_PORT_ATTEMPTS) {
                    if (opened != null) return@repeat
                    opened = runCatching {
                        ServerSocket(port, 64, InetAddress.getByName("0.0.0.0"))
                    }.getOrNull()
                    if (opened == null) port += 1
                }
                val socket = opened ?: return@runCatching false
                serverSocketRef.set(socket)
                boundEndpoint = "${localIpAddress()}:${socket.localPort}"
                val appContext = context.applicationContext
                Thread({ acceptLoop(appContext, socket) }, "EtaLocalChatServerAccept").apply {
                    isDaemon = true
                }.start()
                true
            }.getOrDefault(false)
        }
    }

    fun stop() {
        val socket = serverSocketRef.getAndSet(null) ?: return
        runCatching { socket.close() }
        boundEndpoint = ""
    }

    fun createShareToken(conversationIds: List<String>, ttlMs: Long = DEFAULT_SHARE_TTL_MS): String? {
        val ids = conversationIds.map(String::trim).filter(String::isNotEmpty).distinct()
        if (ids.isEmpty()) return null
        val token = UUID.randomUUID().toString().replace("-", "").take(12)
        val now = System.currentTimeMillis()
        shares[token] = ShareEntry(
            conversationIds = ids,
            createdAt = now,
            expiresAt = now + ttlMs.coerceIn(60_000L, MAX_SHARE_TTL_MS),
        )
        return token
    }

    fun shareUrl(token: String): String =
        "http://$boundEndpoint/s/$token"

    fun activeShareCount(): Int = shares.count { it.value.expiresAt > System.currentTimeMillis() }

    private fun acceptLoop(context: Context, socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            executor.execute { handleClient(context, client) }
        }
    }

    private fun handleClient(context: Context, client: Socket) {
        client.use { socket ->
            runCatching {
                socket.soTimeout = 10_000
                val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) {
                    writeResponse(socket, 400, "text/plain; charset=utf-8", "Bad Request", headOnly = false)
                    return
                }
                val method = parts[0].uppercase()
                val target = parts[1]
                var contentLength = 0
                while (true) {
                    val headerLine = reader.readLine() ?: break
                    if (headerLine.isBlank()) break
                    val separator = headerLine.indexOf(':')
                    if (separator > 0 && headerLine.take(separator).trim().equals("content-length", ignoreCase = true)) {
                        contentLength = headerLine.substring(separator + 1).trim().toIntOrNull() ?: 0
                    }
                }
                val body = if (contentLength > 0 && contentLength <= 65536) {
                    val buffer = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val count = reader.read(buffer, read, contentLength - read)
                        if (count < 0) break
                        read += count
                    }
                    String(buffer, 0, read)
                } else {
                    ""
                }
                val headOnly = method == "HEAD"
                when {
                    method != "GET" && method != "HEAD" && method != "POST" ->
                        writeResponse(socket, 405, "text/plain; charset=utf-8", "Method Not Allowed", headOnly)
                    else -> route(context, socket, method, target, body, headOnly)
                }
            }
        }
    }

    private fun route(
        context: Context,
        socket: Socket,
        method: String,
        target: String,
        body: String,
        headOnly: Boolean,
    ) {
        val path = target.substringBefore('?')
        when {
            path == "/" || path == "/index.html" ->
                writeResponse(socket, 200, "text/html; charset=utf-8", indexPage(), headOnly)
            path == "/health" -> writeResponse(
                socket,
                200,
                "application/json; charset=utf-8",
                JSONObject().put("ok", true).put("service", "eta-local-chat").toString(),
                headOnly,
            )
            path == "/api/server-info" -> writeResponse(
                socket,
                200,
                "application/json; charset=utf-8",
                serverInfoJson().toString(),
                headOnly,
            )
            path == "/api/server-config" && method == "POST" -> {
                val responseBody = applyServerConfig(context, body)
                if (responseBody.optBoolean("restarted", false)) {
                    scheduleRestart(context)
                }
                writeResponse(socket, 200, "application/json; charset=utf-8", responseBody.toString(), headOnly)
            }
            path == "/api/conversations" -> writeResponse(
                socket,
                200,
                "application/json; charset=utf-8",
                conversationsJson(context),
                headOnly,
            )
            path.startsWith("/api/conversations/") -> {
                val conversationId = urlDecode(path.removePrefix("/api/conversations/"))
                val body2 = conversationDetailJson(context, conversationId)
                if (body2 == null) {
                    writeResponse(socket, 404, "application/json; charset=utf-8", "{\"error\":\"not_found\"}", headOnly)
                } else {
                    writeResponse(socket, 200, "application/json; charset=utf-8", body2, headOnly)
                }
            }
            path.startsWith("/api/share/") -> {
                val token = urlDecode(path.removePrefix("/api/share/"))
                val shareBody = shareJson(context, token)
                if (shareBody == null) {
                    writeResponse(socket, 404, "application/json; charset=utf-8", "{\"error\":\"not_found\"}", headOnly)
                } else {
                    writeResponse(socket, 200, "application/json; charset=utf-8", shareBody, headOnly)
                }
            }
            path.startsWith("/s/") -> {
                val token = urlDecode(path.removePrefix("/s/"))
                val entry = shares[token]
                if (entry != null && entry.expiresAt > System.currentTimeMillis()) {
                    writeResponse(socket, 200, "text/html; charset=utf-8", sharePage(token), headOnly)
                } else {
                    writeResponse(socket, 404, "text/html; charset=utf-8", shareExpiredPage(), headOnly)
                }
            }
            else -> writeResponse(socket, 404, "text/plain; charset=utf-8", "Not Found", headOnly)
        }
    }

    private fun serverInfoJson(): JSONObject = JSONObject()
        .put("ok", true)
        .put("address", boundEndpoint)
        .put("port", if (currentPort > 0) currentPort else JSONObject.NULL)
        .put("running", isRunning)
        .put("activeShares", activeShareCount())

    private fun applyServerConfig(context: Context, body: String): JSONObject {
        val result = JSONObject()
        val port = runCatching {
            JSONObject(body).optInt("port", -1)
        }.getOrDefault(-1)
        if (port !in 1024..65535) {
            return result.put("ok", false).put("message", "端口无效，需在 1024-65535 之间")
        }
        runCatching {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(800) { SettingsDataStore.setLocalChatServerPort(port) }
            }
        }
        val changed = port != currentPort
        return result
            .put("ok", true)
            .put("port", port)
            .put("restarted", changed)
            .put(
                "message",
                if (changed) "端口已保存为 $port，服务器正在重启" else "端口未变化，仍为 $port",
            )
    }

    private fun scheduleRestart(context: Context) {
        Thread({
            runCatching {
                Thread.sleep(400)
                LocalChatServerService.restart(context.applicationContext)
            }
        }, "EtaLocalChatServerRestart").apply { isDaemon = true }.start()
    }

    private fun conversationsJson(context: Context): String {
        val dao = EtaDatabase.get(context).conversationDao()
        val rows = runBlocking {
            runCatching { dao.conversationsPage(LIST_PAGE_LIMIT, 0) }.getOrDefault(emptyList())
        }
        val array = JSONArray()
        rows.forEach { row ->
            val messageCount = runBlocking {
                runCatching { dao.messageCount(row.id) }.getOrDefault(0)
            }
            array.put(
                JSONObject()
                    .put("id", row.id)
                    .put("title", row.title)
                    .put("createdAt", row.createdAt)
                    .put("updatedAt", row.updatedAt)
                    .put("messageCount", messageCount),
            )
        }
        return array.toString()
    }

    private fun conversationDetailJson(context: Context, conversationId: String): String? {
        if (conversationId.isBlank()) return null
        val dao = EtaDatabase.get(context).conversationDao()
        val conversation = runBlocking {
            runCatching { dao.conversationEntityRow(conversationId) }.getOrNull()
        } ?: return null
        val messages = runBlocking {
            runCatching { dao.messagesFor(conversationId) }.getOrDefault(emptyList())
        }
        return conversationJson(
            conversation.id,
            conversation.title,
            conversation.createdAt,
            conversation.updatedAt,
            messages,
        ).toString()
    }

    private fun conversationJson(
        id: String,
        title: String,
        createdAt: Long,
        updatedAt: Long,
        messages: List<io.github.mangi.eta.data.db.ConversationMessageEntity>,
    ): JSONObject {
        val messageArray = JSONArray()
        messages.forEach { message ->
            messageArray.put(
                JSONObject()
                    .put("id", message.id)
                    .put("type", message.type)
                    .put("content", message.content)
                    .put("toolName", message.toolName ?: JSONObject.NULL)
                    .put("toolStatus", message.toolStatus ?: JSONObject.NULL)
                    .put("sortIndex", message.sortIndex),
            )
        }
        return JSONObject()
            .put(
                "conversation",
                JSONObject()
                    .put("id", id)
                    .put("title", title)
                    .put("createdAt", createdAt)
                    .put("updatedAt", updatedAt),
            )
            .put("messages", messageArray)
    }

    private fun shareJson(context: Context, token: String): String? {
        val entry = shares[token] ?: return null
        if (entry.expiresAt <= System.currentTimeMillis()) {
            shares.remove(token)
            return null
        }
        val dao = EtaDatabase.get(context).conversationDao()
        val conversations = JSONArray()
        entry.conversationIds.forEach { conversationId ->
            val conversation = runBlocking {
                runCatching { dao.conversationEntityRow(conversationId) }.getOrNull()
            } ?: return@forEach
            val messages = runBlocking {
                runCatching { dao.messagesFor(conversationId) }.getOrDefault(emptyList())
            }
            val detail = conversationJson(
                conversation.id,
                conversation.title,
                conversation.createdAt,
                conversation.updatedAt,
                messages,
            )
            conversations.put(
                JSONObject()
                    .put("id", conversation.id)
                    .put("title", conversation.title)
                    .put("updatedAt", conversation.updatedAt)
                    .put("messages", detail.optJSONArray("messages") ?: JSONArray()),
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("expiresAt", entry.expiresAt)
            .put("conversations", conversations)
            .toString()
    }

    private fun urlDecode(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrDefault(value)

    private fun writeResponse(socket: Socket, status: Int, contentType: String, body: String, headOnly: Boolean) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "Error"
        }
        val headers = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        val output = socket.getOutputStream()
        output.write(headers.toByteArray(Charsets.UTF_8))
        if (!headOnly) {
            output.write(bytes)
        }
        output.flush()
    }

    private fun localIpAddress(): String {
        val candidates = runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            val siteLocal = mutableListOf<String>()
            val others = mutableListOf<String>()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val host = address.hostAddress ?: continue
                        if (address.isSiteLocalAddress) siteLocal += host else others += host
                    }
                }
            }
            siteLocal.firstOrNull() ?: others.firstOrNull()
        }.getOrNull()
        return candidates ?: "127.0.0.1"
    }

    private fun indexPage(): String = """<!doctype html>
<html lang="zh">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>Eta 聊天记录</title>
<style>
:root{color-scheme:light dark}
*{box-sizing:border-box}
body{margin:0;font-family:system-ui,-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;background:#f2f3f7;color:#1c1c1e}
header{position:sticky;top:0;z-index:9;padding:14px 18px;font-weight:700;font-size:17px;background:rgba(245,246,250,.88);backdrop-filter:blur(14px);border-bottom:1px solid rgba(0,0,0,.05)}
header small{display:block;font-weight:400;font-size:12px;opacity:.55;margin-top:2px}
#root{max-width:760px;margin:0 auto;padding:14px 14px 40px}
.panel{background:#fff;border-radius:16px;padding:14px 16px;margin-bottom:10px;box-shadow:0 1px 4px rgba(0,0,0,.05)}
.panel .row{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-top:8px}
.panel .addr{font-size:13px;font-family:ui-monospace,monospace;background:rgba(10,132,255,.08);color:#0a84ff;padding:6px 10px;border-radius:8px;word-break:break-all}
.panel input{flex:1;min-width:120px;padding:8px 10px;border:1px solid rgba(0,0,0,.12);border-radius:8px;font-size:14px}
.panel button{padding:8px 14px;border:none;border-radius:8px;background:#0a84ff;color:#fff;font-size:14px;cursor:pointer}
.panel button:active{opacity:.7}
.panel .tip{font-size:12px;opacity:.55;margin-top:6px}
.item{background:#fff;border-radius:16px;padding:14px 16px;margin-bottom:10px;cursor:pointer;box-shadow:0 1px 4px rgba(0,0,0,.05)}
.item:active{transform:scale(.985)}
.item .t{font-size:15px;font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.item .s{font-size:12px;opacity:.5;margin-top:4px}
.back{display:inline-block;padding:8px 14px;border-radius:12px;background:#fff;margin-bottom:10px;cursor:pointer;font-size:14px;box-shadow:0 1px 4px rgba(0,0,0,.05)}
.title{font-size:16px;font-weight:700;margin:6px 2px 12px}
.msg{padding:10px 14px;border-radius:16px;margin:8px 0;font-size:14px;line-height:1.6;white-space:pre-wrap;word-break:break-word;max-width:86%}
.user{background:#0a84ff;color:#fff;margin-left:auto}
.assistant{background:#fff;box-shadow:0 1px 3px rgba(0,0,0,.05)}
.tool{background:#e8e8ed;color:#3a3a3c;font-size:12px;max-width:95%}
.system_notice{background:#fff3d6;color:#7a5b00;font-size:12px;max-width:95%}
.empty{text-align:center;opacity:.4;padding:40px 0;font-size:14px}
@media(prefers-color-scheme:dark){
body{background:#000;color:#f2f2f7}
header{background:rgba(20,20,22,.88);border-bottom-color:rgba(255,255,255,.08)}
.item,.back,.panel,.assistant{background:#1c1c1e;box-shadow:none}
.panel input{background:#2c2c2e;border-color:rgba(255,255,255,.14);color:#f2f2f7}
.tool{background:#2c2c2e;color:#c7c7cc}
.system_notice{background:#3a2f0f;color:#ffd60a}
}
</style>
</head>
<body>
<header>Eta 聊天记录<small id="meta">本机服务器</small></header>
<div id="root">
<div class="panel" id="server-panel">
<div style="font-weight:600;font-size:14px">服务器信息</div>
<div class="row"><span class="addr" id="addr">加载中…</span></div>
<div class="row">
<input id="port" type="number" inputmode="numeric" min="1024" max="65535" placeholder="修改端口（1024-65535）">
<button onclick="savePort()">保存并重启</button>
</div>
<div class="tip" id="tip">局域网内任意设备可通过上方地址访问；修改端口后地址会变化，请注意更新。</div>
</div>
</div>
<script>
var root=document.getElementById('root');
function esc(s){return String(s==null?'':s).replace(/[&<>"]/g,function(c){return{'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]})}
function fmt(ts){try{return new Date(ts).toLocaleString()}catch(e){return ''}}
fetch('/api/server-info').then(function(r){return r.json()}).then(function(info){
document.getElementById('addr').textContent='http://'+info.address;
document.getElementById('meta').textContent='已运行 · 端口 '+info.port;
}).catch(function(){});
function savePort(){
var port=parseInt(document.getElementById('port').value,10);
if(!port||port<1024||port>65535){document.getElementById('tip').textContent='请输入 1024-65535 之间的端口';return}
fetch('/api/server-config',{method:'POST',headers:{'Content-Type':'text/plain;charset=UTF-8'},body:JSON.stringify({port:port})})
.then(function(r){return r.json()}).then(function(res){
document.getElementById('tip').textContent=res.message||'已保存';
setTimeout(function(){location.reload()},1500);
}).catch(function(){document.getElementById('tip').textContent='保存失败，请重试'});
}
function loadList(){
var listRoot=document.createElement('div');root.appendChild(listRoot);
listRoot.innerHTML='<div class="empty">加载中…</div>';
fetch('/api/conversations').then(function(r){return r.json()}).then(function(list){
if(!list.length){listRoot.innerHTML='<div class="empty">暂无聊天记录</div>';return}
listRoot.innerHTML='';
list.forEach(function(c){
var e=document.createElement('div');e.className='item';
e.innerHTML='<div class="t">'+esc(c.title)+'</div><div class="s">'+esc(fmt(c.updatedAt))+' · '+c.messageCount+' 条消息</div>';
e.onclick=function(){openConv(c.id,c.title)};
listRoot.appendChild(e);
});
}).catch(function(){listRoot.innerHTML='<div class="empty">加载失败</div>'});
}
function openConv(id,title){
root.innerHTML='';
renderPanel();
var listRoot=document.createElement('div');root.appendChild(listRoot);
listRoot.innerHTML='<div class="empty">加载中…</div>';
fetch('/api/conversations/'+encodeURIComponent(id)).then(function(r){return r.json()}).then(function(data){
listRoot.innerHTML='';
var back=document.createElement('div');back.className='back';back.textContent='← 返回列表';back.onclick=function(){location.reload()};listRoot.appendChild(back);
var h=document.createElement('div');h.className='title';h.textContent=title||'对话';listRoot.appendChild(h);
var msgs=data.messages||[];
if(!msgs.length){listRoot.innerHTML+='<div class="empty">该对话暂无消息</div>';return}
msgs.forEach(function(m){
if(m.type==='thinking')return;
if((m.type==='tool'||m.type==='tool_summary')&&!m.content)return;
var e=document.createElement('div');
e.className='msg '+m.type;
e.textContent=(m.toolName&&m.type==='tool'?('['+m.toolName+'] '):'')+m.content;
listRoot.appendChild(e);
});
}).catch(function(){listRoot.innerHTML='<div class="empty">加载失败</div>'});
}
function renderPanel(){
var p=document.createElement('div');p.className='panel';
p.innerHTML='<div style="font-weight:600;font-size:14px">服务器信息</div><div class="row"><span class="addr" id="addr">…</span></div>';
root.appendChild(p);
fetch('/api/server-info').then(function(r){return r.json()}).then(function(info){
document.getElementById('addr').textContent='http://'+info.address;
});
}
loadList();
</script>
</body>
</html>"""

    private fun sharePage(token: String): String = """<!doctype html>
<html lang="zh">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>Eta 聊天记录分享</title>
<style>
:root{color-scheme:light dark}
*{box-sizing:border-box}
body{margin:0;font-family:system-ui,-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;background:#f2f3f7;color:#1c1c1e}
header{position:sticky;top:0;z-index:9;padding:14px 18px;font-weight:700;font-size:17px;background:rgba(245,246,250,.88);backdrop-filter:blur(14px);border-bottom:1px solid rgba(0,0,0,.05)}
header small{display:block;font-weight:400;font-size:12px;opacity:.55;margin-top:2px}
#root{max-width:760px;margin:0 auto;padding:14px 14px 40px}
.title{font-size:16px;font-weight:700;margin:10px 2px 4px}
.time{font-size:11px;opacity:.4;margin:0 2px 8px}
.msg{padding:10px 14px;border-radius:16px;margin:8px 0;font-size:14px;line-height:1.6;white-space:pre-wrap;word-break:break-word;max-width:86%}
.user{background:#0a84ff;color:#fff;margin-left:auto}
.assistant{background:#fff;box-shadow:0 1px 3px rgba(0,0,0,.05)}
.tool{background:#e8e8ed;color:#3a3a3c;font-size:12px;max-width:95%}
.system_notice{background:#fff3d6;color:#7a5b00;font-size:12px;max-width:95%}
.empty{text-align:center;opacity:.4;padding:40px 0;font-size:14px}
@media(prefers-color-scheme:dark){
body{background:#000;color:#f2f2f7}
header{background:rgba(20,20,22,.88);border-bottom-color:rgba(255,255,255,.08)}
.assistant{background:#1c1c1e;box-shadow:none}
.tool{background:#2c2c2e;color:#c7c7cc}
.system_notice{background:#3a2f0f;color:#ffd60a}
}
</style>
</head>
<body>
<header>Eta 聊天记录分享<small id="meta">临时链接</small></header>
<div id="root"><div class="empty">加载中…</div></div>
<script>
var root=document.getElementById('root');
function esc(s){return String(s==null?'':s).replace(/[&<>"]/g,function(c){return{'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]})}
function fmt(ts){try{return new Date(ts).toLocaleString()}catch(e){return ''}}
fetch('/api/share/__TOKEN__').then(function(r){if(r.status===404)throw new Error('expired');return r.json()}).then(function(data){
root.innerHTML='';
document.getElementById('meta').textContent='有效期至 '+fmt(data.expiresAt);
(data.conversations||[]).forEach(function(conv){
var h=document.createElement('div');h.className='title';h.textContent=conv.title||'对话';root.appendChild(h);
var t=document.createElement('div');t.className='time';t.textContent='更新于 '+fmt(conv.updatedAt);root.appendChild(t);
var msgs=conv.messages||[];
if(!msgs.length){var e0=document.createElement('div');e0.className='empty';e0.textContent='该对话暂无消息';root.appendChild(e0);return}
msgs.forEach(function(m){
if(m.type==='thinking')return;
if((m.type==='tool'||m.type==='tool_summary')&&!m.content)return;
var e=document.createElement('div');
e.className='msg '+m.type;
e.textContent=(m.toolName&&m.type==='tool'?('['+m.toolName+'] '):'')+m.content;
root.appendChild(e);
});
});
}).catch(function(e){
root.innerHTML='<div class="empty">'+(e.message==='expired'?'链接已失效或已过期':'加载失败')+'</div>';
});
</script>
</body>
</html>"""

    private fun shareExpiredPage(): String = """<!doctype html>
<html lang="zh">
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>链接已失效</title></head>
<body style="font-family:system-ui,sans-serif;display:flex;align-items:center;justify-content:center;min-height:80vh;color:#666">
<div style="text-align:center"><div style="font-size:40px">⏱</div><div style="margin-top:8px">分享链接已失效或已过期</div></div>
</body>
</html>"""
}
