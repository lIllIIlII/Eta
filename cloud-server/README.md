# Eta 云端对话服务器

单文件、零依赖（仅需 Python 3.8+ 标准库）的云端对话中转服务器。配合 Eta App 的「设置 → 云端同步」使用。

## 功能

- 保存 App 同步上来的对话与 MEMORY.md
- App 关闭后台时，前台服务继续定期上传/拉取
- App 被卸载后，对话仍保存在服务器上；重装 App 后「从云端恢复」一键拉回
- 内置网页聊天页面：在浏览器打开服务器地址即可继续与 AI 对话（App 内的对话历史完整保留）
- 可选 AI 断线续写：配置模型服务商后，网页上发的消息由服务器直接调用 AI 回复

## 部署

```bash
# 直接运行（令牌自动生成并打印）
python3 eta_cloud_server.py --port 8787

# 指定令牌与 AI 续写（任一 OpenAI 兼容接口）
ETA_AI_BASE_URL="https://api.openai.com/v1" \
ETA_AI_API_KEY="sk-..." \
ETA_AI_MODEL="gpt-5.5" \
python3 eta_cloud_server.py --port 8787 --token 你的令牌
```

用 systemd 常驻：

```ini
[Unit]
Description=Eta Cloud Server
After=network.target

[Service]
ExecStart=/usr/bin/python3 /opt/eta-cloud/eta_cloud_server.py --port 8787 --token 你的令牌
Environment=ETA_AI_BASE_URL=https://api.openai.com/v1
Environment=ETA_AI_API_KEY=sk-...
Environment=ETA_AI_MODEL=gpt-5.5
Restart=always

[Install]
WantedBy=multi-user.target
```

公网部署建议用 Nginx / Cloudflare 反代加上 HTTPS。

## App 端配置

打开 Eta → 设置 → 云端同步：

1. 启用「云端同步」
2. 填入服务器地址（如 `https://你的域名:8787`）与访问令牌
3. 按需开启「后台保持同步」
4. 「立即同步」上传并拉取；重装后用「从云端恢复」

## 接口

所有请求需带 `X-Eta-Token` 请求头（未设置令牌时免鉴权）。

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/v1/ping` | 连通性检查 |
| GET | `/v1/conversations` | 对话列表（含 updatedAt 与删除标记） |
| GET/PUT | `/v1/conversations/{id}` | 读取/写入完整对话快照 |
| DELETE | `/v1/conversations/{id}` | 删除（墓碑标记，同步到 App） |
| POST | `/v1/conversations/{id}/messages` | 追加网页端消息，AI 已配置时自动续写 |
| POST | `/v1/conversations/{id}/continue` | 让 AI 续写当前对话 |
| GET/PUT | `/v1/memory` | 读取/写入 MEMORY.md |
| GET | `/` | 网页聊天页面 |

数据存储在同目录的 `eta_cloud.db`（SQLite），备份该文件即备份全部云端数据。
