---
name: pptx
description: 创建与修改 PowerPoint 演示文稿（.pptx）。当用户要求做 PPT、幻灯片、汇报演示、产品介绍或从大纲生成演示文稿时使用。
---

# PowerPoint 演示文稿

在 Eta 的 Linux 环境中用 python-pptx 生成 .pptx：`uv run --with python-pptx python 脚本.py`。

## 结构先行

动手前先确定：页数、每页标题与要点、是否需要图表/图片。无特殊说明按「封面 → 目录/概览 → 内容页 × N → 总结页」组织，每页要点不超过 5 条、每条不超过 20 字，文字宁少勿多。

## 生成要点

- 版式：标题页用 `prs.slide_layouts[0]`，内容页用 `[1]`，空白页用 `[6]` 自由排版。
- 16:9 尺寸：`prs.slide_width = Inches(13.333)`，`prs.slide_height = Inches(7.5)`。
- 中文字体：对每个 `run.font` 设置 `name`，并设置 `run.font.name` 的 eastAsia：`from pptx.oxml.ns import qn; run.font._rPr.rFonts.set(qn("a:ea"), "微软雅黑")`。
- 占位符填充：`slide.shapes.title.text`、`slide.placeholders[1].text`；逐级要点用 `tf.paragraphs` 的 `level` 控制。
- 配色克制：整份演示不超过 3 种主色；标题与正文字号建议 32/18pt 以上，保证投影可读。
- 图表：`slide.shapes.add_chart(XL_CHART_TYPE.COLUMN_CLUSTERED, ...)` 传入 CategoryChartData；数据行/列都要有标题，否则图例缺失。
- 图片：`add_picture(path, left, top, width=...)`，等比缩放只给 width 或 height 之一。

## 校验

生成后用脚本重新打开，统计每页 shape 数与文字长度，检查是否有空页、溢出占位符；把文件绝对路径告诉用户。
