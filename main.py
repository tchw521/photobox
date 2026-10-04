"""光影相册 PhotoBox

一套 Python(Flet/Flutter) 代码，同时产出 Android APK 与 Windows EXE：
    flet build apk      -> 安卓安装包
    flet build windows  -> Windows 可执行文件
"""

from __future__ import annotations

import asyncio
import os
import time
import queue
import threading
from pathlib import Path

import flet as ft

from core import Library, Photo, is_android, month_of
import card
import watermark
from theme import (
    ACCENT, ACCENT_SOFT, BG_GRADIENT, DANGER, GLASS, GLASS_STRONG,
    RADIUS, STROKE, TEXT, TEXT_DIM, chip, empty_hint, fmt_size, glass_card, hbox,
    icon_btn, label, sidebar_item, stat_tile, title, vbox,
    clear_marquee, MARQUEE_ROWS,
)

APP_NAME = "光影相册"
APP_ID = "cn.photobox.app"
VERSION = "1.0.0"


class PhotoBox:
    def __init__(self, page: ft.Page):
        self.page = page
        self.lib = Library()

        # UI 状态
        self.album = "ALL"            # ALL | FAV | 具体图集名
        self.month: str | None = None
        self.query = ""
        self.sort = "date_desc"
        self.tab = 0                  # 0 图库 1 卡片 2 设置 | 3 回收站 4 创作（侧边栏入口）
        # 卡片页：叠放浏览 + 归类到相册
        self.card_queue: list[Photo] = []
        self.card_idx = 0
        self.card_mode = "move"       # move | copy
        self.card_done = 0
        self.sidebar_width = 168        # 启动时按界面 1/5 重算
        self.select_mode = False
        self.selected: set[str] = set()
        self.trash_sort = "time_desc"
        self.trash_album = "ALL"      # 回收站图集筛选（TabulaV3：回收站图集排序）
        self.trash_selected: set[str] = set()
        # 创作（水印）状态
        self.craft_photo: Photo | None = None
        self.wm = {"template": "glass", "filt": "原图", "color_mode": "gradient",
                   "color1": "#A855F7", "color2": "#22D3EE",
                   "prompt": "", "custom_text": "", "polka_text": "", "polka_enabled": False}
        self.blocked_page = False     # 侧栏是否显示“已屏蔽”面板
        self.show_preview_actions = False   # 预览弹窗是否显示快捷操作
        self.scanning = False

        # 缩略图异步管线
        self._todo: "queue.Queue[str]" = queue.Queue()
        self._done: "queue.Queue[tuple[str, str]]" = queue.Queue()
        self._targets: dict[str, list[ft.Image]] = {}
        threading.Thread(target=self._thumb_worker, daemon=True).start()

        self._setup_page()
        self._build_shell()
        self.page.run_task(self._drain_thumbs)
        self.page.run_task(self._boot)
        self.page.run_task(self._marquee_loop)

    async def _marquee_loop(self):
        """侧边栏长相册名自动左右滚动（跑马灯）。"""
        import asyncio
        while True:
            await asyncio.sleep(0.12)
            if not MARQUEE_ROWS:
                continue
            dirty = False
            for row in MARQUEE_ROWS:
                data = row.data
                if not isinstance(data, dict):
                    continue
                max_pos = max(0.0, data["width"] - 46.0)   # 容器可视宽度约 46px
                if max_pos <= 0:
                    continue
                data["pos"] += data["dir"] * 1.6
                if data["pos"] >= max_pos:
                    data["pos"] = max_pos
                    data["dir"] = -1
                elif data["pos"] <= 0:
                    data["pos"] = 0.0
                    data["dir"] = 1
                row.scroll_to(offset=data["pos"], delta=0, duration=0)
                dirty = True
            if dirty:
                try:
                    self.page.update()
                except Exception:
                    pass

    # ------------------------------------------------------------------ 页面骨架
    def _setup_page(self) -> None:
        p = self.page
        p.title = f"{APP_NAME} PhotoBox"
        p.bgcolor = "#160A26"
        p.padding = 0
        p.theme = ft.Theme(color_scheme_seed=ACCENT, use_material3=True)
        # 安卓：让内容避开状态栏/刘海/底部手势条
        try:
            p.auto_scroll = False
        except Exception:
            pass
        if is_android():
            self._apply_android_safe_area()
        try:
            if not is_android():
                p.window.width = 1180
                p.window.height = 780
                p.window.min_width = 420
                p.window.min_height = 620
            p.window.bgcolor = "#160A26"
        except Exception:
            pass

    def _apply_android_safe_area(self):
        """安卓沉浸式布局：底部预留导航栏/手势条高度，顶部避开状态栏。"""
        try:
            pad = ft.Padding(0, 0, 0, 0)
            # 通过平台调度器读取系统栏高度（Chaquo/Flet 提供）
            try:
                from android.view import View  # type: ignore
                from android.os import Build  # type: ignore
            except Exception:
                View = None
            inset_bottom = 0
            inset_top = 0
            try:
                from android import activity  # type: ignore
                act = activity
                if act is not None:
                    root = act.getWindow().getDecorView()
                    if hasattr(root, "getRootWindowInsets"):
                        insets = root.getRootWindowInsets()
                        if insets is not None:
                            try:
                                from android.graphics import Insets  # type: ignore
                                sysb = insets.getInsets(
                                    Insets.Type.systemBars() | Insets.Type.displayCutout())
                                inset_bottom = int(sysb.bottom)
                                inset_top = int(sysb.top)
                            except Exception:
                                pass
            except Exception:
                pass
            self.safe_top = max(0, inset_top)
            self.safe_bottom = max(12, inset_bottom)   # 手势条兜底 12dp
        except Exception:
            self.safe_top, self.safe_bottom = 0, 12

    @property
    def is_phone(self) -> bool:
        w = self.page.width or 420
        return w < 600

    def _build_shell(self) -> None:
        p = self.page
        self.sidebar = ft.Column(scroll=ft.ScrollMode.AUTO, expand=True)
        self.toolbar = ft.Column(spacing=8)
        self.content = ft.Container(expand=True, alignment=ft.Alignment(0, 0))
        self.status = ft.Text("", size=11, color=TEXT_DIM)

        sidebar_panel = ft.Container(
            content=ft.Column(
                controls=[
                    ft.Container(
                        content=ft.Row(
                            controls=[
                                ft.Icon(ft.Icons.PHOTO_LIBRARY_ROUNDED, color=ACCENT, size=18),
                                ft.Text("相册分类", size=13, color=TEXT, weight=ft.FontWeight.W_600),
                            ],
                            spacing=6,
                        ),
                        padding=ft.Padding(12, 12, 12, 8),
                    ),
                    ft.Container(content=self.sidebar, expand=True,
                                 padding=ft.Padding(8, 0, 8, 12)),
                ],
                expand=True,
            ),
            width=self.sidebar_width,
            bgcolor="#12FFFFFF",
            border_radius=ft.BorderRadius(0, RADIUS, RADIUS, 0),
        )
        self._sidebar_panel = sidebar_panel
        body = ft.Row(
            controls=[
                sidebar_panel,
                ft.VerticalDivider(width=1, thickness=1, color="#10FFFFFF"),
                ft.Container(
                    content=ft.Column(
                        controls=[
                            self.toolbar,
                            ft.Container(content=self.content, expand=True),
                        ],
                        expand=True,
                        spacing=6,
                    ),
                    expand=True,
                    padding=ft.Padding(14, 12, 14, 10),
                ),
            ],
            expand=True,
            spacing=0,
        )

        self.header = ft.Container(
            content=ft.Row(
                controls=[
                    ft.Column(
                        controls=[
                            ft.Text(APP_NAME, size=18, color=TEXT, weight=ft.FontWeight.W_700),
                            self.status,
                        ],
                        spacing=0,
                        expand=True,
                        horizontal_alignment=ft.CrossAxisAlignment.START,
                    ),
                    icon_btn(ft.Icons.REFRESH_ROUNDED, self.on_rescan, tooltip="重新扫描"),
                    icon_btn(ft.Icons.INFO_OUTLINE_ROUNDED, lambda e: self.goto(2), tooltip="关于/设置"),
                ],
                alignment=ft.MainAxisAlignment.START,
                vertical_alignment=ft.CrossAxisAlignment.CENTER,
            ),
            padding=ft.Padding(16, 14, 16, 10),
        )

        # 底部导航：图库 / 卡片 / 设置（回收站、创作走侧边栏入口）
        p.on_resize = self.on_resize
        p.navigation_bar = ft.NavigationBar(
            bgcolor="#1A0F31",
            selected_index=0,
            on_change=self.on_tab,
            destinations=[
                ft.NavigationBarDestination(icon=ft.Icons.PHOTO_LIBRARY_OUTLINED,
                                            selected_icon=ft.Icons.PHOTO_LIBRARY, label="图库"),
                ft.NavigationBarDestination(icon=ft.Icons.STYLE_OUTLINED,
                                            selected_icon=ft.Icons.STYLE, label="卡片"),
                ft.NavigationBarDestination(icon=ft.Icons.SETTINGS_OUTLINED,
                                            selected_icon=ft.Icons.SETTINGS, label="设置"),
            ],
        )
        p.floating_action_button = ft.FloatingActionButton(
            icon=ft.Icons.ADD, bgcolor=ACCENT, mini=True,
            tooltip="导入 / 扫描目录", on_click=self.on_add_folder,
        )
        p.floating_action_button_location = ft.FloatingActionButtonLocation.END_FLOAT

        root = ft.Container(
            content=ft.Column(controls=[self.header, ft.Container(content=body, expand=True)],
                              expand=True, spacing=0),
            gradient=BG_GRADIENT,
            expand=True,
        )
        p.add(root)

        self.picker = ft.FilePicker(on_result=self.on_pick_dir)
        p.overlay.append(self.picker)

    @staticmethod
    def calc_sidebar_width(page_w: float) -> int:
        """左侧相册栏宽度 = 界面宽度的 1/5，带上下限保证可读。"""
        return int(max(72, min(240, (page_w or 900) * 0.2)))

    def sync_sidebar_width(self) -> None:
        want = self.calc_sidebar_width(self.page.width)
        if want != self.sidebar_width:
            self.sidebar_width = want
            if getattr(self, "_sidebar_panel", None) is not None:
                self._sidebar_panel.width = want

    def on_resize(self, e=None):
        """窗口变化时重算侧栏宽度；常驻布局，绝不覆盖右侧照片区。"""
        self.sync_sidebar_width()
        self.render()
        self.page.update()

    # ------------------------------------------------------------------ 启动
    async def _boot(self, e=None):
        self.sync_sidebar_width()
        self.render()
        self.page.run_thread(self._scan_job)

    def _scan_job(self):
        self.scanning = True
        self._set_status("正在扫描图集…")
        n = len(self.lib.scan(progress=lambda c: self._set_status_async(f"已发现 {c} 张…")))
        self.scanning = False
        self.lib.auto_clean()
        self._set_status(f"共 {n} 张照片 · {fmt_size(self.lib.total_size())}")
        self._refresh_ui()

    def _set_status(self, text: str) -> None:
        self.status.value = text

    def _set_status_async(self, text: str) -> None:
        self.page.run_task(self._set_status_task, text)

    async def _set_status_task(self, text: str):
        self.status.value = text
        self.page.update()

    def _refresh_ui(self):
        self.render()
        self.page.update()

    # ------------------------------------------------------------------ 缩略图
    def _thumb_worker(self):
        while True:
            path = self._todo.get()
            if path is None:
                break
            out = self.lib.make_thumb(path)
            if out:
                self._done.put((path, out))

    def request_thumb(self, path: str, img: ft.Image) -> None:
        cached = self.lib.cached_thumb(path)
        if cached:
            img.src = cached
            return
        self._targets.setdefault(path, []).append(img)
        self._todo.put(path)

    async def _drain_thumbs(self):
        while True:
            dirty = False
            while not self._done.empty():
                path, thumb = self._done.get()
                for img in self._targets.pop(path, []):
                    try:
                        img.src = thumb
                        dirty = True
                    except Exception:
                        pass
            if dirty:
                self.page.update()
            await asyncio.sleep(0.3)

    # ------------------------------------------------------------------ 渲染
    def render(self):
        self.render_sidebar()
        self.render_toolbar()
        if self.tab == 0:
            self.render_library()
        elif self.tab == 1:
            self.render_card()
        elif self.tab == 2:
            self.render_settings()
        elif self.tab == 3:
            self.render_trash()
        else:
            self.render_craft()
        if self.page.floating_action_button is not None:
            self.page.floating_action_button.visible = self.tab in (0, 3)
        # 卡片页与设置页不需要左侧相册栏，腾出全部宽度给内容
        if getattr(self, "_sidebar_panel", None) is not None:
            self._sidebar_panel.visible = self.tab not in (1, 2)
        self.sync_sidebar_width()
        nb = self.page.navigation_bar
        if nb is not None:
            nb.selected_index = self.tab if self.tab < 3 else 0

    def render_sidebar(self):
        clear_marquee()
        items = []
        fav_count = len(self.lib.favorites)
        items.append(sidebar_item(ft.Icons.APPS_ROUNDED, "全部照片", len(self.lib.photos),
                                  self.album == "ALL" and not self.blocked_page,
                                  lambda e: self.pick_album("ALL")))
        items.append(sidebar_item(ft.Icons.STAR_BORDER_ROUNDED, "收藏", fav_count,
                                  self.album == "FAV", lambda e: self.pick_album("FAV")))
        items.append(ft.Container(height=8))
        items.append(ft.Container(
            content=ft.Text("图集", size=11, color="#6B5C8C"),
            padding=ft.Padding(12, 4, 12, 4),
        ))
        for name, count in self.lib.albums():
            items.append(sidebar_item(ft.Icons.FOLDER_OPEN_OUTLINED, name, count,
                                      self.album == name,
                                      lambda e, n=name: self.pick_album(n)))
        items.append(ft.Container(height=8))
        items.append(sidebar_item(ft.Icons.BLOCK_ROUNDED, "已屏蔽图集", len(self.lib.blocked),
                                  self.blocked_page, lambda e: self.show_blocked()))
        items.append(sidebar_item(ft.Icons.DELETE_OUTLINE_ROUNDED, "回收站", len(self.lib.trash),
                                  self.tab == 3, lambda e: self.goto(3)))
        items.append(sidebar_item(ft.Icons.AUTO_AWESOME_ROUNDED, "创作 / 水印", 0,
                                  self.tab == 4, lambda e: self.open_craft()))
        self.sidebar.controls = items

    def render_toolbar(self):
        if self.tab in (1, 2, 4):
            self.toolbar.controls = []
            return

        if self.tab == 3:
            sorts = [("time_desc", "删除时间"), ("name_asc", "名称"),
                     ("size_desc", "大小"), ("album_asc", "按图集")]
            row = ft.Row(
                controls=[
                    ft.Text("回收站", size=15, color=TEXT, weight=ft.FontWeight.W_600),
                    ft.Container(expand=True),
                    icon_btn(ft.Icons.RESTORE_ROUNDED, self.on_restore_sel, tooltip="还原所选"),
                    icon_btn(ft.Icons.DELETE_FOREVER_ROUNDED, self.on_purge_sel,
                             color=DANGER, tooltip="彻底删除"),
                    icon_btn(ft.Icons.CLEANING_SERVICES_ROUNDED, self.on_empty_trash,
                             color=DANGER, tooltip="清空回收站"),
                ],
                vertical_alignment=ft.CrossAxisAlignment.CENTER,
            )
            chips = ft.Row(
                controls=[chip(t, self.trash_sort == k, lambda e, k=k: self.set_trash_sort(k))
                          for k, t in sorts],
                scroll=ft.ScrollMode.AUTO,
            )
            # 回收站图集筛选（TabulaV3：回收站图集排序）
            albums = self.lib.trash_albums()
            album_row = ft.Row(
                controls=[chip("全部图集", self.trash_album == "ALL",
                               lambda e: self.set_trash_album("ALL"))]
                + [chip(f"{a} ({c})", self.trash_album == a,
                        lambda e, a=a: self.set_trash_album(a)) for a, c in albums],
                scroll=ft.ScrollMode.AUTO,
            )
            self.toolbar.controls = [row, chips] + ([album_row] if albums else [])
            return

        # 照片库
        self.search = ft.TextField(
            value=self.query,
            hint_text="搜索照片名称 / 图集",
            hint_style=ft.TextStyle(size=13, color="#6B5C8C"),
            text_style=ft.TextStyle(size=13, color=TEXT),
            border=ft.InputBorder.NONE,
            filled=True,
            bgcolor=GLASS,
            border_radius=ft.BorderRadius(14, 14, 14, 14),
            content_padding=ft.Padding(12, 0, 12, 0),
            height=40,
            prefix_icon=ft.Icons.SEARCH_ROUNDED,
            on_change=self.on_search,
        )
        view_row = ft.Row(
            controls=[
                ft.Container(content=self.search, expand=True),
                # 宫格 / 列表 合并为一个按钮，点击即切换
                icon_btn(
                    ft.Icons.GRID_VIEW_ROUNDED if self.lib.view == "grid"
                    else ft.Icons.VIEW_LIST_ROUNDED,
                    lambda e: self.toggle_view(),
                    color=ACCENT if self.lib.view in ("grid", "list") else TEXT_DIM,
                    tooltip=("切换为列表" if self.lib.view == "grid" else "切换为宫格"),
                ),
                icon_btn(ft.Icons.CALENDAR_MONTH_ROUNDED, lambda e: self.set_view("timeline"),
                         color=ACCENT if self.lib.view == "timeline" else TEXT_DIM, tooltip="时间线"),
                icon_btn(ft.Icons.AUTO_AWESOME_ROUNDED, lambda e: self.open_craft(),
                         color=ACCENT, tooltip="创作 / 水印"),
                ft.PopupMenuButton(
                    icon=ft.Icons.SORT_ROUNDED,
                    icon_color=TEXT_DIM,
                    tooltip="排序",
                    items=[
                        ft.PopupMenuItem(content="日期（新→旧）",
                                         on_click=lambda e: self.set_sort("date_desc")),
                        ft.PopupMenuItem(content="日期（旧→新）",
                                         on_click=lambda e: self.set_sort("date_asc")),
                        ft.PopupMenuItem(content="名称", on_click=lambda e: self.set_sort("name_asc")),
                        ft.PopupMenuItem(content="大小", on_click=lambda e: self.set_sort("size_desc")),
                    ],
                ),
            ],
            vertical_alignment=ft.CrossAxisAlignment.CENTER,
        )
        self.toolbar.controls = [view_row]
        if self.select_mode:
            n = len(self.selected)
            self.toolbar.controls.append(
                ft.Row(
                    controls=[
                        ft.Text(f"已选 {n} 项", size=13, color=ACCENT),
                        ft.Container(expand=True),
                        ft.TextButton(content=ft.Text("全选", size=12, color=TEXT_DIM),
                                      on_click=self.on_select_all),
                        ft.TextButton(content=ft.Text("取消", size=12, color=TEXT_DIM),
                                      on_click=self.on_exit_select),
                    ],
                    vertical_alignment=ft.CrossAxisAlignment.CENTER,
                    scroll=ft.ScrollMode.AUTO,
                )
            )
            # 多选操作条：收藏 / 移动 / 重命名 / 删除
            ops = [
                ("收藏", ft.Icons.STAR_BORDER_ROUNDED, ACCENT, self.on_sel_fav),
                ("移动", ft.Icons.DRIVE_FILE_MOVE_ROUNDED, ACCENT, self.on_sel_move),
                ("重命名", ft.Icons.EDIT_ROUNDED, TEXT_DIM, self.on_sel_rename),
                ("删除", ft.Icons.DELETE_OUTLINE_ROUNDED, DANGER, self.on_sel_delete),
            ]
            self.toolbar.controls.append(
                ft.Row(
                    controls=[
                        ft.Container(
                            content=ft.Row(controls=[
                                ft.Icon(icon, size=16, color=color),
                                ft.Text(label, size=12, color=color),
                            ], spacing=4),
                            padding=ft.Padding(10, 7, 10, 7),
                            bgcolor=GLASS,
                            border_radius=ft.BorderRadius(12, 12, 12, 12),
                            border=ft.Border.all(1, STROKE),
                            on_click=fn,
                        ) for label, icon, color, fn in ops
                        if label != "重命名" or n == 1
                    ],
                    scroll=ft.ScrollMode.AUTO,
                )
            )

    # ---------------- 照片库
    def render_library(self):
        if self.blocked_page:
            self.render_blocked()
            return
        photos = self.lib.select(
            album=self.album, month=self.month, query=self.query,
            favorites_only=(self.album == "FAV"), sort=self.sort,
        )

        months = self.lib.months()
        month_row = ft.Row(
            controls=[chip("全部", self.month is None, lambda e: self.set_month(None))]
            + [chip(f"{m} ({c})", self.month == m, lambda e, m=m: self.set_month(m))
               for m, c in months[:24]],
            scroll=ft.ScrollMode.AUTO,
        )
        head = ft.Row(
            controls=[
                ft.Text(self.album_title(), size=14, color=TEXT, weight=ft.FontWeight.W_600),
                ft.Container(expand=True),
                label(f"{len(photos)} 张 · {fmt_size(sum(p.size for p in photos))}"),
                icon_btn(ft.Icons.CLEANING_SERVICES_ROUNDED, self.on_month_clean,
                         color=ACCENT, tooltip="按月份专清"),
            ],
            vertical_alignment=ft.CrossAxisAlignment.CENTER,
        )

        if not photos:
            hint = empty_hint(
                ft.Icons.PHOTO_LIBRARY_OUTLINED,
                "还没有照片",
                "点击右下角 + 添加扫描目录" if not self.lib.roots else "换个图集或清除筛选试试",
            )
            self.content.content = ft.Column(controls=[head, month_row,
                                                       ft.Container(content=hint, expand=True)],
                                             expand=True, spacing=8)
            return

        if self.lib.view == "grid":
            grid = ft.GridView(
                controls=[self.photo_card(p) for p in photos],
                runs_count=self.grid_cols(),
                child_aspect_ratio=1.0,
                spacing=10,
                run_spacing=10,
                expand=True,
            )
            area = grid
        elif self.lib.view == "timeline":
            area = self.timeline_view(photos)
        else:
            area = ft.ListView(controls=[self.photo_row(p) for p in photos],
                               spacing=8, expand=True, padding=ft.Padding(0, 0, 8, 0))
        self.content.content = ft.Column(controls=[head, month_row, ft.Container(content=area, expand=True)],
                                         expand=True, spacing=8)

    def timeline_view(self, photos: list[Photo]) -> ft.ListView:
        """集邮式时间线：按日期分段，段内宫格。"""
        groups = self.lib.timeline(photos)
        cols = []
        for day, items in groups:
            cells = []
            for p in items:
                img = ft.Image(
                    src=self.lib.cached_thumb(p.path) or "",
                    fit=ft.BoxFit.COVER, width=76, height=76,
                    error_content=ft.Container(
                        content=ft.Icon(ft.Icons.IMAGE_OUTLINED, color="#4B3B6B", size=18),
                        alignment=ft.Alignment(0, 0)),
                )
                self.request_thumb(p.path, img)
                cells.append(
                    self._photo_gesture(
                        ft.Container(
                            content=ft.Stack(
                                controls=[
                                    ft.Container(content=img, width=76, height=76,
                                                 border_radius=ft.BorderRadius(12, 12, 12, 12),
                                                 clip_behavior=ft.ClipBehavior.ANTI_ALIAS),
                                    ft.Container(
                                        content=ft.Icon(ft.Icons.STAR_ROUNDED, size=10, color="#FACC15"),
                                        right=3, bottom=3,
                                        visible=p.path in self.lib.favorites,
                                    ),
                                    ft.Container(
                                        content=ft.Icon(ft.Icons.CHECK_CIRCLE_ROUNDED,
                                                        size=16, color="#FFFFFF"),
                                        alignment=ft.Alignment(1, -1),
                                        padding=ft.Padding(0, 3, 3, 0),
                                        visible=p.path in self.selected,
                                    ),
                                ],
                                width=76, height=76,
                            ),
                            width=76, height=76,
                        ),
                        p.path,
                    )
                )
            cols.append(
                ft.Container(
                    content=ft.Column(
                        controls=[
                            ft.Row(
                                controls=[
                                    ft.Container(width=4, height=16, bgcolor=ACCENT,
                                                 border_radius=ft.BorderRadius(2, 2, 2, 2)),
                                    ft.Text(day, size=13, color=TEXT, weight=ft.FontWeight.W_600),
                                    ft.Text(f"{len(items)} 张", size=11, color=TEXT_DIM),
                                    ft.Container(expand=True),
                                    ft.TextButton(
                                        content=ft.Text("专清这日", size=11, color=TEXT_DIM),
                                        on_click=lambda e, ps=items: self.on_trash(
                                            [x.path for x in ps]),
                                    ),
                                ],
                                vertical_alignment=ft.CrossAxisAlignment.CENTER,
                                spacing=8,
                            ),
                            ft.Row(controls=cells, wrap=True, spacing=8, run_spacing=8),
                        ],
                        spacing=8,
                    ),
                    padding=ft.Padding(10, 10, 10, 12),
                    bgcolor=GLASS,
                    border_radius=ft.BorderRadius(16, 16, 16, 16),
                    border=ft.Border.all(1, "#18FFFFFF"),
                )
            )
        return ft.ListView(controls=cols, spacing=10, expand=True,
                           padding=ft.Padding(0, 0, 8, 40))

    def album_title(self) -> str:
        if self.album == "ALL":
            return "全部照片" + (f" · {self.month}" if self.month else "")
        if self.album == "FAV":
            return "收藏"
        return self.album

    def grid_cols(self) -> int:
        """按扣除侧栏后的可用宽度算列数，手机上保证至少 3 列。"""
        w = self.page.width or 900
        side = 0 if self.tab in (1, 2) else self.sidebar_width
        usable = w - side - 14 * 2
        cell = 104 if w < 480 else 150          # 小屏用更紧凑的格子
        cols = max(2, min(8, int(usable // cell)))
        return max(3, cols) if w < 600 else cols

    # ---------------- 手势：长按进入多选（点击与长按互不干扰）
    def _photo_gesture(self, content: ft.Control, path: str) -> ft.GestureDetector:
        """把照片单元包成手势控件。

        Flutter 的 GestureDetector 在长按触发后不会再回调 on_tap，
        这里额外加一个时间窗做兜底，防止个别设备两个事件都派发导致
        「长按选中 → 立刻又被点击取消」。
        """
        return ft.GestureDetector(
            content=content,
            on_tap=lambda e, p=path: self._tap_after_long_press(p),
            on_long_press=lambda e, p=path: self.on_photo_long_press(p),
        )

    def _tap_after_long_press(self, path: str):
        now = time.time()
        if now - getattr(self, "_last_long_press_at", 0.0) < 0.45:
            return                      # 长按刚触发，忽略紧随的点击
        self.on_photo_click(path)

    def photo_card(self, p: Photo) -> ft.Container:
        selected = p.path in self.selected
        img = ft.Image(
            src=self.lib.cached_thumb(p.path) or "",
            fit=ft.BoxFit.COVER,
            error_content=ft.Container(
                content=ft.Icon(ft.Icons.IMAGE_OUTLINED, color="#4B3B6B", size=28),
                alignment=ft.Alignment(0, 0),
            ),
        )
        self.request_thumb(p.path, img)
        fav = p.path in self.lib.favorites
        card = ft.Container(
            content=ft.Stack(
                controls=[
                    ft.Container(content=img, alignment=ft.Alignment(0, 0), expand=True),
                    ft.Container(
                        content=ft.Row(
                            controls=[
                                ft.Text(p.name, size=10, color=TEXT, max_lines=1,
                                        overflow=ft.TextOverflow.ELLIPSIS, expand=True),
                                ft.Icon(ft.Icons.STAR_ROUNDED, size=12, color="#FACC15") if fav else ft.Container(width=0),
                            ],
                            spacing=4,
                            vertical_alignment=ft.CrossAxisAlignment.CENTER,
                        ),
                        left=0, right=0, bottom=0,
                        padding=ft.Padding(8, 6, 8, 6),
                        bgcolor="#99000000",
                    ),
                    ft.Container(
                        content=ft.Icon(ft.Icons.CHECK_CIRCLE_ROUNDED, color="#FFFFFF", size=22),
                        alignment=ft.Alignment(1, -1),
                        padding=ft.Padding(0, 6, 6, 0),
                        visible=selected,
                    ),
                ],
                expand=True,
            ),
            border_radius=ft.BorderRadius(16, 16, 16, 16),
            clip_behavior=ft.ClipBehavior.ANTI_ALIAS,
            border=ft.Border.all(3, ACCENT) if selected else None,
        )
        return self._photo_gesture(card, p.path)

    def photo_row(self, p: Photo) -> ft.Container:
        selected = p.path in self.selected
        img = ft.Image(
            src=self.lib.cached_thumb(p.path) or "",
            fit=ft.BoxFit.COVER, width=56, height=56,
            error_content=ft.Container(
                content=ft.Icon(ft.Icons.IMAGE_OUTLINED, color="#4B3B6B", size=20),
                alignment=ft.Alignment(0, 0)),
        )
        self.request_thumb(p.path, img)
        thumb = ft.Container(content=img, width=56, height=56,
                             border_radius=ft.BorderRadius(12, 12, 12, 12),
                             clip_behavior=ft.ClipBehavior.ANTI_ALIAS)
        fav = p.path in self.lib.favorites
        row = ft.Row(
            controls=[
                thumb,
                ft.Column(
                    controls=[
                        ft.Text(p.name, size=13, color=TEXT, max_lines=1,
                                overflow=ft.TextOverflow.ELLIPSIS),
                        ft.Text(f"{p.album} · {p.date_str} · {fmt_size(p.size)}",
                                size=11, color=TEXT_DIM, max_lines=1,
                                overflow=ft.TextOverflow.ELLIPSIS),
                    ],
                    spacing=2, expand=True,
                    horizontal_alignment=ft.CrossAxisAlignment.START,
                ),
                ft.Checkbox(value=selected, on_change=lambda e, path=p.path: self.on_photo_click(path)),
                ft.PopupMenuButton(
                    icon=ft.Icons.MORE_VERT, icon_color=TEXT_DIM, icon_size=18,
                    items=[
                        ft.PopupMenuItem(content="取消收藏" if fav else "收藏",
                                         icon=ft.Icons.STAR_BORDER_ROUNDED,
                                         on_click=lambda e, path=p.path: self.on_fav(path)),
                        ft.PopupMenuItem(content="清理到回收站", icon=ft.Icons.DELETE_OUTLINE,
                                         on_click=lambda e, path=p.path: self.on_trash([path])),
                        ft.PopupMenuItem(content="屏蔽此图集", icon=ft.Icons.BLOCK_ROUNDED,
                                         on_click=lambda e, a=p.album: self.on_block_album(a)),
                        ft.PopupMenuItem(content="打开所在目录", icon=ft.Icons.FOLDER_OPEN_OUTLINED,
                                         on_click=lambda e, path=p.path: self.on_open_dir(path)),
                    ],
                ),
            ],
            spacing=10,
            vertical_alignment=ft.CrossAxisAlignment.CENTER,
        )
        box = ft.Container(
            content=row,
            padding=ft.Padding(10, 8, 6, 8),
            bgcolor=ACCENT_SOFT if selected else GLASS,
            border_radius=ft.BorderRadius(14, 14, 14, 14),
            border=ft.Border.all(1, "#18FFFFFF"),
        )
        return self._photo_gesture(box, p.path)

    # ---------------- 回收站
    def render_trash(self):
        items = self.lib.sorted_trash(self.trash_sort)
        if self.trash_album != "ALL":
            items = [t for t in items if t.album == self.trash_album]
        if not items:
            self.content.content = empty_hint(ft.Icons.DELETE_OUTLINE_ROUNDED,
                                              "回收站是空的", "清理的照片会先放到这里")
            return
        rows = []
        for t in items:
            sel = t.id in self.trash_selected
            img = ft.Image(src=t.thumb or "", fit=ft.BoxFit.COVER, width=48, height=48,
                           error_content=ft.Container(
                               content=ft.Icon(ft.Icons.IMAGE_OUTLINED, color="#4B3B6B", size=18),
                               alignment=ft.Alignment(0, 0)))
            rows.append(
                ft.Container(
                    content=ft.Row(
                        controls=[
                            ft.Checkbox(value=sel, on_change=lambda e, i=t.id: self.toggle_trash(i)),
                            ft.Container(content=img, width=48, height=48,
                                         border_radius=ft.BorderRadius(12, 12, 12, 12),
                                         clip_behavior=ft.ClipBehavior.ANTI_ALIAS),
                            ft.Column(
                                controls=[
                                    ft.Text(t.name, size=13, color=TEXT, max_lines=1,
                                            overflow=ft.TextOverflow.ELLIPSIS),
                                    ft.Text(f"{t.album} · {fmt_size(t.size)} · 删除于 "
                                            f"{_ts(t.trashed_at)}",
                                            size=11, color=TEXT_DIM),
                                ],
                                spacing=2, expand=True,
                                horizontal_alignment=ft.CrossAxisAlignment.START,
                            ),
                            icon_btn(ft.Icons.RESTORE_ROUNDED, lambda e, i=t.id: self.restore([i])),
                            icon_btn(ft.Icons.DELETE_FOREVER_ROUNDED, lambda e, i=t.id: self.purge([i]),
                                     color=DANGER),
                        ],
                        spacing=8,
                        vertical_alignment=ft.CrossAxisAlignment.CENTER,
                    ),
                    padding=ft.Padding(8, 6, 6, 6),
                    bgcolor=ACCENT_SOFT if sel else GLASS,
                    border_radius=ft.BorderRadius(14, 14, 14, 14),
                )
            )
        self.content.content = ft.Column(
            controls=[
                ft.Row(controls=[
                    label(f"{len(items)} 项 · {fmt_size(self.lib.trash_size())}"),
                    ft.Container(expand=True),
                    ft.TextButton(content=ft.Text("全选", size=12, color=TEXT_DIM),
                                  on_click=self.on_trash_select_all),
                ]),
                ft.ListView(controls=rows, spacing=8, expand=True),
            ],
            expand=True,
            spacing=6,
        )

    # ---------------- 屏蔽图集
    def render_blocked(self):
        if not self.lib.blocked:
            self.content.content = empty_hint(ft.Icons.BLOCK_ROUNDED, "没有屏蔽的图集",
                                              "在照片上长按 → 屏蔽此图集")
            return
        self.content.content = ft.Column(
            controls=[
                ft.Text("已屏蔽的图集不会出现在扫描结果中", size=12, color=TEXT_DIM),
                ft.Column(controls=[
                    ft.Container(
                        content=ft.Row(controls=[
                            ft.Icon(ft.Icons.BLOCK_ROUNDED, size=16, color=TEXT_DIM),
                            ft.Text(b, size=13, color=TEXT, expand=True, max_lines=1,
                                    overflow=ft.TextOverflow.ELLIPSIS),
                            ft.TextButton(content=ft.Text("取消屏蔽", size=12, color=ACCENT),
                                          on_click=lambda e, n=b: self.on_unblock(n)),
                        ]),
                        padding=ft.Padding(12, 8, 8, 8),
                        bgcolor=GLASS,
                        border_radius=ft.BorderRadius(14, 14, 14, 14),
                    ) for b in sorted(self.lib.blocked)
                ], scroll=ft.ScrollMode.AUTO, expand=True),
            ],
            spacing=10, expand=True,
        )

    # ---------------- 卡片页：叠放浏览 + 归类到相册
    def build_card_queue(self, album: str | None = None) -> None:
        """按当前图集筛选条件建立待分类队列。"""
        src = album or (None if self.album in ("ALL", "FAV") else self.album)
        self.card_queue = self.lib.select(
            album=self.album, month=self.month, query=self.query,
            favorites_only=(self.album == "FAV"), sort=self.sort,
        )
        self.card_idx = 0
        self.card_done = 0
        self.sidebar_width = 168        # 启动时按界面 1/5 重算

    def render_card(self):
        if not self.card_queue:
            self.build_card_queue()
        if not self.card_queue:
            self.content.content = empty_hint(
                ft.Icons.STYLE_OUTLINED, "没有待分类的照片",
                "去图库选一个图集，或先扫描一些照片")
            return

        total = len(self.card_queue)
        if self.card_idx >= total:
            self.content.content = ft.Container(
                content=ft.Column(
                    controls=[
                        ft.Icon(ft.Icons.TASK_ALT_ROUNDED, size=56, color=ACCENT),
                        ft.Text("这一批都归好类了", size=16, color=TEXT),
                        ft.Text(f"本次处理 {self.card_done} 张", size=12, color=TEXT_DIM),
                        ft.TextButton(content=ft.Text("重新装填待分类", size=12, color=ACCENT),
                                      on_click=lambda e: (self.build_card_queue(),
                                                          self.render(), self.page.update())),
                    ],
                    alignment=ft.MainAxisAlignment.CENTER,
                    horizontal_alignment=ft.CrossAxisAlignment.CENTER,
                    spacing=10,
                ),
                alignment=ft.Alignment(0, 0), expand=True,
            )
            return

        cur = self.card_queue[self.card_idx]
        layers = card.stack_layers(total - self.card_idx)

        # --- 叠放卡堆（只有叠放，不加任何边框模板）
        stack_cells = []
        for i, (dx, dy, scale, rot, op) in enumerate(layers):
            idx = self.card_idx + i
            if idx >= total:
                break
            p = self.card_queue[idx]
            w = int((self.page.width or 900) * 0.30 * scale)
            w = max(150, min(360, w))
            img = ft.Image(
                src=self.lib.cached_thumb(p.path) or "",
                fit=ft.BoxFit.COVER, width=w, height=int(w * 1.28),
                error_content=ft.Container(
                    content=ft.Icon(ft.Icons.IMAGE_OUTLINED, color="#4B3B6B", size=26),
                    alignment=ft.Alignment(0, 0)),
            )
            self.request_thumb(p.path, img)
            card_box = ft.Container(
                content=img,
                width=w, height=int(w * 1.28),
                border_radius=ft.BorderRadius(18, 18, 18, 18),
                clip_behavior=ft.ClipBehavior.ANTI_ALIAS,
                shadow=ft.BoxShadow(blur_radius=24, spread_radius=2,
                                    color="#66000000" if i else "#99000000"),
            )
            if i == 0:
                # 顶层照片：上滑回收 / 下滑收藏 / 长按弹相册
                card_box = ft.GestureDetector(
                    content=card_box,
                    on_vertical_drag_update=lambda e, path=p.path: self.on_card_drag(e, path),
                    on_vertical_drag_end=lambda e, path=p.path: self.on_card_drag_end(e, path),
                    on_long_press=lambda e, path=p.path: self.on_card_long_press(path),
                    on_tap=lambda e, path=p.path: self.preview(path),
                )
            cell = ft.Container(
                content=card_box,
                offset=ft.Offset(dx, dy),
                rotate=ft.Rotate(rot, alignment=ft.Alignment(0, 0)),
                opacity=op,
            )
            stack_cells.append(cell)

        stack = ft.Container(
            content=ft.Stack(controls=list(reversed(stack_cells)),
                             alignment=ft.Alignment(0, 0)),
            height=int(min(460, (self.page.height or 700) * 0.52)),
            alignment=ft.Alignment(0, 0),
        )
        self._stack_ref = stack
        self._drag_tip = ft.Text("", size=12, color=TEXT_DIM)

        # --- 当前照片信息 + 操作
        head = ft.Row(
            controls=[
                ft.Text(f"{self.card_idx + 1} / {total}", size=13, color=ACCENT,
                        weight=ft.FontWeight.W_600),
                ft.Container(expand=True),
                chip("移动", self.card_mode == "move",
                     lambda e: self.set_card_mode("move")),
                chip("复制", self.card_mode == "copy",
                     lambda e: self.set_card_mode("copy")),
            ],
            vertical_alignment=ft.CrossAxisAlignment.CENTER,
        )
        info = ft.Column(
            controls=[
                ft.Text(cur.name, size=14, color=TEXT, max_lines=1,
                        overflow=ft.TextOverflow.ELLIPSIS),
                ft.Text(f"当前来源：{cur.album} · {card.rel_date(cur.mtime)} · "
                        f"{fmt_size(cur.size)}", size=11, color=TEXT_DIM),
            ],
            spacing=2, horizontal_alignment=ft.CrossAxisAlignment.CENTER,
        )
        actions = ft.Row(
            controls=[
                icon_btn(ft.Icons.UNDO_ROUNDED, lambda e: self.card_prev(),
                         tooltip="上一张"),
                icon_btn(ft.Icons.SKIP_NEXT_ROUNDED, lambda e: self.card_skip(),
                         tooltip="跳过这张"),
                icon_btn(ft.Icons.DELETE_OUTLINE_ROUNDED,
                         lambda e: self.on_trash([cur.path]), color=DANGER,
                         tooltip="清理到回收站"),
            ],
            alignment=ft.MainAxisAlignment.CENTER,
        )

        # --- 底部相册栏：点一下就归类
        targets = card.album_dirs(self.lib.roots, exclude=set(self.lib.blocked))
        album_chips = ft.Row(
            controls=[
                ft.Container(
                    content=ft.Row(controls=[
                        ft.Icon(ft.Icons.CREATE_NEW_FOLDER_OUTLINED, size=15, color=ACCENT),
                        ft.Text("新建文件夹", size=12, color=ACCENT),
                    ], spacing=4),
                    padding=ft.Padding(11, 8, 11, 8),
                    bgcolor=GLASS, border_radius=ft.BorderRadius(14, 14, 14, 14),
                    border=ft.Border.all(1, STROKE),
                    on_click=self.on_new_album,
                )
            ] + [
                ft.Container(
                    content=ft.Row(controls=[
                        ft.Icon(ft.Icons.FOLDER_OPEN_OUTLINED, size=14, color=TEXT_DIM),
                        ft.Text(f"{os.path.basename(path)}", size=12, color=TEXT),
                        ft.Text(str(n), size=10, color=TEXT_DIM),
                    ], spacing=5),
                    padding=ft.Padding(11, 8, 11, 8),
                    bgcolor=GLASS, border_radius=ft.BorderRadius(14, 14, 14, 14),
                    border=ft.Border.all(1, STROKE),
                    on_click=lambda e, d=path, n=os.path.basename(path): self.card_to(d, n),
                ) for path, n in targets[:24]
            ],
            scroll=ft.ScrollMode.AUTO,
        )

        self.content.content = ft.Column(
            controls=[
                head,
                ft.Container(content=stack, expand=True),
                self._drag_tip,
                info,
                ft.Text("上滑回收 · 下滑收藏 · 长按选择相册", size=11, color="#6B5C8C"),
                actions,
                ft.Text("归类到相册（点击即归档）", size=11, color="#6B5C8C"),
                album_chips,
            ],
            spacing=6, expand=True,
            horizontal_alignment=ft.CrossAxisAlignment.CENTER,
        )

    def set_card_mode(self, mode: str):
        self.card_mode = mode
        self.render()
        self.page.update()

    # ---------------- 卡片手势：上滑回收 / 下滑收藏 / 长按归类
    THRESHOLD = 90.0      # 触发阈值（像素）

    def on_card_drag(self, e, path: str):
        """拖动中：实时显示卡片位移与方向提示。"""
        delta = getattr(e, "primary_delta", None)
        delta = float(delta) if delta is not None else 0.0
        self._drag_dy = getattr(self, "_drag_dy", 0.0) + delta
        dy = max(-160.0, min(160.0, self._drag_dy))
        hint = "松手清理到回收站" if dy < -self.THRESHOLD else (
            "松手收藏" if dy > self.THRESHOLD else "")
        for c in self.content.content.controls if isinstance(
                getattr(self.content, "content", None), ft.Column) else []:
            pass
        self._apply_drag_hint(dy, hint)

    def _apply_drag_hint(self, dy: float, hint: str):
        """把位移应用到顶层卡片，并更新提示文案。"""
        stack = getattr(self, "_stack_ref", None)
        if stack is None:
            return
        cells = stack.content.controls
        if not cells:
            return
        top = cells[-1]
        top.offset = ft.Offset(0, dy / 320.0)
        top.opacity = max(0.35, 1.0 - abs(dy) / 260.0)
        tip = getattr(self, "_drag_tip", None)
        if tip is not None:
            tip.value = hint
            tip.color = DANGER if dy < 0 else ACCENT
        self.page.update()

    def on_card_drag_end(self, e, path: str):
        dy = getattr(self, "_drag_dy", 0.0)
        self._drag_dy = 0.0
        tip = getattr(self, "_drag_tip", None)
        if tip is not None:
            tip.value = ""
        stack = getattr(self, "_stack_ref", None)
        if stack is not None and stack.content.controls:
            top = stack.content.controls[-1]
            top.offset = ft.Offset(0, 0)
            top.opacity = 1.0
        if dy <= -self.THRESHOLD:
            self.card_drop_trash(path)
        elif dy >= self.THRESHOLD:
            self.card_drop_fav(path)
        else:
            self.page.update()

    def card_drop_trash(self, path: str):
        """上滑：直接清理到回收站（回收站可还原，无需二次确认）。"""
        n = self.lib.move_to_trash([path])
        if n:
            self.toast("已清理到回收站，可随时还原")
        self.card_queue = [p for p in self.card_queue if p.path != path]
        if self.card_idx >= len(self.card_queue):
            self.card_idx = 0
        self.render()
        self.page.update()

    def card_drop_fav(self, path: str):
        """下滑：收藏并跳到下一张。"""
        added = self.lib.toggle_favorite(path)
        self.toast("已收藏 ⭐" if added else "已取消收藏")
        self.card_queue = [p for p in self.card_queue if p.path != path]
        if self.card_idx >= len(self.card_queue):
            self.card_idx = 0
        self.render()
        self.page.update()

    def on_card_long_press(self, path: str):
        """长按：底部弹出相册列表，选择后移动分类。"""
        self.show_album_sheet(path)

    def show_album_sheet(self, path: str):
        targets = card.album_dirs(self.lib.roots, exclude=set(self.lib.blocked))
        rows = []

        def act(dest_dir: str, name: str):
            self.close_sheet()
            self.card_move_to(path, dest_dir, name)

        rows.append(
            ft.Container(
                content=ft.Row(controls=[
                    ft.Icon(ft.Icons.CREATE_NEW_FOLDER_OUTLINED, size=17, color=ACCENT),
                    ft.Text("新建文件夹…", size=13, color=ACCENT),
                ], spacing=8),
                padding=ft.Padding(14, 12, 14, 12),
                on_click=lambda e: (self.close_sheet(), self.on_new_album_for(path)),
            )
        )
        for d, n in targets[:40]:
            rows.append(
                ft.Container(
                    content=ft.Row(controls=[
                        ft.Icon(ft.Icons.FOLDER_OPEN_OUTLINED, size=17, color=TEXT_DIM),
                        ft.Text(os.path.basename(d), size=13, color=TEXT, expand=True,
                                max_lines=1, overflow=ft.TextOverflow.ELLIPSIS),
                        ft.Text(str(n), size=11, color=TEXT_DIM),
                        ft.Icon(ft.Icons.CHEVRON_RIGHT, size=15, color="#4B3B6B"),
                    ], spacing=8),
                    padding=ft.Padding(14, 11, 14, 11),
                    on_click=lambda e, dd=d, nn=os.path.basename(d): act(dd, nn),
                )
            )

        self.sheet = ft.BottomSheet(
            content=ft.Container(
                content=ft.Column(
                    controls=[
                        ft.Container(
                            content=ft.Column(controls=[
                                ft.Text("移动到相册", size=15, color=TEXT,
                                        weight=ft.FontWeight.W_600),
                                ft.Text(Path(path).name, size=11, color=TEXT_DIM,
                                        max_lines=1, overflow=ft.TextOverflow.ELLIPSIS),
                            ], spacing=2),
                            padding=ft.Padding(16, 6, 16, 10),
                        ),
                        ft.Divider(height=1, color="#18FFFFFF"),
                        ft.Container(
                            content=ft.ListView(controls=rows, spacing=2, expand=True),
                            height=min(340, max(160, len(rows) * 46)),
                        ),
                        ft.Divider(height=1, color="#18FFFFFF"),
                        ft.Row(controls=[
                            ft.TextButton(content=ft.Text("复制而非移动", size=12, color=TEXT_DIM),
                                          on_click=lambda e: (
                                              setattr(self, "card_mode", "copy"),
                                              self.toast("已切换为复制模式"),
                                              self.close_sheet())),
                            ft.Container(expand=True),
                            ft.TextButton(content=ft.Text("取消", size=12, color=TEXT_DIM),
                                          on_click=lambda e: self.close_sheet()),
                        ]),
                        ft.Container(height=8),
                    ],
                    spacing=0, tight=True,
                ),
                bgcolor="#241040",
                border_radius=ft.BorderRadius(24, 24, 0, 0),
                padding=ft.Padding(0, 8, 0, 0),
            ),
            open=True,
            show_drag_handle=True,
            bgcolor="#00000000",
        )
        self.page.overlay.append(self.sheet)
        self.page.update()

    def close_sheet(self):
        if getattr(self, "sheet", None) is not None:
            self.sheet.open = False
            try:
                self.page.overlay.remove(self.sheet)
            except ValueError:
                pass
            self.sheet = None
        self.page.update()

    def card_move_to(self, path: str, dest_dir: str, name: str):
        photo = self.lib.photos.get(path)
        if not photo:
            return
        self.card_queue = [p for p in self.card_queue if p.path != path]
        if self.card_idx >= len(self.card_queue):
            self.card_idx = 0
        self.page.run_thread(self._card_move_job, photo, dest_dir, name)

    def _card_move_job(self, photo, dest_dir: str, name: str):
        ok, failed = card.classify([photo.path], dest_dir, self.card_mode)
        if ok:
            self.lib.reindex([photo.path], dest_dir)
            self.card_done += 1
            self.toast(f"已{'移动' if self.card_mode == 'move' else '复制'}到「{name}」")
        else:
            self.toast(f"移动失败：{failed}")
        self.render()
        self.page.update()

    def on_new_album_for(self, path: str):
        field = ft.TextField(
            hint_text="新相册名称，如：头像 / 小说封面底图",
            hint_style=ft.TextStyle(size=12, color="#6B5C8C"),
            text_style=ft.TextStyle(size=13, color=TEXT),
            border=ft.InputBorder.NONE, filled=True, bgcolor=GLASS,
            border_radius=ft.BorderRadius(12, 12, 12, 12),
            content_padding=ft.Padding(12, 0, 12, 0), height=40,
            autofocus=True,
        )

        def create(ev=None):
            name = (field.value or "").strip()
            if not name:
                self.page.pop_dialog()
                return
            self.page.pop_dialog()
            try:
                parent = card.suggest_parent(self.lib.roots)
                d = card.create_album(parent, name)
                self.lib.add_root(parent)
                self.toast(f"已创建相册「{name}」")
                self.card_move_to(path, d, name)
            except Exception as ex:
                self.toast(f"创建失败：{ex}")

        self.page.show_dialog(
            ft.AlertDialog(
                modal=True,
                title=ft.Text("新建相册", size=15, color=TEXT, weight=ft.FontWeight.W_600),
                content=ft.Column(controls=[
                    field,
                    ft.Text("创建后自动把这张照片移动进去", size=11, color=TEXT_DIM),
                ], spacing=8, tight=True),
                bgcolor="#241040",
                actions=[
                    ft.TextButton(content=ft.Text("取消", size=12, color=TEXT_DIM),
                                  on_click=lambda e: self.page.pop_dialog()),
                    ft.TextButton(content=ft.Text("创建并移动", size=12, color=ACCENT),
                                  on_click=create),
                ],
                actions_alignment=ft.MainAxisAlignment.END,
            )
        )

    def card_skip(self):
        """跳过：移到队尾，稍后再处理。"""
        if not self.card_queue:
            return
        self.card_queue.append(self.card_queue.pop(self.card_idx))
        if self.card_idx >= len(self.card_queue):
            self.card_idx = 0
        self.render()
        self.page.update()

    def card_prev(self):
        if self.card_idx > 0:
            self.card_idx -= 1
        self.render()
        self.page.update()

    def card_to(self, dest_dir: str, name: str):
        """把当前照片归类到目标相册。"""
        if not self.card_queue or self.card_idx >= len(self.card_queue):
            return
        cur = self.card_queue.pop(self.card_idx)
        self.page.run_thread(self._card_to_job, cur, dest_dir, name)

    def _card_to_job(self, cur: Photo, dest_dir: str, name: str):
        ok, failed = card.classify([cur.path], dest_dir, self.card_mode)
        if ok:
            self.lib.reindex([cur.path], dest_dir)
            self.card_done += 1
            if self.card_idx >= len(self.card_queue):
                self.card_idx = 0
            self._set_status(f"已归档到「{name}」")
            self.toast(f"已{'移动' if self.card_mode == 'move' else '复制'}到「{name}」")
        else:
            self.toast(f"归档失败：{failed}")
        self.render()
        self.page.update()

    def on_new_album(self, e=None):
        field = ft.TextField(
            hint_text="新相册名称，如：头像 / 小说封面底图",
            hint_style=ft.TextStyle(size=12, color="#6B5C8C"),
            text_style=ft.TextStyle(size=13, color=TEXT),
            border=ft.InputBorder.NONE, filled=True, bgcolor=GLASS,
            border_radius=ft.BorderRadius(12, 12, 12, 12),
            content_padding=ft.Padding(12, 0, 12, 0), height=40,
            autofocus=True,
        )

        def create(ev=None):
            name = (field.value or "").strip()
            if not name:
                self.page.pop_dialog()
                return
            self.page.pop_dialog()
            try:
                parent = card.suggest_parent(self.lib.roots)
                d = card.create_album(parent, name)
                self.lib.add_root(parent)
                self.toast(f"已创建相册「{name}」")
                if self.card_queue and self.card_idx < len(self.card_queue):
                    self.card_to(d, name)
                else:
                    self.render()
                    self.page.update()
            except Exception as ex:
                self.toast(f"创建失败：{ex}")

        self.page.show_dialog(
            ft.AlertDialog(
                modal=True,
                title=ft.Text("新建相册", size=15, color=TEXT, weight=ft.FontWeight.W_600),
                content=ft.Column(controls=[
                    field,
                    ft.Text("会在当前扫描目录下创建文件夹，创建后自动归档当前照片",
                            size=11, color=TEXT_DIM),
                ], spacing=8, tight=True),
                bgcolor="#241040",
                actions=[
                    ft.TextButton(content=ft.Text("取消", size=12, color=TEXT_DIM),
                                  on_click=lambda e: self.page.pop_dialog()),
                    ft.TextButton(content=ft.Text("创建并归档", size=12, color=ACCENT),
                                  on_click=create),
                ],
                actions_alignment=ft.MainAxisAlignment.END,
            )
        )

    # ---------------- 创作 / 水印（对齐 photoo v3.3）
    def render_craft(self):
        photos = self.current_photos()
        if not photos:
            self.content.content = empty_hint(ft.Icons.AUTO_AWESOME_ROUNDED,
                                              "还没有可用于创作的照片",
                                              "先回到照片库扫描一些照片")
            return
        paths = {p.path for p in photos}
        if not self.craft_photo or self.craft_photo.path not in paths:
            self.craft_photo = photos[0]
        p = self.craft_photo
        w = self.wm

        self.craft_img = ft.Image(
            src=self.lib.cached_thumb(p.path) or "",
            fit=ft.BoxFit.CONTAIN,
            error_content=ft.Container(
                content=ft.Icon(ft.Icons.IMAGE_OUTLINED, color="#4B3B6B", size=40),
                alignment=ft.Alignment(0, 0)),
        )
        self._craft_preview_job()

        def f(text, value, key, hint=""):
            return ft.TextField(
                value=value, hint_text=hint or text,
                hint_style=ft.TextStyle(size=11, color="#6B5C8C"),
                text_style=ft.TextStyle(size=12, color=TEXT),
                border=ft.InputBorder.NONE, filled=True, bgcolor=GLASS,
                border_radius=ft.BorderRadius(12, 12, 12, 12),
                content_padding=ft.Padding(10, 0, 10, 0), height=36,
                on_change=lambda e, k=key: self.on_wm_text(k, e.control.value),
            )

        # 模板
        tmpl = ft.Row(
            controls=[chip(name, w["template"] == key,
                           lambda e, k=key: self.on_wm_set("template", k))
                      for key, (name, _) in watermark.TEMPLATES.items()],
        )
        # 滤镜
        filt = ft.Row(controls=[chip(f, w["filt"] == f,
                                     lambda e, v=f: self.on_wm_set("filt", v))
                                for f in watermark.FILTERS], scroll=ft.ScrollMode.AUTO)
        # 色彩
        color_modes = [("none", "无"), ("gradient", "渐变色"), ("half", "半屏色彩"), ("solid", "纯色")]
        cmode = ft.Row(controls=[chip(t, w["color_mode"] == k,
                                      lambda e, k=k: self.on_wm_set("color_mode", k))
                                 for k, t in color_modes])
        PRESETS = ["#A855F7", "#22D3EE", "#F472B6", "#FACC15", "#34D399", "#FB923C", "#64748B"]
        swatch = ft.Row(
            controls=[ft.Container(width=26, height=26, bgcolor=c,
                                   border_radius=ft.BorderRadius(8, 8, 8, 8),
                                   border=ft.Border.all(2, "#FFFFFF" if w["color1"] == c else "#00000000"),
                                   on_click=lambda e, c=c: self.on_wm_set("color1", c))
                      for c in PRESETS]
            + [ft.Container(width=26, height=26, bgcolor=c,
                            border_radius=ft.BorderRadius(8, 8, 8, 8),
                            border=ft.Border.all(2, "#FFFFFF" if w["color2"] == c else "#00000000"),
                            on_click=lambda e, c=c: self.on_wm_set("color2", c))
               for c in PRESETS],
            scroll=ft.ScrollMode.AUTO,
        )
        # 提示词
        prompt_row = ft.Row(
            controls=[
                ft.Container(
                    content=ft.Text(w["prompt"] or "点击右侧随机", size=12, color=ACCENT),
                    padding=ft.Padding(10, 8, 10, 8), bgcolor=GLASS,
                    border_radius=ft.BorderRadius(12, 12, 12, 12), expand=True),
                icon_btn(ft.Icons.CASINO_ROUNDED, lambda e: self.on_wm_random_prompt(),
                         color=ACCENT, tooltip=f"随机风格（内置 {len(watermark.STYLE_PROMPTS)} 种）"),
            ],
            vertical_alignment=ft.CrossAxisAlignment.CENTER,
        )

        panel = ft.ListView(
            controls=[
                ft.Row(controls=[
                    ft.TextButton(
                        content=ft.Text("← 返回照片库", size=12, color=TEXT_DIM),
                        on_click=lambda e: self.goto(0)),
                    ft.Container(expand=True),
                    ft.Text("创作 / 水印", size=14, color=TEXT, weight=ft.FontWeight.W_600),
                ], vertical_alignment=ft.CrossAxisAlignment.CENTER),
                glass_card(vbox([title("水印边框"), tmpl], spacing=8), padding=14),
                glass_card(vbox([title("滤镜"), filt], spacing=8), padding=14),
                glass_card(vbox([
                    title("色彩系统"),
                    cmode,
                    ft.Row(controls=[
                        f("主色", w["color1"], "color1"), f("副色", w["color2"], "color2"),
                    ]),
                    swatch,
                ], spacing=8), padding=14),
                glass_card(vbox([
                    title("波点样式"),
                    ft.Switch(label="启用波点（支持文本 / Emoji）", value=w["polka_enabled"],
                              active_color=ACCENT, label_text_style=ft.TextStyle(size=12, color=TEXT_DIM),
                              on_change=lambda e: self.on_wm_set("polka_enabled", e.control.value)),
                    f("波点内容", w["polka_text"], "polka_text", "如：✦ 或 摄影"),
                ], spacing=8), padding=14),
                glass_card(vbox([
                    title("美术风格提示词"),
                    prompt_row,
                    f("自定义水印文字", w["custom_text"], "custom_text", "如：西湖 · 杭州"),
                ], spacing=8), padding=14),
                ft.Row(controls=[
                    ft.Container(
                        content=ft.Text("导出到创作图集", size=13, color="#FFFFFF",
                                        weight=ft.FontWeight.W_600),
                        padding=ft.Padding(18, 12, 18, 12),
                        bgcolor=ACCENT,
                        border_radius=ft.BorderRadius(14, 14, 14, 14),
                        on_click=self.on_craft_export,
                    ),
                    ft.TextButton(content=ft.Text("换一张照片", size=12, color=TEXT_DIM),
                                  on_click=lambda e: self.on_craft_next()),
                ], spacing=10),
                ft.Container(height=40),
            ],
            spacing=10,
            expand=True,
        )

        preview_box = ft.Container(
            content=ft.Column(
                controls=[
                    ft.Container(content=self.craft_img, expand=True,
                                alignment=ft.Alignment(0, 0)),
                    ft.Text(p.name, size=12, color=TEXT_DIM, max_lines=1,
                            overflow=ft.TextOverflow.ELLIPSIS),
                ],
                expand=True, spacing=6,
            ),
            width=380,
            padding=14,
            bgcolor=GLASS,
            border_radius=ft.BorderRadius(20, 20, 20, 20),
            border=ft.Border.all(1, "#18FFFFFF"),
        )

        self.content.content = ft.Row(
            controls=[preview_box, ft.Container(content=panel, expand=True)],
            expand=True, spacing=12,
        )

    def current_photos(self) -> list[Photo]:
        return self.lib.select(album=self.album, month=self.month, query=self.query,
                               favorites_only=(self.album == "FAV"), sort=self.sort)

    def _craft_preview_job(self):
        if not self.craft_photo:
            return
        self.page.run_thread(self._craft_preview_worker, self.craft_photo, dict(self.wm))

    def _craft_preview_worker(self, photo, opts):
        out = watermark.preview_thumb(photo, str(self.lib.dir / "wmcache"), **opts)
        if out and getattr(self, "craft_img", None):
            self.craft_img.src = out
            self.page.update()

    def on_wm_set(self, key, value):
        self.wm[key] = value
        self.render()
        self.page.update()

    def on_wm_text(self, key, value):
        self.wm[key] = value or ""
        self._craft_preview_job()

    def on_wm_random_prompt(self):
        self.wm["prompt"] = watermark.suggest_prompt()
        self.toast(f"风格：{self.wm['prompt']}")
        self.render()
        self.page.update()

    def on_craft_next(self):
        photos = self.current_photos()
        if not photos:
            return
        idx = 0
        for i, p in enumerate(photos):
            if self.craft_photo and p.path == self.craft_photo.path:
                idx = i
                break
        self.craft_photo = photos[(idx + 1) % len(photos)]
        self.render()
        self.page.update()

    def craft_this(self, path: str):
        p = self.lib.photos.get(path)
        if p:
            self.craft_photo = p
        self.open_craft()

    def open_craft(self):
        self.tab = 4
        self.blocked_page = False
        self.render()
        self.page.update()

    def on_craft_export(self, e=None):
        if not self.craft_photo:
            return
        photo, opts = self.craft_photo, dict(self.wm)
        self.toast("正在渲染…")

        def job():
            try:
                dest = watermark.render(photo, self.lib.created_dir(), **opts)
                self._set_status(f"已导出：{os.path.basename(dest)}")
                self.toast(f"已保存到「创作」图集：{os.path.basename(dest)}")
            except Exception as ex:
                self.toast(f"导出失败：{ex}")
        self.page.run_thread(job)

    # ---------------- 设置
    def render_settings(self):
        root_rows = []
        # 安卓首次使用引导：说明需要授予的存储权限
        if is_android():
            granted = bool(self.lib.roots)
            guide = ft.Container(
                content=ft.Column(controls=[
                    ft.Row(controls=[
                        ft.Icon(ft.Icons.CHECK_CIRCLE_ROUNDED if granted
                                else ft.Icons.PERM_MEDIA_OUTLINED,
                                size=18, color="#34D399" if granted else ACCENT),
                        ft.Text("相册权限已就绪" if granted else "需要授予相册访问权限",
                                size=13, color=TEXT, weight=ft.FontWeight.W_600),
                    ], spacing=8),
                    ft.Text(
                        "已扫描到照片目录，可正常整理。"
                        if granted else
                        "请在系统弹窗中选择「允许」；若未看到弹窗，前往 "
                        "设置 → 应用 → 光影相册 → 权限 → 照片和视频 → 允许。",
                        size=11, color=TEXT_DIM,
                    ),
                    ft.Row(controls=[
                        ft.TextButton(content=ft.Text("重新检测目录", size=12, color=ACCENT),
                                      on_click=self.on_rescan),
                        ft.TextButton(content=ft.Text("打开系统设置", size=12, color=ACCENT),
                                      on_click=self.on_open_app_settings),
                    ], spacing=10),
                ], spacing=6),
                padding=14,
                bgcolor=GLASS_STRONG,
                border_radius=ft.BorderRadius(RADIUS, RADIUS, RADIUS, RADIUS),
            )
        else:
            guide = ft.Container(height=0)
        for r in self.lib.roots:
            exists = os.path.isdir(r)
            root_rows.append(
                ft.Container(
                    content=ft.Row(controls=[
                        ft.Icon(ft.Icons.FOLDER_OPEN_OUTLINED, size=16,
                                color=TEXT_DIM if exists else DANGER),
                        ft.Text(r, size=12, color=TEXT if exists else DANGER, expand=True,
                                max_lines=2, overflow=ft.TextOverflow.ELLIPSIS),
                        icon_btn(ft.Icons.CLOSE_ROUNDED, lambda e, p=r: self.remove_root(p),
                                 color=DANGER, size=18),
                    ], spacing=8, vertical_alignment=ft.CrossAxisAlignment.CENTER),
                    padding=ft.Padding(10, 6, 4, 6),
                    bgcolor=GLASS,
                    border_radius=ft.BorderRadius(12, 12, 12, 12),
                )
            )
        self.new_root = ft.TextField(
            hint_text="手动添加目录，如 /sdcard/DCIM 或 C:\\Users\\me\\Pictures",
            hint_style=ft.TextStyle(size=12, color="#6B5C8C"),
            text_style=ft.TextStyle(size=12, color=TEXT),
            border=ft.InputBorder.NONE, filled=True, bgcolor=GLASS,
            border_radius=ft.BorderRadius(12, 12, 12, 12),
            content_padding=ft.Padding(12, 0, 12, 0), height=40,
            on_submit=lambda e: self.add_root_from_input(),
        )
        roots_card = glass_card(
            vbox([
                title("扫描目录"),
                ft.Column(controls=root_rows, spacing=6) if root_rows else label("暂无目录"),
                ft.Row(controls=[
                    ft.Container(content=self.new_root, expand=True),
                    icon_btn(ft.Icons.DRIVE_FOLDER_UPLOAD_ROUNDED, self.on_add_folder,
                             color=ACCENT, tooltip="浏览选择目录"),
                    ft.TextButton(content=ft.Text("添加", size=12, color=ACCENT),
                                  on_click=lambda e: self.add_root_from_input()),
                ]),
            ], spacing=8),
            padding=14,
        )

        stats = ft.Row(controls=[
            stat_tile(str(len(self.lib.photos)), "照片"),
            stat_tile(fmt_size(self.lib.total_size()), "占用"),
            stat_tile(str(len(self.lib.trash)), "回收站"),
            stat_tile(str(len(self.lib.blocked)), "已屏蔽"),
        ], spacing=8)

        preview_card = glass_card(
            vbox([
                title("预览弹窗"),
                ft.Row(controls=[
                    ft.Text("显示快捷操作（收藏 / 清理 / 屏蔽）", size=12, color=TEXT_DIM, expand=True),
                    ft.Switch(value=self.show_preview_actions, active_color=ACCENT,
                              on_change=self.on_toggle_preview_actions),
                ], vertical_alignment=ft.CrossAxisAlignment.CENTER),
            ], spacing=6),
            padding=14,
        )

        view_card = glass_card(
            vbox([
                title("视图"),
                ft.Row(controls=[
                    chip("宫格", self.lib.view == "grid", lambda e: self.set_view("grid")),
                    chip("列表", self.lib.view == "list", lambda e: self.set_view("list")),
                    ft.Container(expand=True),
                    label("缩略图尺寸"),
                ], vertical_alignment=ft.CrossAxisAlignment.CENTER),
                ft.Slider(value=self.lib.thumb_size, min=160, max=640, divisions=8,
                          active_color=ACCENT, inactive_color="#3A2A5C",
                          label="{value}px", on_change=self.on_thumb_size),
            ], spacing=6),
            padding=14,
        )

        clean_card = glass_card(
            vbox([
                title("回收站自动清理"),
                label("超过设定天数自动彻底删除（0 = 关闭）", size=11),
                ft.Slider(value=float(self.lib.auto_clean_days), min=0, max=90, divisions=9,
                          active_color=ACCENT, inactive_color="#3A2A5C",
                          label="{value} 天", on_change=self.on_auto_clean),
            ], spacing=6),
            padding=14,
        )

        about = glass_card(
            vbox([
                title("关于"),
                label(f"{APP_NAME} PhotoBox v{VERSION}", 12, TEXT),
                label("一套 Python 代码同时构建 Android APK 与 Windows EXE", 11),
                label("图集屏蔽 · 按月份专清 · 回收站 · 收藏 · 宫格/列表双视图", 11),
                label(f"数据目录：{self.lib.dir}", 11),
            ], spacing=4),
            padding=14,
        )

        self.content.content = ft.ListView(
            controls=[
                stats,
                guide,
                roots_card,
                preview_card,
                view_card,
                clean_card,
                about,
                ft.Row(controls=[
                    ft.TextButton(content=ft.Text("清除缩略图缓存", size=12, color=TEXT_DIM),
                                  on_click=self.on_clear_cache),
                    ft.TextButton(content=ft.Text("重新扫描", size=12, color=ACCENT),
                                  on_click=self.on_rescan),
                ]),
                ft.Container(height=60),
            ],
            spacing=10,
            expand=True,
        )

    # ------------------------------------------------------------------ 交互
    def goto(self, tab: int):
        self.tab = tab
        self.blocked_page = False
        if tab != 3:
            self.trash_album = "ALL"
        self.render()
        self.page.update()

    def on_tab(self, e):
        idx = e.control.selected_index
        if idx == 1 and not self.card_queue:
            self.build_card_queue()
        self.goto(idx)

    def pick_album(self, name: str):
        self.album = name
        self.blocked_page = False
        self.month = None
        self.selected.clear()
        self.select_mode = False
        self.tab = 0
        self.render()
        self.page.update()

    def show_blocked(self):
        self.blocked_page = True
        self.tab = 0
        self.render()
        self.page.update()

    def set_month(self, m):
        self.month = m
        self.render()
        self.page.update()

    def toggle_view(self):
        """宫格 ⇄ 列表 一键切换。"""
        self.set_view("list" if self.lib.view == "grid" else "grid")

    def set_view(self, v: str):
        self.lib.view = v
        self.lib.save_config()
        self.render()
        self.page.update()

    def set_sort(self, s: str):
        self.sort = s
        self.render()
        self.page.update()

    def set_trash_sort(self, s: str):
        self.trash_sort = s
        self.render_trash()
        self.page.update()

    def set_trash_album(self, a: str):
        self.trash_album = a
        self.render_trash()
        self.page.update()

    def on_search(self, e):
        self.query = e.control.value or ""
        self.render()
        self.page.update()

    def on_rescan(self, e=None):
        self.page.run_thread(self._scan_job)
        self.toast("开始扫描…")

    def on_photo_click(self, path: str):
        if self.select_mode:
            if path in self.selected:
                self.selected.discard(path)
            else:
                self.selected.add(path)
            if not self.selected:
                self.select_mode = False
            self.render()
            self.page.update()
        else:
            self.preview(path)

    def on_photo_long_press(self, path: str):
        self._last_long_press_at = time.time()
        entering = not self.select_mode
        self.select_mode = True
        self.selected.add(path)
        if entering:
            self.toast("已进入多选，可继续点选更多")
        self.render()
        self.page.update()

    def on_select_all(self, e):
        photos = self.lib.select(album=self.album, month=self.month, query=self.query,
                                 favorites_only=self.album == "FAV", sort=self.sort)
        self.selected = {p.path for p in photos}
        self.render()
        self.page.update()

    def on_exit_select(self, e):
        self.select_mode = False
        self.selected.clear()
        self.render()
        self.page.update()

    def on_fav(self, path: str):
        self.lib.toggle_favorite(path)
        self.toast("已更新收藏")
        self.render()
        self.page.update()

    # ---------------- 多选操作：收藏 / 移动 / 重命名 / 删除
    def on_sel_fav(self, e=None):
        """批量加入/取消收藏。"""
        paths = list(self.selected)
        if not paths:
            return
        to_add = [p for p in paths if p not in self.lib.favorites]
        if to_add:
            self.lib.favorites.update(to_add)
            self.toast(f"已收藏 {len(to_add)} 张")
        else:
            for p in paths:
                self.lib.favorites.discard(p)
            self.toast(f"已取消收藏 {len(paths)} 张")
        self.lib.save_config()
        self.render()
        self.page.update()

    def on_sel_move(self, e=None):
        """批量移动到相册：底部弹窗列出所有相册。"""
        paths = list(self.selected)
        if not paths:
            return
        targets = card.album_dirs(self.lib.roots, exclude=set(self.lib.blocked))

        def act(dest_dir: str, name: str):
            self.close_sheet()
            self.page.run_thread(self._sel_move_job, paths, dest_dir, name)

        rows = [
            ft.Container(
                content=ft.Row(controls=[
                    ft.Icon(ft.Icons.CREATE_NEW_FOLDER_OUTLINED, size=17, color=ACCENT),
                    ft.Text("新建文件夹…", size=13, color=ACCENT),
                ], spacing=8),
                padding=ft.Padding(14, 12, 14, 12),
                on_click=lambda ev: (self.close_sheet(), self.on_new_album_for_multi(paths)),
            )
        ]
        for d, n in targets[:40]:
            rows.append(
                ft.Container(
                    content=ft.Row(controls=[
                        ft.Icon(ft.Icons.FOLDER_OPEN_OUTLINED, size=17, color=TEXT_DIM),
                        ft.Text(os.path.basename(d), size=13, color=TEXT, expand=True,
                                max_lines=1, overflow=ft.TextOverflow.ELLIPSIS),
                        ft.Text(str(n), size=11, color=TEXT_DIM),
                        ft.Icon(ft.Icons.CHEVRON_RIGHT, size=15, color="#4B3B6B"),
                    ], spacing=8),
                    padding=ft.Padding(14, 11, 14, 11),
                    on_click=lambda ev, dd=d, nn=os.path.basename(d): act(dd, nn),
                )
            )
        self.sheet = ft.BottomSheet(
            content=ft.Container(
                content=ft.Column(controls=[
                    ft.Container(
                        content=ft.Column(controls=[
                            ft.Text(f"移动 {len(paths)} 张到相册", size=15, color=TEXT,
                                    weight=ft.FontWeight.W_600),
                            ft.Text("移动后原位置不再保留", size=11, color=TEXT_DIM),
                        ], spacing=2),
                        padding=ft.Padding(16, 6, 16, 10),
                    ),
                    ft.Divider(height=1, color="#18FFFFFF"),
                    ft.Container(
                        content=ft.ListView(controls=rows, spacing=2, expand=True),
                        height=min(340, max(160, len(rows) * 46)),
                    ),
                    ft.Row(controls=[
                        ft.TextButton(content=ft.Text("复制而非移动", size=12, color=TEXT_DIM),
                                      on_click=lambda ev: (
                                          setattr(self, "card_mode", "copy"),
                                          self.toast("已切换为复制模式"),
                                          self.close_sheet())),
                        ft.Container(expand=True),
                        ft.TextButton(content=ft.Text("取消", size=12, color=TEXT_DIM),
                                      on_click=lambda ev: self.close_sheet()),
                    ]),
                    ft.Container(height=8),
                ], spacing=0, tight=True),
                bgcolor="#241040",
                border_radius=ft.BorderRadius(24, 24, 0, 0),
                padding=ft.Padding(0, 8, 0, 0),
            ),
            open=True, show_drag_handle=True, bgcolor="#00000000",
        )
        self.page.overlay.append(self.sheet)
        self.page.update()

    def _sel_move_job(self, paths: list[str], dest_dir: str, name: str):
        ok, failed = card.classify(paths, dest_dir, self.card_mode)
        if ok:
            self.lib.reindex(paths, dest_dir)
            self.selected.clear()
            self.select_mode = False
            self.toast(f"已{'移动' if self.card_mode == 'move' else '复制'} {ok} 张到「{name}」")
        if failed:
            self.toast(f"{len(failed)} 个文件失败")
        self.render()
        self.page.update()

    def on_new_album_for_multi(self, paths: list[str]):
        field = ft.TextField(
            hint_text="新相册名称", hint_style=ft.TextStyle(size=12, color="#6B5C8C"),
            text_style=ft.TextStyle(size=13, color=TEXT), border=ft.InputBorder.NONE,
            filled=True, bgcolor=GLASS, border_radius=ft.BorderRadius(12, 12, 12, 12),
            content_padding=ft.Padding(12, 0, 12, 0), height=40, autofocus=True,
        )

        def create(ev=None):
            name = (field.value or "").strip()
            if not name:
                self.page.pop_dialog()
                return
            self.page.pop_dialog()
            try:
                d = card.create_album(card.suggest_parent(self.lib.roots), name)
                self.lib.add_root(card.suggest_parent(self.lib.roots))
                self.page.run_thread(self._sel_move_job, paths, d, name)
            except Exception as ex:
                self.toast(f"创建失败：{ex}")

        self.page.show_dialog(
            ft.AlertDialog(
                modal=True,
                title=ft.Text("新建相册", size=15, color=TEXT, weight=ft.FontWeight.W_600),
                content=ft.Column(controls=[
                    field,
                    ft.Text(f"创建后把这 {len(paths)} 张照片移动进去", size=11, color=TEXT_DIM),
                ], spacing=8, tight=True),
                bgcolor="#241040",
                actions=[
                    ft.TextButton(content=ft.Text("取消", size=12, color=TEXT_DIM),
                                  on_click=lambda e: self.page.pop_dialog()),
                    ft.TextButton(content=ft.Text("创建并移动", size=12, color=ACCENT),
                                  on_click=create),
                ],
                actions_alignment=ft.MainAxisAlignment.END,
            )
        )

    def on_sel_rename(self, e=None):
        """重命名单张照片（多选时仅对单张可用）。"""
        if len(self.selected) != 1:
            self.toast("一次只能重命名一张")
            return
        path = next(iter(self.selected))
        photo = self.lib.photos.get(path)
        if not photo:
            return
        stem, ext = os.path.splitext(photo.name)
        field = ft.TextField(
            value=stem, hint_style=ft.TextStyle(size=12, color="#6B5C8C"),
            text_style=ft.TextStyle(size=13, color=TEXT), border=ft.InputBorder.NONE,
            filled=True, bgcolor=GLASS, border_radius=ft.BorderRadius(12, 12, 12, 12),
            content_padding=ft.Padding(12, 0, 12, 0), height=40, autofocus=True,
        )

        def do_rename(ev=None):
            new = (field.value or "").strip()
            if not new or new == stem:
                self.page.pop_dialog()
                return
            self.page.pop_dialog()
            try:
                dest = card.unique_path(os.path.dirname(path), new + ext)
                os.rename(path, dest)
                old = self.lib.photos.pop(path, None)
                if old:
                    st = os.stat(dest)
                    self.lib.photos[dest] = Photo(
                        path=dest, name=new + ext, album=old.album, size=st.st_size,
                        mtime=st.st_mtime, month=month_of(st.st_mtime))
                if path in self.lib.favorites:
                    self.lib.favorites.discard(path)
                    self.lib.favorites.add(dest)
                self.selected.clear()
                self.select_mode = False
                self.toast(f"已重命名为 {new + ext}")
            except OSError as ex:
                self.toast(f"重命名失败：{ex}")
            self.render()
            self.page.update()

        self.page.show_dialog(
            ft.AlertDialog(
                modal=True,
                title=ft.Text("重命名", size=15, color=TEXT, weight=ft.FontWeight.W_600),
                content=ft.Column(controls=[
                    field,
                    ft.Text(f"扩展名 {ext} 保持不变", size=11, color=TEXT_DIM),
                ], spacing=8, tight=True),
                bgcolor="#241040",
                actions=[
                    ft.TextButton(content=ft.Text("取消", size=12, color=TEXT_DIM),
                                  on_click=lambda ev: self.page.pop_dialog()),
                    ft.TextButton(content=ft.Text("确定", size=12, color=ACCENT),
                                  on_click=do_rename),
                ],
                actions_alignment=ft.MainAxisAlignment.END,
            )
        )

    def on_sel_delete(self, e=None):
        """删除所选：先移入回收站，确认后可彻底删除。"""
        paths = list(self.selected)
        if not paths:
            return
        n = len(paths)
        self.confirm(
            "删除所选",
            f"这 {n} 张照片会先移入回收站，可在回收站还原。",
            lambda: self.on_trash(paths),
        )

    def on_block_album(self, album: str):
        def yes():
            self.lib.block_album(album)
            self.lib.photos = {k: v for k, v in self.lib.photos.items() if v.album != album}
            if self.album == album:
                self.album = "ALL"
            self.toast(f"已屏蔽图集「{album}」")
            self.render()
            self.page.update()
        self.confirm("屏蔽图集", f"「{album}」将从扫描结果中隐藏，可在侧边栏「已屏蔽图集」恢复。", yes)

    def on_unblock(self, album: str):
        self.lib.unblock_album(album)
        self.toast("已取消屏蔽，重新扫描后生效")
        self.page.run_thread(self._scan_job)

    def on_month_clean(self, e=None):
        photos = self.lib.select(album=self.album, month=self.month, query=self.query,
                                 favorites_only=self.album == "FAV", sort=self.sort)
        if not photos:
            self.toast("当前没有可清理的照片")
            return
        scope = self.month or self.album_title()
        paths = [p.path for p in photos]

        def yes():
            n = self.lib.move_to_trash(paths)
            self.selected.clear()
            self.select_mode = False
            self.toast(f"已清理 {n} 张到回收站")
            self.render()
            self.page.update()
        self.confirm("按月份专清", f"将「{scope}」的 {len(paths)} 张照片移入回收站？", yes, "清理")

    def on_trash_selected(self, e=None):
        if not self.selected:
            return
        paths = list(self.selected)

        def yes():
            n = self.lib.move_to_trash(paths)
            self.selected.clear()
            self.select_mode = False
            self.toast(f"已清理 {n} 张到回收站")
            self.render()
            self.page.update()
        self.confirm("清理到回收站", f"{len(paths)} 张照片将移入回收站，可随时还原。", yes, "清理")

    def on_trash(self, paths: list[str]):
        def yes():
            n = self.lib.move_to_trash(paths)
            for p in paths:
                self.selected.discard(p)
            if not self.selected:
                self.select_mode = False
            self.toast(f"已清理 {n} 张到回收站")
            self.render()
            self.page.update()
        self.confirm("清理到回收站", f"{len(paths)} 张照片将移入回收站。", yes, "清理")

    def toggle_trash(self, tid: str):
        if tid in self.trash_selected:
            self.trash_selected.discard(tid)
        else:
            self.trash_selected.add(tid)
        self.render_trash()
        self.page.update()

    def on_trash_select_all(self, e):
        if self.trash_selected:
            self.trash_selected.clear()
        else:
            self.trash_selected = {t.id for t in self.lib.trash}
        self.render_trash()
        self.page.update()

    def restore(self, ids: list[str]):
        n = self.lib.restore(list(ids))
        self.trash_selected.clear()
        self.toast(f"已还原 {n} 张")
        self.render()
        self.page.update()

    def on_restore_sel(self, e):
        ids = self.trash_selected or [t.id for t in self.lib.trash]
        if not ids:
            return
        self.restore(list(ids))

    def purge(self, ids: list[str]):
        def yes():
            n = self.lib.delete_forever(list(ids))
            self.trash_selected.clear()
            self.toast(f"已彻底删除 {n} 项")
            self.render()
            self.page.update()
        self.confirm("彻底删除", f"{len(ids)} 项将被永久删除，无法恢复。", yes, "删除")

    def on_purge_sel(self, e):
        ids = self.trash_selected
        if not ids:
            self.toast("请先勾选要删除的项目")
            return
        self.purge(list(ids))

    def on_empty_trash(self, e):
        if not self.lib.trash:
            self.toast("回收站是空的")
            return

        def yes():
            n = self.lib.empty_trash()
            self.toast(f"已清空 {n} 项")
            self.render()
            self.page.update()
        self.confirm("清空回收站", f"{len(self.lib.trash)} 项将被永久删除。", yes, "清空")

    # 设置相关
    def on_add_folder(self, e=None):
        try:
            self.page.run_task(self.picker.get_directory_path, "选择照片目录")
        except Exception:
            self.toast("请手动输入目录路径")

    def on_pick_dir(self, e: ft.FilePickerResultEvent):
        path = getattr(e, "path", None)
        if not path:
            files = getattr(e, "files", None) or []
            if files:
                path = getattr(files[0], "path", None)
        if path:
            self.lib.add_root(path)
            self.toast("已添加目录，正在扫描…")
            self.page.run_thread(self._scan_job)

    def add_root_from_input(self):
        p = (self.new_root.value or "").strip()
        if not p:
            return
        if not os.path.isdir(p):
            self.toast("目录不存在")
            return
        self.lib.add_root(p)
        self.new_root.value = ""
        self.toast("已添加目录，正在扫描…")
        self.page.run_thread(self._scan_job)

    def remove_root(self, path: str):
        self.lib.remove_root(path)
        self.render_settings()
        self.page.update()

    def on_open_app_settings(self, e=None):
        """跳转到系统应用详情页（安卓权限设置）。"""
        try:
            from android.content import Intent  # type: ignore
            from android.provider import Settings  # type: ignore
            from android.net import Uri  # type: ignore
            from android import activity  # type: ignore

            intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.setData(Uri.parse(f"package:{activity.getPackageName()}"))
            activity.startActivity(intent)
        except Exception:
            try:
                import flet_permissions  # type: ignore
            except Exception:
                pass
            self.toast("请在系统设置中授予「照片和视频」权限")

    def on_toggle_preview_actions(self, e):
        self.show_preview_actions = e.control.value
        self.toast("预览快捷操作已" + ("显示" if self.show_preview_actions else "隐藏"))
        self.render_settings()
        self.page.update()

    def on_thumb_size(self, e):
        self.lib.thumb_size = int(e.control.value)
        self.lib.save_config()

    def on_auto_clean(self, e):
        self.lib.auto_clean_days = int(e.control.value)
        self.lib.save_config()

    def on_clear_cache(self, e):
        n = self.lib.clear_thumb_cache()
        self.toast(f"已清除 {n} 个缩略图缓存")
        self.render()
        self.page.update()

    def on_open_dir(self, path: str):
        folder = str(Path(path).parent)
        self.toast(folder)

    def preview(self, path: str):
        photo = self.lib.photos.get(path)
        if not photo:
            return
        img = ft.Image(src=self.lib.cached_thumb(path) or path, fit=ft.BoxFit.CONTAIN, expand=True)
        dlg = ft.AlertDialog(
            modal=True,
            title=ft.Text(photo.name, size=14, color=TEXT),
            content=ft.Column(
                controls=[
                    ft.Container(content=img, height=320, alignment=ft.Alignment(0, 0)),
                    ft.Text(f"{photo.album} · {photo.date_str} · {fmt_size(photo.size)}",
                            size=12, color=TEXT_DIM),
                ],
                spacing=8, tight=True,
            ),
            bgcolor="#241040",
            actions=(
                [
                    ft.TextButton(content=ft.Text("收藏", size=12, color=ACCENT),
                                  on_click=lambda e, p=path: (self.page.pop_dialog(), self.on_fav(p))),
                    ft.TextButton(content=ft.Text("清理", size=12, color=DANGER),
                                  on_click=lambda e, p=path: (self.page.pop_dialog(), self.on_trash([p]))),
                    ft.TextButton(content=ft.Text("屏蔽此图集", size=12, color=DANGER),
                                  on_click=lambda e, a=photo.album: (self.page.pop_dialog(),
                                                                     self.on_block_album(a))),
                ] if self.show_preview_actions else []
            ) + [
                ft.TextButton(content=ft.Text("关闭", size=12, color=TEXT_DIM),
                              on_click=lambda e: self.page.pop_dialog()),
            ],
            actions_alignment=ft.MainAxisAlignment.END,
        )
        self.page.show_dialog(dlg)

    # ------------------------------------------------------------------ 组件
    def confirm(self, heading: str, text: str, on_yes, yes_label: str = "确定"):
        def yes(e):
            self.page.pop_dialog()
            on_yes()

        def no(e):
            self.page.pop_dialog()

        self.page.show_dialog(
            ft.AlertDialog(
                modal=True,
                title=ft.Text(heading, size=15, color=TEXT, weight=ft.FontWeight.W_600),
                content=ft.Text(text, size=13, color=TEXT_DIM),
                bgcolor="#241040",
                actions=[
                    ft.TextButton(content=ft.Text("取消", size=12, color=TEXT_DIM), on_click=no),
                    ft.TextButton(content=ft.Text(yes_label, size=12, color=DANGER), on_click=yes),
                ],
                actions_alignment=ft.MainAxisAlignment.END,
            )
        )

    def toast(self, msg: str):
        self.page.show_dialog(ft.SnackBar(content=ft.Text(msg, size=12, color=TEXT),
                                          bgcolor="#2C1A4D", duration=1800))


def _ts(t: float) -> str:
    from datetime import datetime
    return datetime.fromtimestamp(t).strftime("%Y-%m-%d %H:%M")


def main(page: ft.Page):
    PhotoBox(page)


if __name__ == "__main__":
    # assets 目录可选：不存在时不要传，避免打包阶段因缺目录失败
    _assets = "assets" if os.path.isdir("assets") else None
    if _assets:
        ft.run(main, assets_dir=_assets)
    else:
        ft.run(main)
