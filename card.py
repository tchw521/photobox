"""卡片页引擎：叠放浏览 + 归类到相册

设计要点（按你的要求）：
  · 卡片页只做「叠放」展示，不加任何水印边框、滤镜、色彩模板
  · 页面的核心作用是把图片快速分类到不同相册（文件夹）
  · 所有动作都是真实的移动 / 复制文件，不是仅改索引
"""

from __future__ import annotations

import os
import shutil
from datetime import datetime
from pathlib import Path

# ---------------------------------------------------------------- 叠放布局
# 返回 [(dx, dy, 缩放, 旋转弧度, 透明度)]，索引 0 为最上层
STACK_LAYOUT = [
    (0.0, 0.0, 1.00, 0.000, 1.00),   # 顶层：当前待分类的照片
    (-0.06, 0.05, 0.94, -0.055, 0.92),
    (0.07, 0.08, 0.90, 0.060, 0.82),
    (-0.02, 0.13, 0.86, -0.020, 0.68),
    (0.04, 0.17, 0.82, 0.030, 0.52),
]


def stack_layers(count: int, visible: int = 5) -> list[tuple]:
    """按待处理数量返回叠放层参数（不足则截断）。"""
    n = max(1, min(visible, count))
    return STACK_LAYOUT[:n]


def rel_date(ts: float) -> str:
    d = datetime.fromtimestamp(ts)
    return d.strftime("%Y-%m-%d")


# ---------------------------------------------------------------- 归档动作
def unique_path(dest_dir: str, name: str) -> str:
    """若目标已存在同名文件，自动加后缀避免覆盖。"""
    p = Path(dest_dir) / name
    if not p.exists():
        return str(p)
    stem, suffix = Path(name).stem, Path(name).suffix
    i = 1
    while True:
        cand = Path(dest_dir) / f"{stem}_{i}{suffix}"
        if not cand.exists():
            return str(cand)
        i += 1


def classify(paths: list[str], dest_dir: str, mode: str = "move") -> tuple[int, list[str]]:
    """把照片归类到目标相册。mode: move | copy

    返回 (成功数, 失败的文件名列表)。
    """
    os.makedirs(dest_dir, exist_ok=True)
    ok, failed = 0, []
    for src in paths:
        if not os.path.isfile(src):
            failed.append(os.path.basename(src))
            continue
        dest = unique_path(dest_dir, os.path.basename(src))
        try:
            if mode == "copy":
                shutil.copy2(src, dest)
            else:
                shutil.move(src, dest)
            ok += 1
        except OSError:
            failed.append(os.path.basename(src))
    return ok, failed


def create_album(parent_dir: str, name: str) -> str:
    """在父目录下新建相册文件夹，返回完整路径。"""
    name = (name or "").strip()
    if not name:
        raise ValueError("相册名不能为空")
    p = Path(parent_dir) / name
    p.mkdir(parents=True, exist_ok=True)
    return str(p)


def album_dirs(roots: list[str], exclude: set[str] | None = None) -> list[tuple[str, int]]:
    """列出可作为归类目标的相册文件夹：[(名称, 图片数量)]。

    只取根目录下的一级子文件夹，避免把整个磁盘扫一遍。
    """
    from core import IMAGE_EXTS

    exclude = exclude or set()
    out: dict[str, int] = {}
    for root in roots:
        if not os.path.isdir(root):
            continue
        try:
            entries = sorted(os.listdir(root))
        except OSError:
            continue
        for e in entries:
            full = os.path.join(root, e)
            if not os.path.isdir(full) or e.startswith(".") or e in exclude:
                continue
            try:
                n = sum(1 for f in os.listdir(full)
                        if os.path.splitext(f)[1].lower() in IMAGE_EXTS)
            except OSError:
                n = 0
            out[full] = out.get(full, 0) + n
    # 排序：图片多的在前，其次按名称
    items = sorted(out.items(), key=lambda kv: (-kv[1], os.path.basename(kv[0])))
    return [(path, n) for path, n in items]


def suggest_parent(roots: list[str]) -> str:
    """新建相册时的默认父目录。"""
    for r in roots:
        if os.path.isdir(r):
            return r
    return str(Path.home())
