---
name: pdf
description: 生成、合并、拆分、提取 PDF 内容。当用户要求输出 PDF 报告、把图片/文档转成 PDF、合并拆分 PDF、提取文字或表格时使用。
---

# PDF 处理

在 Eta 的 Linux 环境中用 Python 处理 PDF。运行方式：`uv run --with pypdf python 脚本.py` 或 `uv run --with reportlab python 脚本.py`。

## 生成 PDF

- 文字报告用 reportlab：注册中文 TrueType 字体后才能写中文，例如 `pdfmetrics.registerFont(TTFont("Noto", "/usr/share/fonts/.../NotoSansSC-Regular.ttf"))`；环境内没有可用中文字体时，先把用户可读的字体文件复制进环境路径再注册。
- 排版用 Platypus（SimpleDocTemplate + Paragraph/Spacer/Table/PageBreak），不要手写 canvas 逐行坐标，除非做版式固定的单页。
- 表格用 `Table(data, colWidths=...)` 配 `TableStyle`；长表会自动跨页，表头行用 `repeatRows=1` 重复。
- 图片转 PDF：多张图片按 `Image` 流式排列即可；转换后检查页数与文件大小。

## 读取与操作

- 提取文字：pypdf `PdfReader(path)` 遍历 `page.extract_text()`；扫描件（无文字层）无法直接提取，告知用户需要 OCR 并说明限制。
- 合并：`PdfWriter()` 逐个 `add_page`；拆分：按页码区间写出；都在 pypdf 内完成，不要调用外部二进制。
- 加密与解密：`reader.decrypt(password)` 后读取；输出加密用 `writer.encrypt()`，密码一律提示用户自行保管。

## 交付约定

处理完报告：输出文件绝对路径、页数、大小；文字提取结果超过 2000 字时写入 .txt/.md 文件而不是直接刷屏。
