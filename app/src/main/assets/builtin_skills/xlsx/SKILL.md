---
name: xlsx
description: 创建、读取、分析 Excel 表格（.xlsx/.csv）。当用户要求制作表格、汇总数据、公式计算、多表合并、数据透视或导出 Excel 文件时使用。
---

# Excel 表格处理

在 Eta 的 Linux 环境中用 Python 操作电子表格。统一用 `uv run --with openpyxl python 脚本.py` 运行；处理超大数据（>50 万行）改用分批读取。

## 创建表格

- 基础：`Workbook()` → `ws = wb.active` → `ws.append([...])`；保存 `wb.save(path)`。
- 表头加粗：`Font(bold=True)`；列宽：`ws.column_dimensions["A"].width = 18`（中文按 2 倍字符估宽）。
- 公式直接写字符串：`ws["D2"] = "=SUM(B2:C2)"`，写入时不会被计算，由 Excel 打开时计算；需要立即得到值时在 Python 里先算好再写结果。
- 数字不要写成字符串，保留 int/float 类型；日期用 `datetime` 对象并设置 `number_format = "YYYY-MM-DD"`。
- 冻结首行：`ws.freeze_panes = "A2"`；自动筛选：`ws.auto_dimensions` 不存在，用 `ws.auto_filter.ref = ws.dimensions`。

## 读取与分析

- 只读模式省内存：`load_workbook(path, read_only=True, data_only=True)`；`data_only=True` 才能读到公式的缓存计算值。
- 统计需求先输出结构：打印每个 sheet 的行列数与前 3 行样例，再决定处理逻辑，避免盲目遍历。
- 多文件合并：逐个读取 append 到新 Workbook；列名不一致时先对齐列再合并。
- CSV 与 xlsx 互转：`openpyxl` 读 csv 用标准库 `csv` 模块，编码默认 utf-8-sig 以兼容 Excel 打开。

## 输出约定

处理完成后向用户报告：文件绝对路径、行数、sheet 列表、关键统计值；结果数据较大时同步生成一份 csv 摘要。
