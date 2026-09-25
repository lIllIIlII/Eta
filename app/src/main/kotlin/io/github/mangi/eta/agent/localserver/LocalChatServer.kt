package io.github.mangi.eta.agent.localserver

import android.content.Context
import io.github.mangi.eta.data.db.EtaDatabase
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

internal object LocalChatServer {
    private const val DEFAULT_PORT = 8765
    private const val MAX_PORT_ATTEMPTS = 12
    private const val LIST_PAGE_LIMIT = 200

    private val serverSocketRef = AtomicReference<ServerSocket?>()
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable).apply {
            isDaemon = true
            name = "EtaLocalChatServer"
        }
    }

    @Volatile
    var boundEndpoint: String = ""
        private set

    val isRunning: Boolean
        get() = serverSocketRef.get() != null

    fun start(context: Context): Boolean {
        if (serverSocketRef.get() != null) return true
        return synchronized(this) {
            if (serverSocketRef.get() != null) return@synchronized true
            runCatching {
                var opened: ServerSocket? = null
                var port = DEFAULT_PORT
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
                if (contentLength > 0) {
                    var skipped = 0
                    while (skipped < contentLength) {
                        val buffer = CharArray(4096)
                        val read = reader.read(buffer)
                        if (read < 0) break
                        skipped += read
                    }
                }
                val headOnly = method == "HEAD"
                when {
                    method != "GET" && method != "HEAD" ->
                        writeResponse(socket, 405, "text/plain; charset=utf-8", "Method Not Allowed", headOnly)
                    else -> route(context, socket, target, headOnly)
                }
            }
        }
    }

    private fun route(context: Context, socket: Socket, target: String, headOnly: Boolean) {
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
            path == "/api/conversations" -> writeResponse(
                socket,
                200,
                "application/json; charset=utf-8",
                conversationsJson(context),
                headOnly,
            )
            path.startsWith("/api/conversations/") -> {
                val conversationId = urlDecode(path.removePrefix("/api/conversations/"))
                val body = conversationDetailJson(context, conversationId)
                if (body == null) {
                    writeResponse(socket, 404, "application/json; charset=utf-8", "{\"error\":\"not_found\"}", headOnly)
                } else {
                    writeResponse(socket, 200, "application/json; charset=utf-8", body, headOnly)
                }
            }
            else -> writeResponse(socket, 404, "text/plain; charset=utf-8", "Not Found", headOnly)
        }
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
                    .put("id", conversation.id)
                    .put("title", conversation.title)
                    .put("createdAt", conversation.createdAt)
                    .put("updatedAt", conversation.updatedAt),
            )
            .put("messages", messageArray)
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
.item,.back,.assistant{background:#1c1c1e;box-shadow:none}
.tool{background:#2c2c2e;color:#c7c7cc}
.system_notice{background:#3a2f0f;color:#ffd60a}
}
</style>
</head>
<body>
<header>Eta 聊天记录<small id="meta">本机服务器</small></header>
<div id="root"><div class="empty">加载中…</div></div>
<script>
var root=document.getElementById('root');
function esc(s){return String(s==null?'':s).replace(/[&<>"]/g,function(c){return{'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]})}
function fmt(ts){try{return new Date(ts).toLocaleString()}catch(e){return ''}}
function loadList(){
root.innerHTML='<div class="empty">加载中…</div>';
fetch('/api/conversations').then(function(r){return r.json()}).then(function(list){
if(!list.length){root.innerHTML='<div class="empty">暂无聊天记录</div>';return}
root.innerHTML='';
list.forEach(function(c){
var e=document.createElement('div');e.className='item';
e.innerHTML='<div class="t">'+esc(c.title)+'</div><div class="s">'+esc(fmt(c.updatedAt))+' · '+c.messageCount+' 条消息</div>';
e.onclick=function(){openConv(c.id,c.title)};
root.appendChild(e);
});
}).catch(function(){root.innerHTML='<div class="empty">加载失败</div>'});
}
function openConv(id,title){
root.innerHTML='<div class="empty">加载中…</div>';
fetch('/api/conversations/'+encodeURIComponent(id)).then(function(r){return r.json()}).then(function(data){
root.innerHTML='';
var back=document.createElement('div');back.className='back';back.textContent='← 返回列表';back.onclick=loadList;root.appendChild(back);
var h=document.createElement('div');h.className='title';h.textContent=title||'对话';root.appendChild(h);
var msgs=data.messages||[];
if(!msgs.length){root.innerHTML+='<div class="empty">该对话暂无消息</div>';return}
msgs.forEach(function(m){
if(m.type==='thinking')return;
if((m.type==='tool'||m.type==='tool_summary')&&!m.content)return;
var e=document.createElement('div');
e.className='msg '+m.type;
e.textContent=(m.toolName&&m.type==='tool'?('['+m.toolName+'] '):'')+m.content;
root.appendChild(e);
});
}).catch(function(){root.innerHTML='<div class="empty">加载失败</div>'});
}
fetch('/health').then(function(r){return r.json()}).catch(function(){});
loadList();
</script>
</body>
</html>"""
}
