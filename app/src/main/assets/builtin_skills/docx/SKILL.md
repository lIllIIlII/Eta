---
name: docx
description: 创建、读取、编辑 Word 文档（.docx）。当用户要求生成报告、公文、简历、合同、通知等 Word 文件，或需要从 .docx 中提取/修改内容时使用。
---

# Word 文档处理

在 Eta 的 Linux 环境中用 Python 操作 .docx（需先安装 Python 工具环境）。优先使用 `uv run --with python-docx python 脚本.py` 的方式运行，依赖自动解析无需手动 pip install。

## 创建新文档

用 python-docx 编写脚本生成。核心要点：

- 页面：`section = doc.sections[0]`，中文文档常用 A4（`Cm(21) x Cm(29.7)`）。
- 中文字体：设置 `run.font.name = "Times New Roman"` 后必须同时设置 `run._element.rPr.rFonts.set(qn("w:eastAsia"), "宋体")`，否则中文显示为默认字体。
- 标题用 `doc.add_heading(text, level)`，正文用 `doc.add_paragraph`；列表用样式 `List Bullet` / `List Number`，不要手打符号。
- 表格：`doc.add_table(rows, cols)`，设置 `table.style = "Table Grid"` 才有边框；表头行加粗。
- 插图：`doc.add_picture(path, width=Cm(15))`；页眉页脚、页码通过 section.header / footer 操作。
- 保存为 .docx 后写绝对路径告诉用户文件位置。

## 读取与修改现有文档

- 读正文：`Document(path)` 后遍历 `doc.paragraphs` 与 `doc.tables`（表格在 paragraphs 里不出现，必须单独遍历）。
- 批量替换：遍历每个 paragraph 的 `paragraph.text` 并操作 runs；跨 run 的文本需要先合并再替换。
- 复杂编辑可走 XML：docx 是 zip 包，`unzip` 后改 `word/document.xml` 再 `zip` 回去；改前务必备份原文件。

## 校验

生成后用脚本重新打开文档统计段落数、表格数，确认无异常再交付；无法渲染预览时提示用户用 WPS/Office 打开查看。
