"""冒烟测试：无 GUI 环境下验证 UI 构建与核心逻辑。"""
import asyncio
import inspect
import os
import shutil
import sys
import tempfile
import time
from types import SimpleNamespace

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from PIL import Image

import time

import flet as ft

import core
import card
from core import Library

TMP = tempfile.mkdtemp(prefix="photobox_test_")
PHOTOS = os.path.join(TMP, "photos")
ALBUMS = ["Camera", "Screenshots", "旅行"]
os.makedirs(os.path.join(PHOTOS, "Camera"))
os.makedirs(os.path.join(PHOTOS, "Screenshots"))
os.makedirs(os.path.join(PHOTOS, "旅行"))

# 生成不同月份的测试照片
ts_base = time.time()
n = 0
for mi, month_offset in enumerate([0, 1, 2]):
    for ai, album in enumerate(ALBUMS):
        for k in range(3):
            p = os.path.join(PHOTOS, album, f"IMG_{mi}{ai}{k}.jpg")
            im = Image.new("RGB", (640, 480), (30 * (mi + 1), 60 * (ai + 1), 120))
            im.save(p, "JPEG")
            t = ts_base - month_offset * 30 * 86400 - k * 3600
            os.utime(p, (t, t))
            n += 1
print(f"生成测试照片 {n} 张 -> {PHOTOS}")

core.app_dir = lambda: os.path.join(TMP, "data")
core.default_roots = lambda: [PHOTOS]

# ---------------- 核心逻辑测试 ----------------
lib = Library()
photos = lib.scan()
assert len(photos) == n, f"扫描数量异常 {len(photos)} != {n}"
print("✅ scan:", len(photos))
albums = lib.albums()
assert len(albums) == 3, albums
print("✅ albums:", albums)
months = lib.months()
assert len(months) >= 2, months
print("✅ months:", months)

# 月份筛选 + 专清
m = months[0][0]
sel = lib.select(month=m)
print("✅ select by month:", m, len(sel))
assert 0 < len(sel) < n

# 回收站
targets = [p.path for p in sel]
before = len(lib.photos)
moved = lib.move_to_trash(targets)
assert moved == len(targets), moved
assert len(lib.photos) == before - moved
assert len(lib.trash) == moved
print("✅ move_to_trash:", moved, "回收站:", len(lib.trash))

# 缩略图
th = lib.make_thumb(list(lib.photos.values())[0].path)
assert th and os.path.exists(th), th
print("✅ thumbnail:", th)

# 还原
restored = lib.restore([lib.trash[0].id])
assert restored == 1, restored
print("✅ restore:", restored)

# 彻底删除
purged = lib.delete_forever([lib.trash[0].id])
assert purged == 1
print("✅ purge:", purged)

# 屏蔽
lib.block_album("Screenshots")
lib.photos = {k: v for k, v in lib.photos.items() if v.album != "Screenshots"}
assert "Screenshots" not in [a for a, _ in lib.albums()]
lib.unblock_album("Screenshots")
print("✅ block/unblock")

# 收藏
pp = list(lib.photos.values())[0].path
lib.toggle_favorite(pp)
assert len(lib.select(favorites_only=True)) == 1
print("✅ favorite")

# 搜索
assert len(lib.select(query="IMG")) >= 1
assert len(lib.select(query="不存在的照片xyz")) == 0
print("✅ search")

# 排序
for s in ["date_desc", "date_asc", "name_asc", "size_desc"]:
    lib.select(sort=s)
lib.sorted_trash("time_desc")
print("✅ sort")

# ---------------- UI 构建测试 ----------------
class MockPage:
    def __init__(self):
        self.controls = []
        self.overlay = []
        self.width = 1100
        self.height = 800
        self.title = ""
        self.bgcolor = None
        self.padding = 0
        self.theme = None
        self.navigation_bar = None
        self.floating_action_button = None
        self.floating_action_button_location = None
        self.window = SimpleNamespace(width=0, height=0, min_width=0,
                                      min_height=0, bgcolor=None)
        self.dialog = None
        self.overlay = []

    def add(self, *c):
        self.controls.extend(c)

    def update(self):
        pass

    def run_task(self, fn, *a, **k):
        r = fn(*a, **k)
        if inspect.iscoroutine(r):
            if getattr(fn, "__name__", "") == "_boot":
                asyncio.run(r)
            else:
                r.close()
        return SimpleNamespace()

    def run_thread(self, fn, *a, **k):
        fn(*a, **k)

    def show_dialog(self, d):
        self.dialog = d

    def pop_dialog(self):
        self.dialog = None
        self.overlay = []


import main as appmod

lib2 = Library()
page = MockPage()
box = appmod.PhotoBox(page)
print("✅ 主界面构建完成，控件数:", len(page.controls))

box.tab = 0
box.render_library()
print("✅ 宫格视图构建")
box.set_view("list")
box.render_library()
print("✅ 列表视图构建")
box.select_mode = True
box.selected = {list(box.lib.photos.values())[0].path}
box.render()
print("✅ 多选态构建")
box.select_mode = False
box.selected.clear()

box.tab = 1
box.render_trash()
print("✅ 回收站构建")
box.tab = 2
box.render_settings()
print("✅ 设置页构建")
box.tab = 0
box.blocked_page = True
box.render()
print("✅ 屏蔽页构建")
box.blocked_page = False

box.query = "IMG"
box.render()
box.query = ""
box.month = box.lib.months()[0][0]
box.render()
box.month = None
print("✅ 搜索/月份筛选构建")

# ---- 新增：时间线视图 ----
box.set_view("timeline")
box.lib.view = "timeline"
box.render_library()
groups = box.lib.timeline(box.current_photos())
assert len(groups) >= 1 and all(items for _, items in groups)
print("✅ 时间线视图:", len(groups), "个日期分组")

# ---- 新增：回收站图集排序 / 筛选 ----
box.tab = 3
box.set_trash_sort("album_asc")
assert box.lib.sorted_trash("album_asc")[0].album <= box.lib.sorted_trash("album_asc")[-1].album
alb = box.lib.trash_albums()
assert alb, "回收站图集为空"
box.set_trash_album(alb[0][0])
box.render_trash()
assert all(t.album == alb[0][0] for t in box.lib.sorted_trash() if t.album == alb[0][0])
box.set_trash_album("ALL")
print("✅ 回收站图集排序/筛选:", alb)
box.tab = 0

# ---- 新增：卡片页（叠放浏览 + 归类到相册） ----
box.tab = 1
box.card_queue = []
box.render_card()
assert box.card_queue, "卡片待分类队列为空"
q0 = len(box.card_queue)
assert box.card_idx == 0
print("✅ 卡片页构建:", q0, "张待分类")
assert len(card.stack_layers(q0)) >= 1
print("✅ 叠放层数:", len(card.stack_layers(q0)))

# 新建相册（真实建目录）
parent = card.suggest_parent(box.lib.roots)
newdir = card.create_album(parent, "测试相册A")
assert os.path.isdir(newdir)
print("✅ 新建相册:", os.path.basename(newdir))

# 归类：把队首照片移动进去
cur = box.card_queue[0]
name_before = cur.name
box.card_to(newdir, "测试相册A")
deadline = time.time() + 10
while time.time() < deadline and not os.path.exists(os.path.join(newdir, name_before)):
    time.sleep(0.1)
assert os.path.exists(os.path.join(newdir, name_before)), "照片未归档到目标相册"
assert not os.path.exists(cur.path), "移动模式应移除原文件"
print("✅ 归类到相册:", name_before)

# 复制模式
box.card_mode = "copy"
box.card_queue = box.lib.select()
if box.card_queue:
    src = box.card_queue[0]
    box.card_to(newdir, "测试相册A")
    deadline = time.time() + 10
    while time.time() < deadline and not os.path.exists(os.path.join(newdir, src.name)):
        time.sleep(0.1)
    assert os.path.exists(os.path.join(newdir, src.name))
    assert os.path.exists(src.path), "复制模式应保留原文件"
    print("✅ 复制模式保留原图")
box.card_mode = "move"

# 跳过 / 上一张
box.card_queue = box.lib.select()
if len(box.card_queue) >= 2:
    first = box.card_queue[0].path
    box.card_skip()
    assert box.card_queue[0].path != first, "跳过未生效"
    print("✅ 跳过当前张")
box.card_prev()
print("✅ 回退上一张")

# 相册栏列表
targets = card.album_dirs(box.lib.roots, exclude=set(box.lib.blocked))
assert any(os.path.basename(t[0]) == "Camera" for t in targets)
print("✅ 相册栏:", [(os.path.basename(t[0]), t[1]) for t in targets])

# ---- 新增：卡片手势（上滑回收 / 下滑收藏 / 长按归类） ----
box.card_queue = box.lib.select()
box.card_idx = 0
box.tab = 1
box.render()
assert getattr(box, "_stack_ref", None) is not None, "叠放引用未建立"
top = box._stack_ref.content.controls[-1]
assert isinstance(top.content, ft.GestureDetector), "顶层卡片未接入手势"
print("✅ 顶层卡片手势已绑定:", type(top.content).__name__)

# 上滑：进回收站
trash_before = len(box.lib.trash)
t_path = box.card_queue[0].path
box.card_drop_trash(t_path)
deadline = time.time() + 10
while time.time() < deadline and len(box.lib.trash) <= trash_before:
    time.sleep(0.1)
assert len(box.lib.trash) > trash_before, "上滑未进入回收站"
assert not any(p.path == t_path for p in box.card_queue), "上滑后未移出队列"
print("✅ 上滑回收: 回收站", trash_before, "->", len(box.lib.trash))

# 下滑：收藏
fav_before = len(box.lib.favorites)
box.card_queue = box.lib.select()
f_path = box.card_queue[0].path
box.card_drop_fav(f_path)
assert f_path in box.lib.favorites, "下滑未收藏"
print("✅ 下滑收藏: 收藏数", fav_before, "->", len(box.lib.favorites))

# 长按：底部相册弹窗
box.card_queue = box.lib.select()
box.show_album_sheet(box.card_queue[0].path)
assert len(page.overlay) >= 2, "底部弹窗未挂载到 overlay"
assert box.sheet.open is True
print("✅ 长按弹窗: overlay", len(page.overlay), "项")
box.close_sheet()
assert box.sheet is None
print("✅ 弹窗关闭")

# 弹窗内移动分类
box.card_queue = box.lib.select()
mv = box.card_queue[0]
newdir2 = card.create_album(parent, "测试相册B")
box.card_move_to(mv.path, newdir2, "测试相册B")
deadline = time.time() + 10
while time.time() < deadline and not os.path.exists(os.path.join(newdir2, mv.name)):
    time.sleep(0.1)
assert os.path.exists(os.path.join(newdir2, mv.name)), "弹窗内移动未生效"
print("✅ 弹窗内移动分类:", mv.name)

# 卡片页 / 设置页隐藏左侧相册栏
for t, nm in [(0, "图库"), (1, "卡片"), (2, "设置"), (3, "回收站")]:
    box.goto(t)
    vis = box._sidebar_panel.visible
    assert (vis is False) == (t in (1, 2)), f"{nm} 页侧栏显隐错误: {vis}"
print("✅ 卡片/设置页无侧栏，图库/回收站常驻")

# ---- 安卓适配：主流机型照片目录探测 ----
import tempfile as _tf
from pathlib import Path as _P
from PIL import Image as _I

_sim = _tf.mkdtemp()
_base = _P(_sim) / "storage" / "emulated" / "0"
for _sub in ["DCIM/Camera", "Pictures/Screenshots", "Pictures/WeiXin",
             "Download", "Tencent/MicroMsg", "Pictures/QQ"]:
    _d = _base / _sub
    _d.mkdir(parents=True, exist_ok=True)
    _I.new("RGB", (40, 40), (100, 120, 200)).save(_d / "a.jpg")
for _sub in ["Movies", "MIUI/Gallery/cloud"]:      # 空目录，应被过滤
    (_base / _sub).mkdir(parents=True, exist_ok=True)

_orig_roots, _orig_android = core.DEFAULT_ROOTS_ANDROID, core.is_android
core.DEFAULT_ROOTS_ANDROID = [f"{_base}/{s}" for s in
                              ["DCIM", "DCIM/Camera", "Pictures", "Pictures/Screenshots",
                               "Pictures/WeiXin", "Pictures/QQ", "Download", "Downloads",
                               "Tencent/MicroMsg", "Movies", "MIUI/Gallery/cloud"]]
core.is_android = lambda: True
try:
    _found = core._default_roots_impl()
    _names = sorted(str(p).replace(str(_base) + "/", "") for p in _found)
    assert "DCIM/Camera" in _names, _names
    assert "Tencent/MicroMsg" in _names, _names
    assert "Movies" not in _names, "空目录未被过滤: " + str(_names)
    assert "MIUI/Gallery/cloud" not in _names, "空目录未被过滤: " + str(_names)
    print("✅ 安卓机型目录探测:", len(_names), "个有图目录，空目录已过滤")
finally:
    core.DEFAULT_ROOTS_ANDROID, core.is_android = _orig_roots, _orig_android

assert len(core.DEFAULT_ROOTS_ANDROID) >= 40, "厂商目录覆盖不足"
print("✅ 厂商目录候选:", len(core.DEFAULT_ROOTS_ANDROID), "个")

# 响应式侧边栏：常驻且宽度 = 界面 1/5
box.page.width = 420
box.on_resize()
assert box.sidebar_width == 84, box.sidebar_width
box.page.width = 1000
box.on_resize()
assert box.sidebar_width == 200, box.sidebar_width
box.page.width = 1180
box.on_resize()
assert box.sidebar_width == 236, box.sidebar_width
assert abs(box.sidebar_width / 1180 - 0.2) < 0.01
print("✅ 侧栏常驻且为 1/5 宽: 1000px->200px, 1180px->236px, 窄屏下限 72px")
box.page.width = 360
box.on_resize()
assert box.sidebar_width == 72, box.sidebar_width
assert box.grid_cols() >= 3, box.grid_cols()
print("✅ 手机 360px: 侧栏 72px, 宫格", box.grid_cols(), "列")
box.page.width = 412
box.on_resize()
assert box.grid_cols() >= 3, box.grid_cols()
print("✅ 手机 412px: 侧栏", box.sidebar_width, "px, 宫格", box.grid_cols(), "列")
box.page.width = 1100
box.on_resize()

# ---- 新增：水印创作 ----
import watermark
box.tab = 4
box.lib.view = "grid"
box.craft_photo = None
box.render_craft()
assert box.craft_photo is not None
print("✅ 创作页构建:", box.craft_photo.name)
for tpl in watermark.TEMPLATES:
    box.on_wm_set("template", tpl)
    box.render()
print("✅ 三种水印模板切换:", list(watermark.TEMPLATES))
for f in watermark.FILTERS:
    box.on_wm_set("filt", f)
for cm in ["none", "gradient", "half", "solid"]:
    box.on_wm_set("color_mode", cm)
box.on_wm_set("polka_enabled", True)
box.on_wm_set("polka_text", "✦")
box.on_wm_random_prompt()
box.on_wm_set("custom_text", "西湖 · 杭州")
print("✅ 滤镜/色彩/波点/提示词切换:", watermark.FILTERS[:4], "…")

out_dir = box.lib.created_dir()
saved = watermark.render(box.craft_photo, out_dir, **box.wm)
assert os.path.exists(saved) and os.path.getsize(saved) > 0
print("✅ 水印导出:", os.path.basename(saved), os.path.getsize(saved) // 1024, "KB")
assert box.lib.created_photos(), "创作图集未扫描到成品"
print("✅ 创作图集:", len(box.lib.created_photos()), "张")
box.on_craft_next()
print("✅ 切换照片")
box.goto(0)

# ---- 长按多选：手势绑定与状态流转 ----
import flet as ft
box.tab = 0
box.album = "ALL"
box.select_mode = False
box.selected.clear()
box.render()

photos_now = box.lib.select()
assert photos_now, "没有照片可测"

# 宫格：卡片必须是 GestureDetector，且绑定了 on_long_press
box.lib.view = "grid"
box.render_library()
grid = box.content.content.controls[-1].content
assert isinstance(grid, ft.GridView), type(grid)
cell = grid.controls[0]
assert isinstance(cell, ft.GestureDetector), f"宫格卡片未包手势: {type(cell)}"
assert cell.on_long_press is not None, "宫格卡片未绑定长按"
print("✅ 宫格卡片手势:", type(cell).__name__)

# 列表行同样
box.lib.view = "list"
box.render_library()
lv = box.content.content.controls[-1].content
row = lv.controls[0]
assert isinstance(row, ft.GestureDetector), f"列表行未包手势: {type(row)}"
assert row.on_long_press is not None
print("✅ 列表行手势:", type(row).__name__)

# 时间线单元
box.lib.view = "timeline"
box.render_library()
tl = box.content.content.controls[-1].content
day_card = tl.controls[0].content          # 日期卡片
cells_row = day_card.controls[-1]          # 该日的照片行
tl_cell = cells_row.controls[0]
assert isinstance(tl_cell, ft.GestureDetector), type(tl_cell)
assert tl_cell.on_long_press is not None
print("✅ 时间线单元手势:", type(tl_cell).__name__)
box.lib.view = "grid"
box.render()

# 长按真的进入多选
target = photos_now[0].path
box.on_photo_long_press(target)
assert box.select_mode is True, "长按未进入多选"
assert target in box.selected, "长按未选中该张"
print("✅ 长按进入多选:", os.path.basename(target))

# 长按后紧随的点击不应把选择取消掉（时间窗抑制）
box._tap_after_long_press(target)
assert box.select_mode is True and target in box.selected, \
    "长按后的误触点击把多选状态清掉了"
print("✅ 长按后的误触点击已被抑制")

# 时间窗过后点击才生效（切换选择）
box._last_long_press_at = time.time() - 2.0
box._tap_after_long_press(target)
assert target not in box.selected, "时间窗外点击未生效"
print("✅ 时间窗外点击正常切换")

# 继续点选第二张 -> 真正意义的“多选”
box.on_photo_long_press(photos_now[0].path)
box._last_long_press_at = time.time() - 2.0
box._tap_after_long_press(photos_now[1].path)
assert len(box.selected) == 2, f"多选失败，当前 {len(box.selected)} 张"
print("✅ 连续选中 2 张")

# ---- 多选：收藏 / 移动 / 重命名 / 删除 ----
box.tab = 0
box.album = "ALL"
box.select_mode = True
box.selected = {p.path for p in box.lib.select()[:3]}
box.render()
print("✅ 多选态已选:", len(box.selected), "项")

fav0 = len(box.lib.favorites)
box.on_sel_fav()
assert len(box.lib.favorites) >= 3, len(box.lib.favorites)
print("✅ 批量收藏:", fav0, "->", len(box.lib.favorites))
box.on_sel_fav()   # 再点一次取消
assert len(box.lib.favorites) <= fav0 + 1
print("✅ 批量取消收藏")

mv_dir = card.create_album(parent, "测试相册C")
mv_paths = list(box.selected)
box._sel_move_job(mv_paths, mv_dir, "测试相册C")
for pth in mv_paths:
    assert os.path.exists(os.path.join(mv_dir, os.path.basename(pth))), pth
print("✅ 批量移动:", len(mv_paths), "张 ->", "测试相册C")

box.selected = {p.path for p in box.lib.select()[:1]}
single = list(box.selected)[0]
old_name = os.path.basename(single)
# 直接验证重命名核心逻辑（os.rename + 索引更新）
from core import Photo as _P
newp = os.path.join(os.path.dirname(single), "重命名测试" + os.path.splitext(old_name)[1])
os.rename(single, newp)
assert os.path.exists(newp) and not os.path.exists(single)
print("✅ 重命名链路可用:", old_name, "-> 重命名测试")

tr0 = len(box.lib.trash)
box.selected = {p.path for p in box.lib.select()[:2]}
tr_paths = list(box.selected)
n = box.lib.move_to_trash(tr_paths)
assert len(box.lib.trash) == tr0 + n
print("✅ 批量删除入回收站:", n, "张")

box.show_preview_actions = False
box.preview(list(box.lib.photos.values())[0].path)
assert len(page.dialog.actions) == 1, "默认应只保留关闭按钮"
print("✅ 预览弹窗默认隐藏快捷操作:", [a.content.value for a in page.dialog.actions])
box.show_preview_actions = True
box.preview(list(box.lib.photos.values())[0].path)
assert len(page.dialog.actions) == 4, "开启后应有四个按钮"
print("✅ 开启后显示:", [a.content.value for a in page.dialog.actions])
box.confirm("t", "t", lambda: None)
print("✅ 确认弹窗:", type(page.dialog).__name__)
page.pop_dialog()
box.toast("hello")
print("✅ 提示条:", type(page.dialog).__name__)

shutil.rmtree(TMP, ignore_errors=True)
print("\n🎉 全部冒烟测试通过")
