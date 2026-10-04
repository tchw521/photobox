"""光影相册 PhotoBox - 主题与通用组件

深紫渐变 + 半透明玻璃卡片，风格对齐参考 UI。
"""

from __future__ import annotations

import flet as ft

# ---------- 配色 ----------
BG_TOP = "#1A0B2E"
BG_BOTTOM = "#2B1155"
GLASS = "#1FFFFFFF"          # 半透明白（Flet 支持 #AARRGGBB）
GLASS_STRONG = "#2BFFFFFF"
STROKE = "#18FFFFFF"
ACCENT = "#A855F7"
ACCENT_SOFT = "#7C3AED"
TEXT = "#F2EEFF"
TEXT_DIM = "#A79EC8"
DANGER = "#F87171"

RADIUS = 18

BG_GRADIENT = ft.LinearGradient(
    begin=ft.Alignment(-1, -1),
    end=ft.Alignment(1, 1),
    colors=["#180A2B", "#2A1150", "#160A26"],
)


def glass_card(content: ft.Control, padding: int = 12, radius: int = RADIUS,
               bgcolor: str = GLASS, on_click=None, expand: bool | int = False,
               height: int | None = None, width: int | None = None) -> ft.Container:
    return ft.Container(
        content=content,
        padding=ft.Padding(padding, padding, padding, padding),
        bgcolor=bgcolor,
        border_radius=ft.BorderRadius(radius, radius, radius, radius),
        border=ft.Border.all(1, STROKE),
        clip_behavior=ft.ClipBehavior.ANTI_ALIAS,
        on_click=on_click,
        expand=expand,
        height=height,
        width=width,
    )


def hbox(controls, alignment=ft.MainAxisAlignment.START, spacing=8, **kw) -> ft.Row:
    return ft.Row(controls=controls, alignment=alignment, spacing=spacing, **kw)


def vbox(controls, alignment=ft.MainAxisAlignment.START, spacing=8, **kw) -> ft.Column:
    return ft.Column(controls=controls, alignment=alignment, spacing=spacing, **kw)


def label(text: str, size: int = 13, color: str = TEXT_DIM, weight=None) -> ft.Text:
    return ft.Text(text, size=size, color=color, weight=weight, max_lines=1,
                   overflow=ft.TextOverflow.ELLIPSIS)


def title(text: str, size: int = 17, color: str = TEXT) -> ft.Text:
    return ft.Text(text, size=size, color=color, weight=ft.FontWeight.W_600,
                   max_lines=1, overflow=ft.TextOverflow.ELLIPSIS)


def icon_btn(icon, on_click=None, color: str = TEXT, size: int = 20,
             tooltip: str | None = None, bgcolor: str | None = None) -> ft.IconButton:
    return ft.IconButton(icon=icon, icon_color=color, icon_size=size,
                         on_click=on_click, tooltip=tooltip, bgcolor=bgcolor)


def chip(text: str, selected: bool = False, on_click=None) -> ft.Container:
    return ft.Container(
        content=ft.Text(text, size=12,
                        color=TEXT if selected else TEXT_DIM,
                        weight=ft.FontWeight.W_500 if selected else None),
        padding=ft.Padding(10, 6, 10, 6),
        bgcolor=ACCENT if selected else GLASS,
        border_radius=ft.BorderRadius(12, 12, 12, 12),
        border=ft.Border.all(1, STROKE),
        on_click=on_click,
    )


def sidebar_item(icon, text: str, count: int | None = None,
                 selected: bool = False, on_click=None) -> ft.Container:
    """侧边栏相册项：无图标，气泡包裹，名称只显示前两字，过长则滚动。"""
    marquee = _marquee_text(text or "", selected)
    return ft.Container(
        content=ft.Row(
            controls=[
                marquee,
                ft.Text(str(count), size=10, color=TEXT_DIM) if count is not None else ft.Text(""),
            ],
            alignment=ft.MainAxisAlignment.SPACE_BETWEEN,
            spacing=4,
            vertical_alignment=ft.CrossAxisAlignment.CENTER,
        ),
        padding=ft.Padding(8, 8, 8, 8),
        bgcolor=ACCENT_SOFT if selected else "#14FFFFFF",
        border=ft.Border.all(1, ACCENT if selected else "#18FFFFFF"),
        border_radius=ft.BorderRadius(16, 16, 16, 16),
        on_click=on_click,
        clip_behavior=ft.ClipBehavior.ANTI_ALIAS,
        tooltip=text,          # 长按/悬停可看完整名称
    )


def _short_album_name(name: str, keep: int = 2) -> str:
    """相册名只保留前两个字。"""
    name = name or ""
    return name[:keep] if len(name) > keep else name


# 需要跑马灯滚动的文本行，由 sidebar_item 注册，主程序驱动
MARQUEE_ROWS: list = []


def clear_marquee():
    """每次重建侧边栏前清空，避免累积旧引用。"""
    MARQUEE_ROWS.clear()


def _marquee_text(full: str, selected: bool) -> ft.Container:
    """气泡内文字：视口只容纳前两个字，更长的名字在里面左右滚动露出余下部分。

    - 名字 ≤2 字：静止居中显示
    - 名字 >2 字：内容仍是完整名，但容器宽度锁死为两字宽（约 30px），
      由主程序的跑马灯任务驱动横向滚动，静止时看到的就是前两个字。
    """
    color = TEXT if selected else TEXT_DIM
    weight = ft.FontWeight.W_600 if selected else ft.FontWeight.W_400
    if len(full) <= 2:
        return ft.Container(
            content=ft.Text(full, size=12, color=color, weight=weight,
                            text_align=ft.TextAlign.CENTER, no_wrap=True),
            alignment=ft.Alignment(0, 0),
            width=34,
        )
    row = ft.Row(
        controls=[ft.Text(full, size=12, color=color, weight=weight, no_wrap=True)],
        scroll=ft.ScrollMode.AUTO,
        tight=True,
    )
    # 估算文字宽度：中文约 12px/字，英文约 7px/字
    w = sum(12 if ord(c) > 127 else 7 for c in full) + 4
    row.data = {"text": full, "width": float(w), "dir": 1, "pos": 0.0}
    MARQUEE_ROWS.append(row)
    return ft.Container(
        content=row,
        alignment=ft.Alignment(-1, 0),
        width=34,          # 只露出前两个字
        clip_behavior=ft.ClipBehavior.ANTI_ALIAS,
    )


def stat_tile(value: str, caption: str) -> ft.Container:
    return glass_card(
        ft.Column(
            controls=[
                ft.Text(value, size=19, color=TEXT, weight=ft.FontWeight.W_700),
                ft.Text(caption, size=11, color=TEXT_DIM),
            ],
            spacing=2,
            horizontal_alignment=ft.CrossAxisAlignment.START,
        ),
        padding=12,
        expand=True,
    )


def empty_hint(icon, text: str, sub: str = "") -> ft.Container:
    return ft.Container(
        content=ft.Column(
            controls=[
                ft.Icon(icon, size=54, color="#4B3B6B"),
                ft.Text(text, size=15, color=TEXT_DIM),
                ft.Text(sub, size=12, color="#6B5C8C") if sub else ft.Text(""),
            ],
            horizontal_alignment=ft.CrossAxisAlignment.CENTER,
            alignment=ft.MainAxisAlignment.CENTER,
            spacing=8,
        ),
        alignment=ft.Alignment(0, 0),
        expand=True,
    )


def fmt_size(n: int) -> str:
    n = float(n)
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return f"{n:.0f} {unit}" if unit == "B" else f"{n:.1f} {unit}"
        n /= 1024
    return f"{n:.1f} GB"
