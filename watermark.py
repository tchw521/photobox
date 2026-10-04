"""水印 / 相框 / 滤镜 渲染引擎（PIL 实现，无第三方重型依赖）

参考 photoo v3.3 的能力设计：
  · 玻璃面板水印边框      glass
  · 主体描边水印边框      outline
  · 冲破边框水印          breakout
  · 滤镜预览 / 色彩系统（渐变色、半屏色彩、纯色自定义）
  · 波点样式（支持自定义文本 / Emoji）
  · 内置 100+ 精选美术风格提示词
输出为一张新的 JPEG，保存到「光影相册/创作」目录，不改动原图。
"""

from __future__ import annotations

import os
import random
import time
from datetime import datetime
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

# ---------------------------------------------------------------- 字体
_FONT_CANDIDATES = [
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc",
    "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",
    "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
    "/system/fonts/NotoSansCJK-Regular.ttc",
    "/system/fonts/Roboto-Regular.ttf",
    "C:/Windows/Fonts/msyh.ttc",
    "C:/Windows/Fonts/arial.ttf",
]
_FONT_CACHE: dict[int, ImageFont.FreeTypeFont] = {}


def _font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    key = (size, bold)
    if key in _FONT_CACHE:
        return _FONT_CACHE[key]
    paths = _FONT_CANDIDATES
    if bold:
        paths = [p for p in _FONT_CANDIDATES if "Bold" in p or "msyh" in p] + _FONT_CANDIDATES
    for p in paths:
        if os.path.exists(p):
            try:
                f = ImageFont.truetype(p, size)
                _FONT_CACHE[key] = f
                return f
            except Exception:
                continue
    f = ImageFont.load_default()
    _FONT_CACHE[key] = f
    return f


# ---------------------------------------------------------------- 风格提示词库（100+）
STYLE_PROMPTS = [
    "胶片复古", "日系清新", "港风霓虹", "赛博朋克", "蒸汽波", "莫兰迪", "奶油ins",
    "黑金质感", "北欧极简", "法式浪漫", "美式复古", "英伦学院", "国风水墨", "敦煌壁画",
    "浮世绘", "波普艺术", "孟菲斯", "包豪斯", "装饰艺术", "洛可可", "巴洛克", "哥特暗黑",
    "蒸汽朋克", "废土朋克", "极地寒调", "沙漠暖阳", "雨林青翠", "海边盐系", "城市夜景",
    "霓虹街拍", "雨天情绪", "雪国静谧", "樱花季", "枫叶季", "薰衣草田", "向日葵田",
    "怀旧DV", "CCD 质感", "拍立得", "宝丽来", "撕拉片", "半格相机", "双重曝光",
    "柔焦梦幻", "丁达尔光", "逆光发丝", "漏光胶片", "颗粒噪点", "低饱和高级灰",
    "高饱和糖果", "黑白纪实", "银盐颗粒", "铂金印相", "蓝晒法", "湿版摄影",
    "油画笔触", "水彩晕染", "版画肌理", "粉笔质感", "炭笔素描", "钢笔速写",
    "像素风", "低多边形", "故障艺术", "全息镭射", "液态金属", "毛玻璃", "液态渐变",
    "极光流动", "星云爆炸", "月球表面", "深海蓝调", "熔岩橙红", "薄荷清凉",
    "蜜桃乌龙", "焦糖布丁", "抹茶奶绿", "草莓牛奶", "葡萄汽水", "柠檬气泡",
    "清晨薄雾", "黄昏暖光", "蓝调时刻", "正午硬光", "室内窗光", "烛光氛围",
    "演唱会光斑", "烟花轨迹", "光绘涂鸦", "长曝流水", "星轨", "车流尾灯",
    "老上海", "老北京胡同", "港式茶餐厅", "日式居酒屋", "韩系奶油", "泰式热带",
    "地中海白蓝", "摩洛哥瓷砖", "波西米亚", "田园碎花", "工装硬核", "山系户外",
    "露营篝火", "公路旅行", "机车风", "滑板少年", "街头涂鸦", "篮球场",
    "校园时光", "毕业季", "婚礼纪实", "亲子温情", "宠物日常", "猫咪视角",
    "美食特写", "咖啡拉花", "甜品诱惑", "静物摆拍", "建筑几何", "极简留白",
    "对称美学", "框架构图", "前景虚化", "慢门追焦", "微距世界", "航拍视角",
]


def suggest_prompt(seed: str | None = None) -> str:
    if seed:
        return STYLE_PROMPTS[abs(hash(seed)) % len(STYLE_PROMPTS)]
    return random.choice(STYLE_PROMPTS)


# ---------------------------------------------------------------- 滤镜
FILTERS = ["原图", "黑白", "胶片", "冷调", "暖调", "褪色", "高对比", "柔光", "暗角"]


def _lut_apply(im: Image.Image, name: str) -> Image.Image:
    if name == "原图" or name not in FILTERS:
        return im
    r, g, b = im.split()
    if name == "黑白":
        return im.convert("L").convert("RGB")
    if name == "胶片":
        r = r.point(lambda v: min(255, int(v * 1.06 + 8)))
        b = b.point(lambda v: int(v * 0.94 + 6))
        return Image.merge("RGB", (r, g.point(lambda v: int(v * 1.0)), b))
    if name == "冷调":
        return Image.merge("RGB", (r.point(lambda v: int(v * 0.94)), g,
                                   b.point(lambda v: min(255, int(v * 1.10 + 6)))))
    if name == "暖调":
        return Image.merge("RGB", (r.point(lambda v: min(255, int(v * 1.12 + 4))),
                                   g.point(lambda v: int(v * 1.02)), b.point(lambda v: int(v * 0.92))))
    if name == "褪色":
        out = []
        for ch in (r, g, b):
            ch = ch.point(lambda v: int(200 * (v / 255) ** 0.85 + 40))
            out.append(ch)
        return Image.merge("RGB", tuple(out))
    if name == "高对比":
        return im.point(lambda v: min(255, max(0, int((v - 128) * 1.35 + 128))))
    if name == "柔光":
        return Image.blend(im, im.filter(ImageFilter.GaussianBlur(6)), 0.45)
    if name == "暗角":
        w, h = im.size
        mask = Image.new("L", (w, h), 0)
        ImageDraw.Draw(mask).ellipse((-w * 0.18, -h * 0.18, w * 1.18, h * 1.18), fill=255)
        mask = mask.filter(ImageFilter.GaussianBlur(w // 6))
        dark = Image.new("RGB", (w, h), (12, 6, 24))
        return Image.composite(im, dark, mask)
    return im


# ---------------------------------------------------------------- 色彩系统
def _hex2rgb(s: str) -> tuple[int, int, int]:
    s = (s or "#A855F7").lstrip("#")
    if len(s) == 3:
        s = "".join(c * 2 for c in s)
    try:
        return (int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16))
    except ValueError:
        return (168, 85, 247)


def apply_color(im: Image.Image, mode: str, c1: str, c2: str) -> Image.Image:
    """mode: none | gradient | half | solid"""
    if mode == "none":
        return im
    a, b = _hex2rgb(c1), _hex2rgb(c2)
    w, h = im.size
    if mode == "solid":
        layer = Image.new("RGB", (w, h), a)
        return Image.blend(im, layer, 0.35)
    if mode == "gradient":
        grad = Image.new("RGB", (w, h))
        d = ImageDraw.Draw(grad)
        for y in range(h):
            t = y / max(1, h - 1)
            d.line([(0, y), (w, y)],
                   fill=(int(a[0] + (b[0] - a[0]) * t),
                         int(a[1] + (b[1] - a[1]) * t),
                         int(a[2] + (b[2] - a[2]) * t)))
        return Image.blend(im, grad, 0.38)
    if mode == "half":
        grad = Image.new("RGB", (w, h))
        d = ImageDraw.Draw(grad)
        for y in range(h):
            t = 0.0 if y < h / 2 else 1.0
            d.line([(0, y), (w, y)],
                   fill=(int(a[0] + (b[0] - a[0]) * t),
                         int(a[1] + (b[1] - a[1]) * t),
                         int(a[2] + (b[2] - a[2]) * t)))
        return Image.blend(im, grad, 0.42)
    return im


# ---------------------------------------------------------------- 波点样式
def apply_polka(im: Image.Image, text: str, color: str = "#FFFFFF",
                size: int = 26, density: int = 10, opacity: int = 120,
                enabled: bool = True) -> Image.Image:
    """半透明波点阵列，每颗点内是自定义文本 / Emoji。"""
    if not enabled or not text:
        return im
    w, h = im.size
    layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    f = _font(max(10, size // 3))
    rgb = _hex2rgb(color)
    step = max(28, int(min(w, h) * 16 / max(1, density) / 8))
    for y in range(0, h, step):
        for x in range(0, w, step):
            d.ellipse((x + 2, y + 2, x + size + 2, y + size + 2),
                      fill=(rgb[0], rgb[1], rgb[2], opacity))
            d.text((x + size / 2, y + size / 2), text[:2], font=f,
                   fill=(0, 0, 0, opacity + 60), anchor="mm")
    return Image.alpha_composite(im.convert("RGBA"), layer).convert("RGB")


# ---------------------------------------------------------------- 圆角
def _rounded(im: Image.Image, radius: int) -> Image.Image:
    im = im.convert("RGBA")
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, im.size[0] - 1, im.size[1] - 1),
                                           radius=radius, fill=255)
    out = Image.new("RGBA", im.size, (0, 0, 0, 0))
    out.paste(im, (0, 0), mask)
    return out


def _shadow(base: Image.Image, box: tuple[int, int, int, int], radius: int,
            blur: int = 22, alpha: int = 120) -> Image.Image:
    shadow = Image.new("RGBA", base.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle(box, radius=radius, fill=(0, 0, 0, alpha))
    shadow = shadow.filter(ImageFilter.GaussianBlur(blur))
    return Image.alpha_composite(base.convert("RGBA"), shadow)


# ---------------------------------------------------------------- 模板渲染
def _info_lines(photo, prompt: str, custom: str) -> list[tuple[str, int, bool]]:
    """返回 [(文本, 字号, 是否加粗)]"""
    dt = datetime.fromtimestamp(photo.mtime) if photo else datetime.now()
    lines: list[tuple[str, int, bool]] = []
    if custom:
        lines.append((custom, 30, True))
    lines.append((dt.strftime("%Y.%m.%d  %H:%M"), 22, False))
    meta = f"{photo.album} · {prompt}" if photo else prompt
    lines.append((meta, 18, False))
    return lines


def _draw_text_block(d: ImageDraw.ImageDraw, x: int, y: int, w: int,
                     lines: list[tuple[str, int, bool]], color: tuple[int, int, int]):
    cy = y
    for text, size, bold in lines:
        f = _font(size, bold)
        d.text((x, cy), text, font=f, fill=color, anchor="la")
        cy += int(size * 1.55)
    return cy - y


def render_glass(im: Image.Image, photo, prompt: str, custom: str,
                 c1: str, c2: str) -> Image.Image:
    """玻璃面板水印边框：图下方留白 + 半透明磨砂信息面板。"""
    w, h = im.size
    pad = int(w * 0.055)
    panel_h = int(h * 0.20) + 60
    canvas = Image.new("RGB", (w + pad * 2, h + pad * 2 + panel_h), (18, 10, 32))
    # 背景渐变
    bg = apply_color(Image.new("RGB", canvas.size, (24, 12, 44)), "gradient", c1, c2)
    canvas.paste(bg, (0, 0))
    blurred = im.filter(ImageFilter.GaussianBlur(int(w * 0.05))).resize(canvas.size)
    canvas = Image.blend(canvas, blurred, 0.35)
    canvas = canvas.convert("RGBA")

    inner = _rounded(im, int(w * 0.035))
    canvas.alpha_composite(inner, (pad, pad))

    # 玻璃面板
    px, py = pad, pad + h + int(panel_h * 0.28)
    pw, ph = w, panel_h - int(panel_h * 0.28)
    glass = Image.new("RGBA", (pw, ph), (255, 255, 255, 34))
    gd = ImageDraw.Draw(glass)
    gd.rounded_rectangle((0, 0, pw - 1, ph - 1), radius=int(ph * 0.24),
                         fill=(255, 255, 255, 40), outline=(255, 255, 255, 70), width=2)
    canvas.alpha_composite(glass, (px, py))
    d = ImageDraw.Draw(canvas)
    _draw_text_block(d, px + int(pw * 0.06), py + int(ph * 0.24), pw,
                     _info_lines(photo, prompt, custom), (244, 240, 255))
    return canvas.convert("RGB")


def render_outline(im: Image.Image, photo, prompt: str, custom: str,
                   c1: str, c2: str) -> Image.Image:
    """主体描边水印边框：提取边缘生成描边 + 渐变描边框。"""
    w, h = im.size
    pad = int(w * 0.07)
    canvas = Image.new("RGBA", (w + pad * 2, h + pad * 2 + 90), (20, 11, 36, 255))
    edges = im.convert("L").filter(ImageFilter.FIND_EDGES).point(lambda v: 255 if v > 42 else 0)
    outline_rgb = Image.merge("RGB", (edges, edges, edges))
    a = _hex2rgb(c1)
    tinted = Image.new("RGB", outline_rgb.size, a)
    outline_layer = Image.composite(tinted, Image.new("RGB", outline_rgb.size, (0, 0, 0)),
                                    edges.point(lambda v: min(255, v)))
    body = Image.blend(im, outline_layer, 0.35)
    canvas.alpha_composite(_rounded(body, int(w * 0.04)), (pad, pad))
    d = ImageDraw.Draw(canvas)
    d.rounded_rectangle((pad * 0.5, pad * 0.5, w + pad * 1.5, h + pad * 1.5),
                        radius=int(w * 0.05), outline=_hex2rgb(c2), width=max(3, int(w * 0.012)))
    _draw_text_block(d, pad, pad + h + 26, w,
                     _info_lines(photo, prompt, custom), (238, 232, 255))
    return canvas.convert("RGB")


def render_breakout(im: Image.Image, photo, prompt: str, custom: str,
                    c1: str, c2: str) -> Image.Image:
    """冲破边框：大面积留白画布，图片放大并溢出边界。"""
    w, h = im.size
    cw, ch = int(w * 1.02), int(h * 1.28)
    canvas = Image.new("RGB", (cw, ch), (246, 243, 252))
    bg = apply_color(Image.new("RGB", (cw, ch), (246, 243, 252)), "gradient", c1, c2)
    canvas = Image.blend(canvas, bg, 0.30)
    canvas = canvas.convert("RGBA")
    iw = int(cw * 1.16)
    ih = int(iw * h / w)
    big = im.resize((iw, ih))
    ox, oy = (cw - iw) // 2, int(ch * 0.10)
    canvas = _shadow(canvas, (ox + 8, oy + 12, ox + iw - 8, oy + ih - 12), int(cw * 0.04))
    canvas.alpha_composite(_rounded(big, int(cw * 0.03)), (ox, oy))
    d = ImageDraw.Draw(canvas)
    y = min(oy + ih + int(ch * 0.03), ch - int(ch * 0.16))
    _draw_text_block(d, int(cw * 0.07), y, int(cw * 0.86),
                     _info_lines(photo, prompt, custom), (60, 40, 90))
    return canvas.convert("RGB")


TEMPLATES = {
    "glass": ("玻璃面板", render_glass),
    "outline": ("主体描边", render_outline),
    "breakout": ("冲破边框", render_breakout),
}


def render(photo, out_dir: str, template: str = "glass", filt: str = "原图",
           color_mode: str = "none", color1: str = "#A855F7", color2: str = "#22D3EE",
           prompt: str = "", custom_text: str = "", polka_text: str = "",
           polka_enabled: bool = False, max_side: int = 1600) -> str:
    """完整渲染流水线，返回输出文件路径。"""
    with Image.open(photo.path) as src:
        try:
            from PIL import ImageOps
            src = ImageOps.exif_transpose(src)
        except Exception:
            pass
        im = src.convert("RGB")
        ratio = max_side / max(im.size)
        if ratio < 1:
            im = im.resize((int(im.size[0] * ratio), int(im.size[1] * ratio)))

    im = _lut_apply(im, filt)
    im = apply_color(im, color_mode, color1, color2)
    if polka_enabled:
        im = apply_polka(im, polka_text)
    fn = TEMPLATES.get(template, TEMPLATES["glass"])[1]
    out = fn(im, photo, prompt or suggest_prompt(photo.path), custom_text, color1, color2)

    Path(out_dir).mkdir(parents=True, exist_ok=True)
    name = Path(photo.path).stem
    dest = Path(out_dir) / f"{name}_{template}_{int(time.time() * 1000) % 100000}.jpg"
    out.save(dest, "JPEG", quality=92)
    return str(dest)


def preview_thumb(photo, cache_dir: str, **kw) -> str | None:
    """生成低分辨率预览图，用于创作页实时预览。"""
    key = f"{Path(photo.path).stem}_{kw.get('template','glass')}_{kw.get('filt','原图')}_{kw.get('color_mode')}"
    dest = Path(cache_dir) / f"wm_{abs(hash(key))}.jpg"
    if dest.exists():
        return str(dest)
    try:
        with Image.open(photo.path) as src:
            src.load()
            im = src.convert("RGB")
            im.thumbnail((420, 420))
            tmp_dir = Path(cache_dir) / "tmp"
            tmp_dir.mkdir(parents=True, exist_ok=True)
            tmp = tmp_dir / "p.jpg"
            im.save(tmp, "JPEG", quality=80)
        from types import SimpleNamespace
        mini = SimpleNamespace(path=str(tmp), mtime=photo.mtime,
                               album=photo.album, name=photo.name)
        p = render(mini, str(Path(cache_dir) / "wm"), max_side=420, **kw)
        im2 = Image.open(p)
        im2.thumbnail((360, 360))
        im2.save(dest, "JPEG", quality=80)
        return str(dest)
    except Exception:
        return None
