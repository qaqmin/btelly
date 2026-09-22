# -*- coding: utf-8 -*-
"""生成 B视机（BTelly）应用图标与启动品牌图（青碧深色族）。
输出：
  app/src/main/res/mipmap-nodpi/ic_launcher.png        432x432 legacy 兼容底（full-bleed）
  app/src/main/res/drawable-nodpi/ic_brand_splash.png  432x432 透明底品牌标（启动链自绘）
"""
from PIL import Image, ImageDraw, ImageFilter

SIZE = 432
SURFACE = (12, 15, 22)        # #0C0F16
SURFACE_HI = (18, 26, 42)     # 渐变上沿
ACCENT = (0, 194, 168)        # #00C2A8
ACCENT_DEEP = (14, 140, 140)  # #0E8C8C


def rounded_gradient_screen(size, r):
    """圆角矩形，纵向青碧渐变。"""
    w, h = size
    grad = Image.new("RGB", (1, h))
    for y in range(h):
        t = y / max(h - 1, 1)
        grad.putpixel((0, y), tuple(int(ACCENT[i] + (ACCENT_DEEP[i] - ACCENT[i]) * t) for i in range(3)))
    grad = grad.resize((w, h))
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, w - 1, h - 1], radius=r, fill=255)
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    out.paste(grad, (0, 0), mask)
    return out, mask


def make_mark(scale=1.0):
    """品牌标：圆角屏（内深色三角）+ 底座。透明底，画布 SIZE。"""
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    cx = SIZE / 2

    # 屏幕：宽 300 高 210（按 96 视口等比放大），圆角 56
    sw, sh, sr = int(300 * scale), int(210 * scale), int(56 * scale)
    sx, sy = int(cx - sw / 2), int(96 * scale)
    screen, _ = rounded_gradient_screen((sw, sh), sr)
    img.alpha_composite(screen, (sx, sy))

    # 播放三角：深色（表面色）圆角三角，居中偏左一点视觉居中
    tri_w, tri_h = int(88 * scale), int(100 * scale)
    tx, ty = cx - tri_w / 2 + int(6 * scale), sy + sh / 2 - tri_h / 2
    tri = Image.new("RGBA", (tri_w, tri_h), (0, 0, 0, 0))
    td = ImageDraw.Draw(tri)
    r = int(10 * scale)
    # 圆角三角：外接圆角矩形裁三个角
    td.rounded_rectangle([0, 0, tri_w - 1, tri_h - 1], radius=r, fill=SURFACE + (255,))
    # 切掉右上/右下角形成三角（用透明多边形覆盖太硬，改用三点路径+圆角近似：直接画带圆角多边形）
    tri = Image.new("RGBA", (tri_w, tri_h), (0, 0, 0, 0))
    td = ImageDraw.Draw(tri)
    pad = int(2 * scale)
    td.polygon([(pad, r), (tri_w - pad, tri_h / 2), (pad, tri_h - r)], fill=SURFACE + (255,))
    # 圆角化：轻微模糊抗锯齿即可（小尺寸下足够）
    img.alpha_composite(tri, (int(tx), int(ty)))

    # 中柱
    neck_w, neck_h = int(16 * scale), int(14 * scale)
    nd = ImageDraw.Draw(img)
    nd.rounded_rectangle(
        [cx - neck_w / 2, sy + sh - int(2 * scale), cx + neck_w / 2, sy + sh + neck_h],
        radius=int(3 * scale), fill=ACCENT + (255,),
    )

    # 底座
    base_w, base_h = int(96 * scale), int(16 * scale)
    by = sy + sh + neck_h - int(1 * scale)
    nd.rounded_rectangle(
        [cx - base_w / 2, by, cx + base_w / 2, by + base_h],
        radius=int(8 * scale), fill=ACCENT + (255,),
    )
    return img


def make_launcher():
    """legacy 全出血方块：深色渐变底 + 顶部微光 + 品牌标。"""
    img = Image.new("RGBA", (SIZE, SIZE), SURFACE + (255,))
    # 纵向微渐变底
    top = Image.new("RGBA", (SIZE, SIZE), SURFACE_HI + (255,))
    mask_v = Image.new("L", (1, SIZE))
    for y in range(SIZE):
        t = y / (SIZE - 1)
        mask_v.putpixel((0, y), int(255 * (1 - t) * 0.55))
    img = Image.composite(top, img, mask_v.resize((SIZE, SIZE)))

    # 中心青碧微光（径向）
    glow = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    gd.ellipse([SIZE * 0.12, -SIZE * 0.28, SIZE * 0.88, SIZE * 0.52], fill=ACCENT + (26,))
    glow = glow.filter(ImageFilter.GaussianBlur(60))
    img.alpha_composite(glow)

    img.alpha_composite(make_mark(scale=1.0))
    return img


def main():
    launcher = make_launcher()
    launcher.save("app/src/main/res/mipmap-nodpi/ic_launcher.png")

    splash = make_mark(scale=1.28)  # 启动屏自绘视图用大号透明底标
    splash.save("app/src/main/res/drawable-nodpi/ic_brand_splash.png")
    print("ok", launcher.size, splash.size)


if __name__ == "__main__":
    main()
