"""Generates app/src/main/java/cn/embeddedquiz/AppIcons.kt from official Lucide SVG files.

Usage:  python -I tools/lucide_icons.py <folder with the downloaded .svg files>

The SVGs come from the lucide-static npm package (version below); the generated file and
docs/third_party/LUCIDE.md list every icon used. Every element kind Lucide uses (path, circle,
ellipse, rect, line, polyline, polygon) is converted to path data, so no stroke is lost.
Path data is re-emitted with explicit separators, including the arc flags SVG lets run together.
"""

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

LUCIDE_VERSION = "1.69.0"
ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "app/src/main/java/cn/embeddedquiz/AppIcons.kt"
DOC = ROOT / "docs/third_party/LUCIDE.md"

# (Kotlin name, Lucide icon, where it is used, filled)
ICONS = [
    ("Home", "house", "底栏 首页", False),
    ("Exam", "clipboard-list", "底栏 模拟考试", False),
    ("QuickNotes", "book-open", "底栏 速记、速记文档", False),
    ("Mistakes", "circle-alert", "底栏 错题", False),
    ("More", "layout-grid", "底栏 更多", False),
    ("Review", "refresh-cw", "首页 复习入口", False),
    ("Sync", "refresh-cw", "更多 智能复习", False),
    ("NotebookPen", "notebook-pen", "更多 全局标签 / 便签", False),
    ("ClipboardCheck", "clipboard-check", "更多 待核对工作台", False),
    ("BarChart", "chart-no-axes-combined", "更多 历史统计", False),
    ("Coin", "coins", "更多 AI 费用估算", False),
    ("Settings", "settings", "更多 设置", False),
    ("Back", "arrow-left", "顶栏返回", False),
    ("Practice", "pencil-line", "练习（保留接口）", False),
    ("ChevronLeft", "chevron-left", "上一题", False),
    ("ChevronRight", "chevron-right", "列表行、下一题", False),
    ("ChevronDown", "chevron-down", "展开", False),
    ("ChevronUp", "chevron-up", "收起", False),
    ("Note", "sticky-note", "题目笔记", False),
    ("Star", "star", "收藏", False),
    ("StarFilled", "star", "已收藏（实心）", True),
    ("MoreVert", "ellipsis-vertical", "溢出菜单", False),
    ("Check", "check", "选中、正确", False),
    ("Close", "x", "关闭、错误", False),
    ("Help", "circle-question-mark", "不会", False),
    ("Chat", "message-square", "AI 讨论", False),
    ("Pencil", "pencil", "编辑", False),
    ("Tag", "tag", "标签（保留接口）", False),
    ("Info", "info", "提示", False),
    ("Clock", "clock", "计时", False),
    ("Send", "send-horizontal", "发送追问", False),
    ("Code", "code", "编程专区", False),
    ("Flag", "flag", "标记", False),
    ("Search", "search", "搜索", False),
    ("Play", "play", "开始（实心）", True),
    ("Shuffle", "shuffle", "随机练习", False),
    ("ListOrder", "list", "顺序练习、速记目录", False),
    ("Target", "target", "薄弱点练习", False),
    ("Book", "book", "题库、阅读", False),
    ("Folder", "folder", "分类练习", False),
    ("Trash", "trash-2", "删除", False),
    ("Import", "download", "导入", False),
    ("Person", "user", "头像占位", False),
    ("Flame", "flame", "连续学习", False),
    ("Plus", "plus", "AI 讨论 来源分组新建", False),
    ("SquarePen", "square-pen", "AI 讨论 新建自由对话", False),
    ("FileText", "file-text", "AI 讨论 题目来源", False),
    ("MessagesSquare", "messages-square", "AI 讨论 自由对话", False),
    ("Sparkles", "sparkles", "AI 讨论 临时问 AI", False),
    ("ArrowUpRight", "arrow-up-right", "AI 讨论 打开来源", False),
    ("Sun", "sun", "设置 外观", False),
    ("Bot", "bot", "设置 AI 模型", False),
    ("Bell", "bell", "设置 复习提醒", False),
    ("Database", "database", "设置 题库与备份", False),
    ("Stethoscope", "stethoscope", "设置 诊断", False),
    ("Pin", "pin", "速记 置顶", False),
    ("KeyRound", "key-round", "设置 API Key", False),
    ("Palette", "palette", "设置 主题色", False),
    ("Type", "type", "设置 字号", False),
    ("Heading", "heading", "速记编辑 标题", False),
    ("ListChecks", "list-checks", "速记编辑 待办", False),
    ("Pilcrow", "pilcrow", "速记编辑 段落", False),
    ("Eye", "eye", "速记编辑 预览", False),
    ("Upload", "upload", "导出备份", False),
]

ARGS = {"m": 2, "l": 2, "h": 1, "v": 1, "c": 6, "s": 4, "q": 4, "t": 2, "a": 7, "z": 0}
NUMBER = re.compile(r"[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?")


def fmt(value: float) -> str:
    text = f"{value:.4f}".rstrip("0").rstrip(".")
    return "0" if text in ("-0", "") else text


def normalize(d: str) -> str:
    """Re-emits path data with explicit separators; arc flags are single digits."""
    out, i, cmd = [], 0, None
    while i < len(d):
        ch = d[i]
        if ch.isspace() or ch == ",":
            i += 1
            continue
        if ch.isalpha():
            cmd = ch
            out.append(ch)
            i += 1
            if ch in "zZ":
                cmd = None
            continue
        if cmd is None:
            raise ValueError(f"number without command at {i}: {d!r}")
        values = []
        for k in range(ARGS[cmd.lower()]):
            while i < len(d) and (d[i].isspace() or d[i] == ","):
                i += 1
            if cmd in "aA" and k in (3, 4):
                if d[i] not in "01":
                    raise ValueError(f"bad arc flag at {i}: {d!r}")
                values.append(d[i])
                i += 1
                continue
            match = NUMBER.match(d, i)
            if not match:
                raise ValueError(f"expected number at {i}: {d!r}")
            values.append(fmt(float(match.group())))
            i = match.end()
        out.append(" ".join(values))
        # Repeated coordinates after a moveto are implicit linetos.
        if cmd == "m":
            cmd = "l"
        elif cmd == "M":
            cmd = "L"
    text = ""
    for part in out:
        text += part if (part[0].isalpha() or not text or text[-1].isalpha()) else " " + part
    return text


def num(element, name, default=0.0):
    value = element.get(name)
    return float(value) if value is not None else default


def to_path(element) -> str:
    tag = element.tag.split("}")[-1]
    if tag == "path":
        return normalize(element.get("d"))
    if tag in ("circle", "ellipse"):
        cx, cy = num(element, "cx"), num(element, "cy")
        rx = num(element, "r") if tag == "circle" else num(element, "rx")
        ry = num(element, "r") if tag == "circle" else num(element, "ry")
        return (
            f"M{fmt(cx - rx)} {fmt(cy)}a{fmt(rx)} {fmt(ry)} 0 1 0 {fmt(2 * rx)} 0"
            f"a{fmt(rx)} {fmt(ry)} 0 1 0 {fmt(-2 * rx)} 0z"
        )
    if tag == "rect":
        x, y, w, h = num(element, "x"), num(element, "y"), num(element, "width"), num(element, "height")
        rx = element.get("rx")
        ry = element.get("ry")
        rx = float(rx) if rx is not None else (float(ry) if ry is not None else 0.0)
        ry = float(ry) if ry is not None else rx
        rx, ry = min(rx, w / 2), min(ry, h / 2)
        if rx == 0 or ry == 0:
            return f"M{fmt(x)} {fmt(y)}h{fmt(w)}v{fmt(h)}h{fmt(-w)}z"
        return (
            f"M{fmt(x + rx)} {fmt(y)}h{fmt(w - 2 * rx)}a{fmt(rx)} {fmt(ry)} 0 0 1 {fmt(rx)} {fmt(ry)}"
            f"v{fmt(h - 2 * ry)}a{fmt(rx)} {fmt(ry)} 0 0 1 {fmt(-rx)} {fmt(ry)}"
            f"h{fmt(-(w - 2 * rx))}a{fmt(rx)} {fmt(ry)} 0 0 1 {fmt(-rx)} {fmt(-ry)}"
            f"v{fmt(-(h - 2 * ry))}a{fmt(rx)} {fmt(ry)} 0 0 1 {fmt(rx)} {fmt(-ry)}z"
        )
    if tag == "line":
        return (
            f"M{fmt(num(element, 'x1'))} {fmt(num(element, 'y1'))}"
            f"L{fmt(num(element, 'x2'))} {fmt(num(element, 'y2'))}"
        )
    if tag in ("polyline", "polygon"):
        values = [fmt(float(v)) for v in NUMBER.findall(element.get("points"))]
        pairs = [f"{values[k]} {values[k + 1]}" for k in range(0, len(values), 2)]
        return "M" + "L".join(pairs) + ("z" if tag == "polygon" else "")
    raise ValueError(f"unsupported element <{tag}>")


def read_icon(folder: Path, name: str):
    root = ET.parse(folder / f"{name}.svg").getroot()
    assert root.get("viewBox") == "0 0 24 24", name
    assert root.get("stroke-width") == "2", name
    assert root.get("stroke-linecap") == "round" and root.get("stroke-linejoin") == "round", name
    parts = []
    for element in root:
        if element.get("transform"):
            raise ValueError(f"{name}: transforms are not supported")
        filled = element.get("fill") not in (None, "none")
        parts.append(("!" if filled else "") + to_path(element))
    return parts


def kotlin_string(text: str) -> str:
    return '"' + text.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'


def main(folder: Path):
    lines = [
        "package cn.embeddedquiz",
        "",
        "import androidx.compose.ui.graphics.Color",
        "import androidx.compose.ui.graphics.SolidColor",
        "import androidx.compose.ui.graphics.StrokeCap",
        "import androidx.compose.ui.graphics.StrokeJoin",
        "import androidx.compose.ui.graphics.vector.ImageVector",
        "import androidx.compose.ui.graphics.vector.addPathNodes",
        "import androidx.compose.ui.unit.dp",
        "",
        "// GENERATED by tools/lucide_icons.py from Lucide " + LUCIDE_VERSION + " (https://lucide.dev/icons/).",
        "// Do not edit by hand; regenerate instead. License: docs/third_party/LUCIDE_LICENSE.txt.",
        "",
        "/**",
        " * Lucide stroke icons on a 24-unit grid: 2-unit stroke, round caps and joins. Tinting replaces",
        " * the black stroke. A path starting with '!' is filled as well (Lucide's fill=\"currentColor\").",
        " */",
        "object AppIcons {",
        "    /** Lucide name of each icon, kept for the license notice and the icon tests. */",
        "    val sources = linkedMapOf<String, String>()",
        "",
        "    private fun lucide(name: String, vararg paths: String, filled: Boolean = false): ImageVector {",
        "        sources[name + if (filled) \" (filled)\" else \"\"] = name",
        "        return ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)",
        "            .apply {",
        "                paths.forEach {",
        "                    val fill = filled || it.startsWith('!')",
        "                    addPath(",
        "                        pathData = addPathNodes(it.removePrefix(\"!\")),",
        "                        fill = if (fill) SolidColor(Color.Black) else null,",
        "                        stroke = SolidColor(Color.Black),",
        "                        strokeLineWidth = 2f,",
        "                        strokeLineCap = StrokeCap.Round,",
        "                        strokeLineJoin = StrokeJoin.Round,",
        "                    )",
        "                }",
        "            }",
        "            .build()",
        "    }",
        "",
    ]
    doc_rows = []
    for kotlin, lucide, usage, filled in ICONS:
        parts = read_icon(folder, lucide)
        args = ", ".join(kotlin_string(p) for p in parts)
        extra = ", filled = true" if filled else ""
        lines.append(f"    // {usage}")
        lines.append(f"    val {kotlin} = lucide({kotlin_string(lucide)}, {args}{extra})")
        doc_rows.append(f"| `AppIcons.{kotlin}` | [{lucide}](https://lucide.dev/icons/{lucide}) | {usage} |")
    lines.append("}")
    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")

    DOC.parent.mkdir(parents=True, exist_ok=True)
    DOC.write_text(
        "\n".join(
            [
                "# Lucide 图标来源",
                "",
                f"应用内图标来自 [Lucide](https://lucide.dev/icons/) 官方 SVG（npm 包 `lucide-static@{LUCIDE_VERSION}` 的 `icons/*.svg`），",
                "由 `tools/lucide_icons.py` 转换为本地 Compose `ImageVector`（`AppIcons.kt`），运行时不访问网络、不引入图标字体或依赖包。",
                "path、circle、rect、line 等元素全部转换为路径；保持 24 单位视口、2 单位描边、圆端点与圆连接。",
                "",
                "许可：ISC（Lucide），其中源自 Feather 的图标另受 MIT 许可，全文见 `LUCIDE_LICENSE.txt`，随 APK 附于 `assets/licenses/`。",
                "",
                "重新生成：下载同版本 SVG 到任意目录后运行 `python -I tools/lucide_icons.py <目录>`。",
                "",
                "| 接口 | Lucide 图标 | 用途 |",
                "| --- | --- | --- |",
                *doc_rows,
                "",
            ]
        ),
        encoding="utf-8",
        newline="\n",
    )
    print(f"wrote {len(ICONS)} icons to {OUT}")


if __name__ == "__main__":
    main(Path(sys.argv[1]))
