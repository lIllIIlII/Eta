---
name: data-analysis
description: 数据分析与可视化。当用户提供数据文件（csv/xlsx/json/log）需要清洗、统计、建模结论或绘制图表时使用。
---

# 数据分析与可视化

在 Eta 的 Linux 环境中用 Python 完成分析：`uv run --with pandas --with matplotlib python 脚本.py`，可按需追加 openpyxl、numpy、scikit-learn。

## 流程

1. 先看数据：读取后立即输出 shape、字段类型、缺失值统计与前 5 行，确认分隔符/编码/表头（中文文件常见 gbk 编码，用 `encoding="gbk"` 或 chardet 探测）。
2. 清洗：去重、处理缺失（明确写出策略：填充/丢弃/保留原样）、类型转换（金额转 float、日期转 datetime）。所有改动记录在最终说明里。
3. 分析：统计指标按用户目标选择（分布用 describe/分位数、趋势用时间聚合、对比用分组透视 `pivot_table`）；先算总量与均值给直觉，再下钻。
4. 可视化：中文图表先设字体
   `import matplotlib; matplotlib.rcParams["font.sans-serif"] = ["Noto Sans SC", "WenQuanYi Zen Hei"]; matplotlib.rcParams["axes.unicode_minus"] = False`
   环境缺中文字体时把图表标题改用英文，或先安装 fonts-noto-cjk。图表保存为 PNG（dpi=150）写入工作目录。
5. 结论：用业务语言总结 3~5 条发现，每条附支撑数字；区分「数据支持的事实」与「推测建议」。

## 交付约定

- 图表一律落盘 PNG 并给出绝对路径，不在终端里贴 base64。
- 处理脚本保存为 .py 文件便于复跑；输出目录用 /workspace。
- 数据超过百万行时先抽样验证逻辑再全量运行，避免超时。
