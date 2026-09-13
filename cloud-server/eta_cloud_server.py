#!/usr/bin/env python3
import argparse
import json
import os
import re
import secrets
import sqlite3
import threading
import time
import urllib.request
import urllib.error
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DB_PATH = os.environ.get("ETA_CLOUD_DB", "eta_cloud.db")
TOKEN = os.environ.get("ETA_CLOUD_TOKEN", "")
AI_BASE_URL = os.environ.get("ETA_AI_BASE_URL", "").rstrip("/")
AI_API_KEY = os.environ.get("ETA_AI_API_KEY", "")
AI_MODEL = os.environ.get("ETA_AI_MODEL", "")
AI_SYSTEM_PROMPT = os.environ.get(
    "ETA_AI_SYSTEM_PROMPT",
    "你是一个乐于助人的 AI 助手，回答使用与用户相同的语言，简洁自然。",
)
PORT = int(os.environ.get("ETA_CLOUD_PORT", "8787"))

DB_LOCK = threading.Lock()


def db():
    conn = sqlite3.connect(DB_PATH)
    conn.execute(
        "CREATE TABLE IF NOT EXISTS conversations ("
        "id TEXT PRIMARY KEY, title TEXT, updated_at INTEGER, deleted INTEGER DEFAULT 0, blob TEXT)"
    )
    conn.execute(
        "CREATE TABLE IF NOT EXISTS memory (id INTEGER PRIMARY KEY CHECK (id = 1), content TEXT, updated_at INTEGER)"
    )
    return conn


def now_ms():
    return int(time.time() * 1000)


def ai_chat(messages):
    if not (AI_BASE_URL and AI_MODEL):
        return None
    payload = json.dumps(
        {"model": AI_MODEL, "messages": [{"role": "system", "content": AI_SYSTEM_PROMPT}] + messages}
    ).encode("utf-8")
    request = urllib.request.Request(
        AI_BASE_URL + "/chat/completions",
        data=payload,
        headers={
            "Content-Type": "application/json",
            "Authorization": "Bearer " + AI_API_KEY,
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            data = json.loads(response.read().decode("utf-8"))
            return data["choices"][0]["message"]["content"]
    except Exception as error:
        return "[AI 调用失败：%s]" % error


class Handler(BaseHTTPRequestHandler):
    server_version = "EtaCloud/1.0"

    def log_message(self, fmt, *args):
        pass

    def _authorized(self):
        if not TOKEN:
            return True
        return self.headers.get("X-Eta-Token", "") == TOKEN

    def _send(self, status, body, content_type="application/json; charset=utf-8"):
        if isinstance(body, (dict, list)):
            body = json.dumps(body, ensure_ascii=False)
        data = body.encode("utf-8") if isinstance(body, str) else body
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, X-Eta-Token")
        self.send_header("Access-Control-Allow-Methods", "GET, PUT, POST, DELETE, OPTIONS")
        self.end_headers()
        self.wfile.write(data)

    def _read_body(self):
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0:
            return {}
        try:
            return json.loads(self.rfile.read(length).decode("utf-8"))
        except Exception:
            return {}

    def do_OPTIONS(self):
        self._send(200, {})

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/" or path == "/index.html":
            return self._send(200, PAGE_HTML, "text/html; charset=utf-8")
        if not self._authorized():
            return self._send(401, {"ok": False, "error": "unauthorized"})
        if path == "/v1/ping":
            return self._send(200, {"ok": True, "server": "eta-cloud", "ai": bool(AI_BASE_URL and AI_MODEL)})
        if path == "/v1/conversations":
            with DB_LOCK, db() as conn:
                rows = conn.execute(
                    "SELECT id, title, updated_at, deleted FROM conversations ORDER BY updated_at DESC"
                ).fetchall()
            return self._send(
                200,
                {
                    "conversations": [
                        {"id": r[0], "title": r[1], "updatedAt": r[2], "deleted": bool(r[3])}
                        for r in rows
                    ]
                },
            )
        match = re.fullmatch(r"/v1/conversations/(.+)", path)
        if match:
            key = match.group(1)
            with DB_LOCK, db() as conn:
                row = conn.execute(
                    "SELECT blob, deleted FROM conversations WHERE id = ?", (key,)
                ).fetchone()
            if not row or row[1]:
                return self._send(404, {"ok": False, "error": "not found"})
            return self._send(200, row[0] or "{}")
        if path == "/v1/memory":
            with DB_LOCK, db() as conn:
                row = conn.execute("SELECT content, updated_at FROM memory WHERE id = 1").fetchone()
            return self._send(
                200,
                {"content": row[0] if row else "", "updatedAt": row[1] if row else 0},
            )
        return self._send(404, {"ok": False, "error": "not found"})

    def do_PUT(self):
        path = self.path.split("?")[0]
        if not self._authorized():
            return self._send(401, {"ok": False, "error": "unauthorized"})
        body = self._read_body()
        match = re.fullmatch(r"/v1/conversations/(.+)", path)
        if match:
            key = match.group(1)
            title = str(body.get("title") or "")[:200]
            updated = int(body.get("updatedAt") or now_ms())
            blob = json.dumps(body, ensure_ascii=False)
            with DB_LOCK, db() as conn:
                conn.execute(
                    "INSERT INTO conversations (id, title, updated_at, deleted, blob) VALUES (?, ?, ?, 0, ?) "
                    "ON CONFLICT(id) DO UPDATE SET title = excluded.title, updated_at = excluded.updated_at, "
                    "deleted = 0, blob = excluded.blob",
                    (key, title, updated, blob),
                )
                conn.commit()
            return self._send(200, {"ok": True})
        if path == "/v1/memory":
            content = str(body.get("content") or "")
            with DB_LOCK, db() as conn:
                conn.execute(
                    "INSERT INTO memory (id, content, updated_at) VALUES (1, ?, ?) "
                    "ON CONFLICT(id) DO UPDATE SET content = excluded.content, updated_at = excluded.updated_at",
                    (content, now_ms()),
                )
                conn.commit()
            return self._send(200, {"ok": True})
        return self._send(404, {"ok": False, "error": "not found"})

    def do_DELETE(self):
        path = self.path.split("?")[0]
        if not self._authorized():
            return self._send(401, {"ok": False, "error": "unauthorized"})
        match = re.fullmatch(r"/v1/conversations/(.+)", path)
        if match:
            key = match.group(1)
            with DB_LOCK, db() as conn:
                conn.execute(
                    "UPDATE conversations SET deleted = 1, updated_at = ? WHERE id = ?",
                    (now_ms(), key),
                )
                conn.commit()
            return self._send(200, {"ok": True})
        return self._send(404, {"ok": False, "error": "not found"})

    def do_POST(self):
        path = self.path.split("?")[0]
        if not self._authorized():
            return self._send(401, {"ok": False, "error": "unauthorized"})
        body = self._read_body()
        match = re.fullmatch(r"/v1/conversations/(.+)/messages", path)
        if match:
            key = match.group(1)
            user_text = str(body.get("content") or "").strip()
            if not user_text:
                return self._send(400, {"ok": False, "error": "empty content"})
            with DB_LOCK, db() as conn:
                row = conn.execute("SELECT blob FROM conversations WHERE id = ?", (key,)).fetchone()
                if row and row[0]:
                    try:
                        conversation = json.loads(row[0])
                    except Exception:
                        conversation = None
                else:
                    conversation = None
                messages = []
                if conversation:
                    for item in conversation.get("messages", []):
                        role = item.get("type")
                        content = item.get("content") or ""
                        if role == "user" and content:
                            messages.append({"role": "user", "content": content})
                        elif role == "assistant" and content:
                            messages.append({"role": "assistant", "content": content})
                messages.append({"role": "user", "content": user_text})
                existing = conversation.get("messages", []) if conversation else []
                max_index = max((int(m.get("sortIndex", -1)) for m in existing), default=-1)
                existing.append(
                    {
                        "id": "web-user-%d" % now_ms(),
                        "conversationId": key,
                        "sortIndex": max_index + 1,
                        "type": "user",
                        "content": user_text,
                        "imagesJson": "[]",
                        "isEdited": False,
                        "imageCount": 0,
                        "toolsJson": "[]",
                    }
                )
            reply = ai_chat(messages)
            if reply is not None:
                existing.append(
                    {
                        "id": "web-assistant-%d" % now_ms(),
                        "conversationId": key,
                        "sortIndex": max_index + 2,
                        "type": "assistant",
                        "content": reply,
                        "imagesJson": "[]",
                        "isEdited": False,
                        "renderMarkdown": True,
                        "imageCount": 0,
                        "toolsJson": "[]",
                    }
                )
            updated = now_ms()
            title = user_text[:30] if not existing[:-1] else None
            if conversation:
                conversation["messages"] = existing
                conversation["updatedAt"] = updated
                new_blob = conversation
                new_title = title or conversation.get("title") or user_text[:30]
            else:
                new_blob = {
                    "version": 1,
                    "id": key,
                    "title": title or user_text[:30],
                    "createdAt": updated,
                    "updatedAt": updated,
                    "messages": existing,
                }
                new_title = new_blob["title"]
            blob_text = json.dumps(new_blob, ensure_ascii=False)
            with DB_LOCK, db() as conn:
                conn.execute(
                    "INSERT INTO conversations (id, title, updated_at, deleted, blob) VALUES (?, ?, ?, 0, ?) "
                    "ON CONFLICT(id) DO UPDATE SET title = excluded.title, updated_at = excluded.updated_at, "
                    "deleted = 0, blob = excluded.blob",
                    (key, new_title, updated, blob_text),
                )
                conn.commit()
            return self._send(
                200,
                {"ok": True, "assistantReply": reply, "updatedAt": updated},
            )
        if re.fullmatch(r"/v1/conversations/(.+)/continue", path):
            key = path.split("/")[3]
            with DB_LOCK, db() as conn:
                row = conn.execute("SELECT blob FROM conversations WHERE id = ?", (key,)).fetchone()
            if not row or not row[0]:
                return self._send(404, {"ok": False, "error": "not found"})
            conversation = json.loads(row[0])
            messages = []
            for item in conversation.get("messages", []):
                role = item.get("type")
                content = item.get("content") or ""
                if role == "user" and content:
                    messages.append({"role": "user", "content": content})
                elif role == "assistant" and content:
                    messages.append({"role": "assistant", "content": content})
            reply = ai_chat(messages)
            return self._send(200, {"ok": True, "assistantReply": reply})
        return self._send(404, {"ok": False, "error": "not found"})


PAGE_HTML = """<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Eta 云端对话</title>
<style>
body{font-family:system-ui,sans-serif;margin:0;background:#0f1115;color:#e8eaf0;display:flex;height:100vh}
aside{width:240px;border-right:1px solid #23262f;overflow-y:auto;padding:12px;box-sizing:border-box}
main{flex:1;display:flex;flex-direction:column}
header{padding:12px 16px;border-bottom:1px solid #23262f;font-weight:600}
#chat{flex:1;overflow-y:auto;padding:16px;display:flex;flex-direction:column;gap:10px}
.msg{max-width:75%;padding:10px 14px;border-radius:14px;white-space:pre-wrap;word-break:break-word;line-height:1.5}
.user{align-self:flex-end;background:#2f6fed;color:#fff}
.assistant{align-self:flex-start;background:#1d2129}
form{display:flex;gap:8px;padding:12px;border-top:1px solid #23262f}
input[type=text]{flex:1;padding:12px;border-radius:10px;border:1px solid #2c313c;background:#171a21;color:#e8eaf0}
button{padding:10px 18px;border-radius:10px;border:0;background:#2f6fed;color:#fff;cursor:pointer}
.conv{padding:10px;border-radius:8px;cursor:pointer;font-size:13px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.conv:hover,.conv.active{background:#1d2129}
</style>
</head>
<body>
<aside><h3 style="font-size:14px;margin:6px 0 10px">对话列表</h3><div id="list">加载中…</div></aside>
<main><header id="title">Eta 云端对话</header><div id="chat"></div>
<form id="form"><input type="text" id="input" placeholder="输入消息…" autocomplete="off"><button>发送</button></form></main>
<script>
const token = new URLSearchParams(location.search).get('token') || '';
const H = {'Content-Type':'application/json','X-Eta-Token':token};
let current = null;
async function loadList(){
  const r = await fetch('/v1/conversations',{headers:H});
  const d = await r.json();
  const el = document.getElementById('list');
  el.innerHTML = '';
  (d.conversations||[]).filter(c=>!c.deleted).forEach(c=>{
    const div = document.createElement('div');
    div.className = 'conv' + (c.id===current?' active':'');
    div.textContent = c.title || c.id;
    div.onclick = ()=>openConv(c.id);
    el.appendChild(div);
  });
}
async function openConv(id){
  current = id;
  const r = await fetch('/v1/conversations/'+encodeURIComponent(id),{headers:H});
  const d = await r.json();
  document.getElementById('title').textContent = d.title || id;
  const chat = document.getElementById('chat');
  chat.innerHTML = '';
  (d.messages||[]).forEach(m=>{
    if(!m.content) return;
    if(m.type==='user') add('user', m.content);
    else if(m.type==='assistant') add('assistant', m.content);
  });
  loadList();
}
function add(role, text){
  const div = document.createElement('div');
  div.className = 'msg '+role;
  div.textContent = text;
  document.getElementById('chat').appendChild(div);
  return div;
}
document.getElementById('form').onsubmit = async (e)=>{
  e.preventDefault();
  if(!current){ current = 'web-' + Date.now(); }
  const input = document.getElementById('input');
  const text = input.value.trim();
  if(!text) return;
  input.value = '';
  add('user', text);
  const waiting = add('assistant', '…');
  const r = await fetch('/v1/conversations/'+encodeURIComponent(current)+'/messages',
    {method:'POST',headers:H,body:JSON.stringify({content:text})});
  const d = await r.json();
  waiting.textContent = d.assistantReply || '（已保存，AI 未在线续写）';
  loadList();
};
loadList();
</script>
</body>
</html>
"""


def main():
    global TOKEN, PORT
    parser = argparse.ArgumentParser(description="Eta 云端对话服务器")
    parser.add_argument("--port", type=int, default=PORT)
    parser.add_argument("--token", default=TOKEN, help="访问令牌，不填则自动生成")
    args = parser.parse_args()
    PORT = args.port
    TOKEN = args.token
    if not TOKEN:
        TOKEN = secrets.token_hex(16)
    print("=" * 52)
    print("Eta 云端对话服务器已启动")
    print("  地址:     http://0.0.0.0:%d" % PORT)
    print("  访问令牌: %s" % TOKEN)
    print("  网页聊天: http://127.0.0.1:%d/?token=%s" % (PORT, TOKEN))
    if AI_BASE_URL and AI_MODEL:
        print("  AI 续写:  已启用 (%s / %s)" % (AI_BASE_URL, AI_MODEL))
    else:
        print("  AI 续写:  未配置（设置 ETA_AI_BASE_URL / ETA_AI_API_KEY / ETA_AI_MODEL 后启用）")
    print("=" * 52)
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    server.serve_forever()


if __name__ == "__main__":
    main()
