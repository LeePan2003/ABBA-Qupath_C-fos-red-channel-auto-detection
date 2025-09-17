#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
批量重排CSV：将相同 AnnotationName 行聚在一起，并按大类优先级排序
优先级：0 Isocortex -> 1 Hippocampus -> 2 Amygdala -> 3 Striatum -> 4 Thalamus -> 5 Hypothalamus -> 6 其他
每类内部默认按“首次出现顺序”，可改为字母序。
"""

import csv, os, codecs, tempfile
from collections import OrderedDict

# ================= 配置区 =================
# 类别内是否按字母序（True）而非首次出现顺序（False）
ALPHA_SORT_WITHIN_CATEGORY = False
# 输入编码（Excel导出常见为 utf-8-sig；如是GBK可改为 "gbk"）
INPUT_ENCODING = "utf-8-sig"
# 要处理的目录：默认脚本所在目录
TARGET_DIR = os.path.dirname(os.path.abspath(__file__))
# ========================================

# ---------- Isocortex 判定 ----------
ISO_PREFIXES = [
    "RSP", "VIS", "SSp", "SS", "AUD", "ORB", "AI", "MO", "FR", "ACC", "PL", "ILA", "PTLp", "TEa", "ECT", "PERI"
]
ISO_NAMES = {"VISC", "OLF"}  # 按需增减

# ---------- Hippocampus 判定 ----------
HIP_NAMES_EXACT = {"CA1", "CA2", "CA3", "DG", "DG-po", "DG-sg", "DG-mo"}
HIP_PREFIXES = ["DG-"]  # 以 DG- 开头的细分

# ---------- Amygdala（杏仁核）判定 ----------
AMY_PREFIXES = ["AMY", "BLA", "BMA", "LA", "CeA", "CEA", "MEA", "COA", "NLOT", "PMCO", "PLCO"]
AMY_NAMES_EXACT = {"CeA", "CEA", "LA", "BLA", "BMA", "MEA", "COA", "NLOT", "PMCO", "PLCO"}

# ---------- Striatum（纹状体）判定 ----------
STR_PREFIXES = ["STR", "CP", "ACB", "OT"]
STR_NAMES_EXACT = {"STR", "STRd", "STRv", "CP", "CPu", "ACB", "OT"}

# ---------- Thalamus（丘脑）判定 ----------
THA_PREFIXES = [
    "TH", "MD", "LP", "PO", "POm", "VL", "VM", "VP", "VPL", "VPM", "LD", "LG", "LGd", "LGv", "MG", "AV", "AM",
    "AD", "RE", "CM", "PF", "RT", "Hb", "HB", "LHb", "MHb", "PVT", "POL"
]
THA_NAMES_EXACT = {"RT"}

# ---------- Hypothalamus（下丘脑）判定 ----------
HYP_PREFIXES = [
    "HY", "ARC", "ARH", "DMH", "DM", "LH", "LHA", "PVN", "PVH", "VMH", "VMN", "SON", "SO", "SCN", "MPO", "MPN",
    "PMv", "PMd", "AP"
]
HYP_NAMES_EXACT = {"ARC", "ARH", "DMH", "LH", "PVN", "PVH", "VMH", "SON", "SCN", "MPO", "MPN"}

# ============== 判定函数 ==============
def detect_annotation_key(headers):
    lower = {h.lower(): h for h in headers}
    return lower.get("annotationname") or lower.get("annotation")

def _starts_with_any(name: str, prefixes) -> bool:
    n = name.strip()
    for p in prefixes:
        if n.startswith(p):
            return True
    return False

def is_isocortex(name: str) -> bool:
    n = name.strip()
    return (n in ISO_NAMES) or _starts_with_any(n, ISO_PREFIXES)

def is_hip(name: str) -> bool:
    n = name.strip()
    return (n in HIP_NAMES_EXACT) or _starts_with_any(n, HIP_PREFIXES)

def is_amygdala(name: str) -> bool:
    n = name.strip()
    return (n in AMY_NAMES_EXACT) or _starts_with_any(n, AMY_PREFIXES)

def is_striatum(name: str) -> bool:
    n = name.strip()
    return (n in STR_NAMES_EXACT) or _starts_with_any(n, STR_PREFIXES)

def is_thalamus(name: str) -> bool:
    n = name.strip()
    return (n in THA_NAMES_EXACT) or _starts_with_any(n, THA_PREFIXES)

def is_hypothalamus(name: str) -> bool:
    n = name.strip()
    return (n in HYP_NAMES_EXACT) or _starts_with_any(n, HYP_PREFIXES)

def category_of(name: str) -> int:
    if is_isocortex(name):     return 0
    if is_hip(name):           return 1
    if is_amygdala(name):      return 2
    if is_striatum(name):      return 3
    if is_thalamus(name):      return 4
    if is_hypothalamus(name):  return 5
    return 6

# ============== 主流程（流式，低内存） ==============
def process_csv(in_path, out_path, alpha_sort_within=False, encoding="utf-8-sig"):
    with open(in_path, "r", newline="", encoding=encoding) as f:
        reader = csv.DictReader(f)
        headers = reader.fieldnames or []
        key = detect_annotation_key(headers)
        if not key:
            print(f"[跳过] {os.path.basename(in_path)} 没有 AnnotationName/Annotation 列")
            return

    tmpdir = tempfile.TemporaryDirectory(prefix="grp_ann_")
    first_seen_index = {}
    group_paths = {}
    group_category = {}
    total = 0
    next_index = 0

    with open(in_path, "r", newline="", encoding=encoding) as f:
        reader = csv.DictReader(f)
        headers = reader.fieldnames or []
        for row in reader:
            total += 1
            ann = (row.get(key) or "").strip()
            if ann not in group_paths:
                first_seen_index[ann] = next_index; next_index += 1
                group_category[ann] = category_of(ann)
                group_paths[ann] = os.path.join(tmpdir.name, f"group_{len(group_paths):06d}.csvpart")
                with open(group_paths[ann], "w", newline="", encoding="utf-8") as gf:
                    w = csv.DictWriter(gf, fieldnames=headers)
                    w.writeheader()
                    w.writerow(row)
            else:
                with open(group_paths[ann], "a", newline="", encoding="utf-8") as gf:
                    w = csv.DictWriter(gf, fieldnames=headers)
                    w.writerow(row)

    groups = list(group_paths.keys())
    if alpha_sort_within:
        groups.sort(key=lambda g: (group_category[g], g))
    else:
        groups.sort(key=lambda g: (group_category[g], first_seen_index[g]))

    with codecs.open(out_path, "w", "utf-8-sig") as out_f:
        writer = csv.writer(out_f, lineterminator="\n")
        writer.writerow(headers)
        for g in groups:
            path = group_paths[g]
            with open(path, "r", newline="", encoding="utf-8") as gf:
                r = csv.reader(gf)
                next(r, None)
                for row in r:
                    writer.writerow(row)

    tmpdir.cleanup()
    print(f"[完成] {os.path.basename(in_path)}（{total} 行）→ {os.path.basename(out_path)}")

def main():
    csv_files = [f for f in os.listdir(TARGET_DIR) if f.lower().endswith(".csv")]
    if not csv_files:
        print(f"在目录 {TARGET_DIR} 下没有找到 CSV 文件")
        return
    print(f"在目录 {TARGET_DIR} 找到 {len(csv_files)} 个 CSV 文件")
    for fname in csv_files:
        in_path = os.path.join(TARGET_DIR, fname)
        name, ext = os.path.splitext(fname)
        out_path = os.path.join(TARGET_DIR, f"{name}_sorted{ext}")
        process_csv(in_path, out_path, alpha_sort_within=ALPHA_SORT_WITHIN_CATEGORY, encoding=INPUT_ENCODING)

if __name__ == "__main__":
    main()
