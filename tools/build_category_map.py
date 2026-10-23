"""生成可编辑的显示分类映射；不改变 bank.json。"""
import json
from pathlib import Path
import re

def normalize(raw):
    value = re.sub(r"^\s*\d+[.、\s]*", "", raw)
    if "C++" in value:
        return "C++"
    if any(x in value for x in ("数据结构", "算法", "逻辑")):
        return "数据结构与算法"
    if "C语言" in value or value == "C基础":
        return "C语言与内存"
    if any(x in value for x in ("设备驱动", "驱动", "设备树", "字符设备", "内核模块", "用户内核")):
        return "Linux驱动与内核"
    if "系统移植" in value or "U Boot" in value:
        return "系统移植"
    if any(x in value for x in ("进程", "线程", "并发", "操作系统")):
        return "操作系统与并发"
    if "FreeRTOS" in value or "实时系统" in value:
        return "实时操作系统"
    if "STM32" in value or "硬件" in value or "接口" in value:
        return "硬件与通信接口"
    if "Linux" in value:
        return "Linux基础与工具"
    if "数据库" in value:
        return "数据库"
    if "网络" in value:
        return "网络与协议"
    return value or "未分类"

if __name__ == "__main__":
    assets = Path(__file__).resolve().parents[1] / "app/src/main/assets"
    bank = json.loads((assets / "bank.json").read_text(encoding="utf-8"))
    mapping = {name: normalize(name) for name in sorted({q.get("category", "未分类") for q in bank["questions"]})}
    (assets / "category-map.json").write_text(json.dumps({"version": 1, "notice": "仅用于显示和筛选，不修改原始分类，可手动编辑 mapping 后重新构建。", "mapping": mapping}, ensure_ascii=False, indent=2)+"\n",encoding="utf-8")
