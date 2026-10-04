"""光影相册 PhotoBox - 核心数据层

负责：扫描图集、缩略图缓存、月份分组、回收站、屏蔽/收藏、配置持久化。
纯 Python，无 UI 依赖，方便单独测试。
"""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import sys
import tempfile
import threading
import time
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

IMAGE_EXTS = {
    ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp",
    ".heic", ".heif", ".avif", ".tif", ".tiff",
}

# Android 常见照片目录：覆盖主流厂商（小米/华为/OPPO/vivo/三星/一加/荣耀/真我）
_BASE_SDCARD = [
    "/storage/emulated/0",   # Android 10+ 标准路径
    "/sdcard",               # 兼容软链接
    "/mnt/sdcard",           # 部分旧机型
]
# 每个根目录下的子目录
_ANDROID_SUBDIRS = [
    "DCIM",                # 相机
    "DCIM/Camera",         # 小米/华为/三星 等相机目录
    "Pictures",            # 截图、应用保存
    "Pictures/Screenshots",
    "Pictures/WeiXin",     # 微信
    "Pictures/QQ",
    "Pictures/Weibo",
    "Download",            # 浏览器/下载
    "Downloads",
    "Android/media",       # 各应用媒体目录
    "Tencent/MicroMsg",    # 微信深层目录
    "Tencent/QQfile_recv",
    "Movies",
    "Camera",
    "MIUI/Gallery/cloud",  # 小米云相册
    "Huawei/MagazineUnlock",  # 华为杂志锁屏
    "oppo/camera",         # OPPO
    "vivo/Camera",         # vivo
]
DEFAULT_ROOTS_ANDROID = [
    f"{b}/{s}" for b in _BASE_SDCARD for s in _ANDROID_SUBDIRS
]
DEFAULT_ROOTS_DESKTOP = [
    str(Path.home() / "Pictures"),
    str(Path.home() / "Desktop"),
    str(Path.home() / "Downloads"),
    str(Path.home() / "Documents"),
]


def is_android() -> bool:
    return "ANDROID_STORAGE" in os.environ or os.path.exists("/system/build.prop")


def app_dir() -> Path:
    """返回一个可写的应用数据目录（Android / Windows 通用）。"""
    cands = []
    home = os.environ.get("HOME")
    if home:
        cands.append(Path(home) / ".photobox")
    cands.append(Path(sys.prefix) / ".photobox")
    cands.append(Path(tempfile.gettempdir()) / "photobox")
    for c in cands:
        try:
            c.mkdir(parents=True, exist_ok=True)
            probe = c / ".w"
            probe.write_text("1")
            probe.unlink()
            return c
        except Exception:
            continue
    return Path(tempfile.gettempdir()) / "photobox"


def _default_roots_impl() -> list[str]:
    """返回设备上真实存在的照片目录。

    安卓上会先探测标准路径，再按需补充厂商目录；只保留真实存在且有图片的目录，
    避免扫描一长串不存在的路径拖慢启动。
    """
    base = DEFAULT_ROOTS_ANDROID if is_android() else DEFAULT_ROOTS_DESKTOP
    exists = [p for p in base if os.path.isdir(p)]
    if is_android():
        # 去重（/sdcard 通常是 /storage/emulated/0 的软链）
        seen, uniq = set(), []
        for p in exists:
            try:
                rp = os.path.realpath(p)
            except OSError:
                continue
            if rp not in seen:
                seen.add(rp)
                uniq.append(p)
        # 只保留确实有图片的目录，减少无效扫描
        with_img = [p for p in uniq if _dir_has_images(p)]
        return with_img or uniq[:4]
    return exists or [str(Path.home())]


def default_roots() -> list[str]:
    """对外入口：设备上真实存在的照片目录。"""
    return _default_roots_impl()


def _dir_has_images(path: str, limit: int = 400) -> bool:
    """快速判断目录（含一层子目录）里是否有图片。"""
    try:
        for name in os.listdir(path)[:limit]:
            if os.path.splitext(name)[1].lower() in IMAGE_EXTS:
                return True
        for name in os.listdir(path)[:limit]:
            sub = os.path.join(path, name)
            if os.path.isdir(sub):
                for n2 in os.listdir(sub)[:50]:
                    if os.path.splitext(n2)[1].lower() in IMAGE_EXTS:
                        return True
    except OSError:
        return False
    return False


def month_of(ts: float) -> str:
    return datetime.fromtimestamp(ts).strftime("%Y-%m")


@dataclass
class Photo:
    path: str
    name: str
    album: str
    size: int
    mtime: float
    month: str

    @property
    def size_kb(self) -> int:
        return max(1, self.size // 1024)

    @property
    def date_str(self) -> str:
        return datetime.fromtimestamp(self.mtime).strftime("%Y-%m-%d %H:%M")


@dataclass
class TrashItem:
    id: str
    name: str
    orig: str
    album: str
    size: int
    trashed_at: float
    thumb: str = ""


class Library:
    """照片库：扫描、索引、清理、回收站。"""

    def __init__(self, data_dir: Path | None = None):
        self.dir = Path(data_dir) if data_dir else Path(app_dir())
        self.thumbs_dir = self.dir / "thumbs"
        self.trash_dir = self.dir / "trash"
        self.config_path = self.dir / "config.json"
        self.trash_path = self.dir / "trash.json"
        for d in (self.thumbs_dir, self.trash_dir):
            d.mkdir(parents=True, exist_ok=True)

        self.roots: list[str] = []
        self.blocked: set[str] = set()
        self.favorites: set[str] = set()
        self.view: str = "grid"           # grid | list
        self.thumb_size: int = 300
        self.auto_clean_days: int = 0     # 0 = 不自动清理
        self._load_config()

        self.photos: dict[str, Photo] = {}
        self.trash: list[TrashItem] = []
        self._load_trash()
        self._lock = threading.Lock()

    # ---------- 配置 ----------
    def _load_config(self) -> None:
        if self.config_path.exists():
            try:
                c = json.loads(self.config_path.read_text(encoding="utf-8"))
                self.roots = c.get("roots") or default_roots()
                self.blocked = set(c.get("blocked") or [])
                self.favorites = set(c.get("favorites") or [])
                self.view = c.get("view", "grid")
                self.thumb_size = int(c.get("thumb_size", 300))
                self.auto_clean_days = int(c.get("auto_clean_days", 0))
                return
            except Exception:
                pass
        self.roots = default_roots()

    def save_config(self) -> None:
        self.config_path.write_text(
            json.dumps(
                {
                    "roots": self.roots,
                    "blocked": sorted(self.blocked),
                    "favorites": sorted(self.favorites),
                    "view": self.view,
                    "thumb_size": self.thumb_size,
                    "auto_clean_days": self.auto_clean_days,
                },
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )

    # ---------- 回收站索引 ----------
    def _load_trash(self) -> None:
        if self.trash_path.exists():
            try:
                self.trash = [TrashItem(**t) for t in json.loads(self.trash_path.read_text(encoding="utf-8"))]
            except Exception:
                self.trash = []

    def _save_trash(self) -> None:
        from dataclasses import asdict

        self.trash_path.write_text(
            json.dumps([asdict(t) for t in self.trash], ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

    # ---------- 扫描 ----------
    def scan(self, roots: list[str] | None = None, progress=None) -> list[Photo]:
        """扫描根目录，建立索引。progress(step, total) 可选回调。"""
        if roots is not None:
            self.roots = [r for r in roots if r]
        found: dict[str, Photo] = {}
        skip_dirs = {".thumbnails", "thumbnails", ".photobox", "cache", ".cache", "trash", ".trash"}
        for root in self.roots:
            root = str(root)
            if not os.path.isdir(root):
                continue
            for dirpath, dirnames, filenames in os.walk(root):
                dirnames[:] = [
                    d for d in dirnames
                    if not d.startswith(".") and d.lower() not in skip_dirs
                ]
                album = os.path.basename(dirpath) or os.path.basename(root)
                if album in self.blocked or dirpath in self.blocked:
                    continue
                for fn in filenames:
                    ext = os.path.splitext(fn)[1].lower()
                    if ext not in IMAGE_EXTS:
                        continue
                    full = os.path.join(dirpath, fn)
                    try:
                        st = os.stat(full)
                    except OSError:
                        continue
                    found[full] = Photo(
                        path=full,
                        name=fn,
                        album=album,
                        size=st.st_size,
                        mtime=st.st_mtime,
                        month=month_of(st.st_mtime),
                    )
                    if progress and len(found) % 200 == 0:
                        progress(len(found))
        with self._lock:
            self.photos = found
        if progress:
            progress(len(found))
        return list(found.values())

    def add_root(self, path: str) -> None:
        if path and path not in self.roots:
            self.roots.append(path)
            self.save_config()

    def remove_root(self, path: str) -> None:
        if path in self.roots:
            self.roots.remove(path)
            self.save_config()

    # ---------- 视图数据 ----------
    def albums(self) -> list[tuple[str, int]]:
        """[(图集名, 数量)]，按数量降序。"""
        counts: dict[str, int] = {}
        for p in self.photos.values():
            counts[p.album] = counts.get(p.album, 0) + 1
        return sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))

    def months(self) -> list[tuple[str, int]]:
        counts: dict[str, int] = {}
        for p in self.photos.values():
            counts[p.month] = counts.get(p.month, 0) + 1
        return sorted(counts.items(), key=lambda kv: kv[0], reverse=True)

    def select(self, album: str = "ALL", month: str | None = None,
               query: str = "", favorites_only: bool = False,
               sort: str = "date_desc") -> list[Photo]:
        q = query.strip().lower()
        items = list(self.photos.values())
        if album != "ALL":
            items = [p for p in items if p.album == album]
        if month:
            items = [p for p in items if p.month == month]
        if favorites_only:
            items = [p for p in items if p.path in self.favorites]
        if q:
            items = [p for p in items if q in p.name.lower() or q in p.album.lower()]
        rev = sort.endswith("desc")
        if sort.startswith("date"):
            items.sort(key=lambda p: p.mtime, reverse=rev)
        elif sort.startswith("name"):
            items.sort(key=lambda p: p.name.lower(), reverse=rev)
        elif sort.startswith("size"):
            items.sort(key=lambda p: p.size, reverse=rev)
        return items

    # ---------- 缩略图 ----------
    def thumb_path(self, path: str) -> Path:
        key = hashlib.md5(path.encode("utf-8", "ignore")).hexdigest()
        return self.thumbs_dir / f"{key}.jpg"

    def cached_thumb(self, path: str) -> str | None:
        p = self.thumb_path(path)
        return str(p) if p.exists() else None

    def make_thumb(self, path: str) -> str | None:
        """生成缩略图（若已缓存直接返回）。失败返回 None。"""
        cached = self.cached_thumb(path)
        if cached:
            return cached
        out = self.thumb_path(path)
        try:
            from PIL import Image, ImageOps

            with Image.open(path) as im:
                im = ImageOps.exif_transpose(im)
                im.thumbnail((self.thumb_size, self.thumb_size))
                if im.mode not in ("RGB", "L"):
                    im = im.convert("RGB")
                im.save(out, "JPEG", quality=82)
            return str(out)
        except Exception:
            return None

    def clear_thumb_cache(self) -> int:
        n = 0
        for f in self.thumbs_dir.glob("*.jpg"):
            try:
                f.unlink()
                n += 1
            except OSError:
                pass
        return n

    # ---------- 屏蔽 / 收藏 ----------
    def block_album(self, album: str) -> None:
        self.blocked.add(album)
        self.save_config()

    def unblock_album(self, album: str) -> None:
        self.blocked.discard(album)
        self.save_config()

    def toggle_favorite(self, path: str) -> bool:
        if path in self.favorites:
            self.favorites.discard(path)
            self.save_config()
            return False
        self.favorites.add(path)
        self.save_config()
        return True

    # ---------- 回收站 ----------
    def reindex(self, paths: list[str], dest_dir: str | None = None) -> None:
        """照片被移动到别处后，从当前索引移除；若目标在扫描根内则补进索引。"""
        moved: dict[str, str] = {}
        for p in paths:
            old = self.photos.get(p)
            if not old:
                self.photos.pop(p, None)
                continue
            if dest_dir:
                new = os.path.join(dest_dir, old.name)
                if os.path.exists(new):
                    moved[new] = old
            self.photos.pop(p, None)
        for new, old in moved.items():
            try:
                st = os.stat(new)
            except OSError:
                continue
            self.photos[new] = Photo(
                path=new, name=old.name, album=os.path.basename(dest_dir) or old.album,
                size=st.st_size, mtime=st.st_mtime, month=month_of(st.st_mtime),
            )
        self.favorites = {p for p in self.favorites if p in self.photos}

    def move_to_trash(self, paths: list[str]) -> int:
        n = 0
        for path in paths:
            p = self.photos.get(path)
            if not p and not os.path.exists(path):
                continue
            name = p.name if p else os.path.basename(path)
            album = p.album if p else os.path.basename(os.path.dirname(path))
            size = p.size if p else (os.path.getsize(path) if os.path.exists(path) else 0)
            tid = hashlib.md5((path + str(time.time())).encode()).hexdigest()[:12]
            dest = self.trash_dir / f"{tid}_{name}"
            try:
                shutil.move(path, dest)
            except OSError:
                continue
            thumb = self.cached_thumb(path) or ""
            self.trash.append(
                TrashItem(id=tid, name=name, orig=path, album=album,
                          size=size, trashed_at=time.time(), thumb=thumb)
            )
            self.photos.pop(path, None)
            n += 1
        if n:
            self._save_trash()
        return n

    def restore(self, ids: list[str]) -> int:
        n = 0
        for tid in ids:
            item = next((t for t in self.trash if t.id == tid), None)
            if not item:
                continue
            src = self.trash_dir / f"{tid}_{item.name}"
            if not src.exists():
                self.trash.remove(item)
                continue
            dest = Path(item.orig)
            try:
                dest.parent.mkdir(parents=True, exist_ok=True)
                if dest.exists():
                    dest = dest.with_name(f"{dest.stem}_restored{dest.suffix}")
                shutil.move(str(src), str(dest))
            except OSError:
                continue
            self.trash.remove(item)
            self.photos[item.orig] = Photo(
                path=item.orig, name=item.name, album=item.album,
                size=item.size, mtime=time.time(), month=month_of(time.time()),
            )
            n += 1
        if n:
            self._save_trash()
        return n

    def delete_forever(self, ids: list[str]) -> int:
        n = 0
        for tid in list(ids):
            item = next((t for t in self.trash if t.id == tid), None)
            if not item:
                continue
            src = self.trash_dir / f"{tid}_{item.name}"
            try:
                if src.exists():
                    src.unlink()
            except OSError:
                pass
            self.trash.remove(item)
            n += 1
        if n:
            self._save_trash()
        return n

    def empty_trash(self) -> int:
        return self.delete_forever([t.id for t in self.trash])

    def auto_clean(self) -> int:
        if self.auto_clean_days <= 0:
            return 0
        limit = time.time() - self.auto_clean_days * 86400
        old = [t.id for t in self.trash if t.trashed_at < limit]
        return self.delete_forever(old)

    def sorted_trash(self, sort: str = "time_desc") -> list[TrashItem]:
        rev = sort.endswith("desc")
        if sort.startswith("name"):
            return sorted(self.trash, key=lambda t: t.name.lower(), reverse=rev)
        if sort.startswith("size"):
            return sorted(self.trash, key=lambda t: t.size, reverse=rev)
        if sort.startswith("album"):
            return sorted(self.trash, key=lambda t: t.album.lower(), reverse=rev)
        return sorted(self.trash, key=lambda t: t.trashed_at, reverse=rev)

    def trash_albums(self) -> list[tuple[str, int]]:
        """回收站里的图集分布，用于「回收站图集排序」筛选。"""
        counts: dict[str, int] = {}
        for t in self.trash:
            counts[t.album] = counts.get(t.album, 0) + 1
        return sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))

    def select_trash(self, album: str = "ALL", sort: str = "time_desc") -> list[TrashItem]:
        items = self.trash if album == "ALL" else [t for t in self.trash if t.album == album]
        return self.sorted_trash(sort) if album == "ALL" else self.sorted_trash(sort)

    # ---------- 时间线（集邮式：按日期分组） ----------
    def timeline(self, photos: list[Photo] | None = None) -> list[tuple[str, list[Photo]]]:
        """[(日期 YYYY-MM-DD, [照片])]，按日期倒序。"""
        items = photos if photos is not None else list(self.photos.values())
        buckets: dict[str, list[Photo]] = {}
        for p in items:
            d = datetime.fromtimestamp(p.mtime).strftime("%Y-%m-%d")
            buckets.setdefault(d, []).append(p)
        for v in buckets.values():
            v.sort(key=lambda p: p.mtime, reverse=True)
        return sorted(buckets.items(), key=lambda kv: kv[0], reverse=True)

    def created_dir(self) -> str:
        """创作（水印输出）目录。"""
        d = self.dir / "创作"
        d.mkdir(parents=True, exist_ok=True)
        return str(d)

    def created_photos(self) -> list[Photo]:
        """扫描创作目录里的水印成品。"""
        out = []
        d = self.created_dir()
        for fn in sorted(os.listdir(d), reverse=True):
            if os.path.splitext(fn)[1].lower() not in IMAGE_EXTS:
                continue
            full = os.path.join(d, fn)
            st = os.stat(full)
            out.append(Photo(path=full, name=fn, album="创作", size=st.st_size,
                             mtime=st.st_mtime, month=month_of(st.st_mtime)))
        return out

    def trash_size(self) -> int:
        return sum(t.size for t in self.trash)

    def total_size(self) -> int:
        return sum(p.size for p in self.photos.values())
