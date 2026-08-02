#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成风滚草的 ModelEngine 资源:
  - tumbleweed.png     : 32x32 干草色纹理 (4 个 16x16 区域)
  - tumbleweed.bbmodel : Blockbench 4.x JSON,3 片互插薄板 + root 骨骼

模型结构复刻原版 ModelTumbleweed:3 张垂直/水平薄板沿三个轴交叉,
整体高度 16px = 1 方块,中心略高于地面 (y 4~20,中心 12px)。

用法: python tools/gen_model.py
"""
import json
import os
import random
import struct
import zlib

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEX_DIR = os.path.join(BASE, "src", "main", "resources", "modelengine", "textures")
BP_DIR = os.path.join(BASE, "src", "main", "resources", "modelengine", "blueprints")
TEX_PATH = os.path.join(TEX_DIR, "tumbleweed.png")
BP_PATH = os.path.join(BP_DIR, "tumbleweed.bbmodel")

W, H = 32, 32


def make_texture():
    rng = random.Random(20240607)
    # 基底:干草棕
    base = (139, 111, 62)
    dark = (110, 86, 44)
    green = (104, 112, 54)
    light = (168, 142, 90)

    px = [[None] * W for _ in range(H)]

    def patch(x0, y0, w, h):
        # 每个 16x16 区域:干草基底 + 噪点
        for y in range(y0, y0 + h):
            for x in range(x0, x0 + w):
                r = rng.random()
                if r < 0.10:
                    c = dark
                elif r < 0.22:
                    c = green
                elif r < 0.30:
                    c = light
                else:
                    c = base
                # 横向细茎纹理
                if rng.random() < 0.18:
                    jitter = rng.randint(-1, 1)
                    c = (min(255, c[0] + jitter * 12),
                         min(255, c[1] + jitter * 12),
                         min(255, c[2] + jitter * 12))
                px[y][x] = c

    patch(0, 0, 16, 16)      # 区域 A
    patch(16, 0, 16, 16)     # 区域 B
    patch(0, 16, 16, 16)     # 区域 C
    patch(16, 16, 16, 16)    # 区域 D

    # 少量干枯叶刺点缀 (对角线小草)
    for _ in range(14):
        cx, cy = rng.randint(0, 31), rng.randint(0, 31)
        length = rng.randint(2, 5)
        dx, dy = rng.choice([(1, 0), (-1, 0), (0, 1), (0, -1), (1, 1), (-1, -1)])
        for i in range(length):
            x, y = cx + dx * i, cy + dy * i
            if 0 <= x < 32 and 0 <= y < 32:
                px[y][x] = light

    # PNG 编码
    raw = b""
    for y in range(H):
        raw += b"\x00"
        for x in range(W):
            raw += bytes(px[y][x]) + b"\xff"

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", W, H, 8, 2, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9))
    png += chunk(b"IEND", b"")

    with open(TEX_PATH, "wb") as f:
        f.write(png)
    print("texture ->", TEX_PATH)


def make_bbmodel():
    # 3 片 1px 厚薄板,尺寸 16x16,中心 (8,12,8) 附近,略高于地面
    # 与原版模型一致:沿 XY / XZ / YZ 三个平面
    y0, y1 = 4, 20  # 中心 y=12px (0.75 格)

    def cube(name, fx, fy, fz, tx, ty, tz, uv0, uv1):
        return {
            "name": name,
            "box_uv": True,
            "rescale": False,
            "locked": False,
            "from": [fx, fy, fz],
            "to": [tx, ty, tz],
            "uv_offset": [0.0, 0.0],
            "rotation": {"angle": 0.0, "axis": "x", "origin": [8.0, 12.0, 8.0]},
            "faces": {
                "north": {"uv": uv0, "texture": 0},
                "south": {"uv": uv0, "texture": 0},
                "east": {"uv": uv1, "texture": 0},
                "west": {"uv": uv1, "texture": 0},
                "up": {"uv": uv0, "texture": 0},
                "down": {"uv": uv0, "texture": 0},
            },
            "type": "cube",
            "uuid": None,
        }

    # 各面的 16x16 UV 区域 (32x32 纹理)
    region_a = [0.0, 0.0, 16.0, 16.0]   # (0,0)-(16,16)
    region_b = [16.0, 0.0, 32.0, 16.0]  # (16,0)-(32,16)
    region_c = [0.0, 16.0, 16.0, 32.0]  # (0,16)-(16,32)

    el_xy = cube("board_xy", 0, y0, -8, 1, y1, 8, region_a, region_b)    # 竖板 X 方向
    el_xz = cube("board_xz", -8, y0, 0, 8, y1, 1, region_b, region_c)    # 竖板 Z 方向
    el_yz = cube("board_yz", -8, y0, -8, 8, y1 + 1, 8, region_c, region_a)  # 水平板

    elements = [el_xy, el_xz, el_yz]

    bb = {
        "meta": {
            "format_version": "4.5",
            "model_format": "free",
            "box_uv": True,
        },
        "name": "tumbleweed",
        "model_identifier": "tumbleweed",
        "box_uv": True,
        "resolution": {"width": 32, "height": 32},
        "visible_box": [0.0, 0.0, 0.0],
        "elements": elements,
        "textures": [
            {
                "path": "",
                "name": "tumbleweed",
                "source": "tumbleweed.png",
                "uuid": "00000000-0000-0000-0000-000000000001",
                "particle": True,
                "render_mode": "default",
                "render_sides": "double",
                "frame_time": 1.0,
                "frame_order_type": "loop",
                "frame_interpolate": False,
                "visible": True,
                "internal": True,
            }
        ],
        "animations": [],
        "outliner": [{"name": "root", "origin": [0.0, 0.0, 0.0], "uuid": "00000000-0000-0000-0000-000000000002", "children": [0, 1, 2]}],
        "display": {},
        "overrides": [],
    }

    with open(BP_PATH, "w", encoding="utf-8") as f:
        json.dump(bb, f, indent=2)
    print("bbmodel ->", BP_PATH)


if __name__ == "__main__":
    make_texture()
    make_bbmodel()
