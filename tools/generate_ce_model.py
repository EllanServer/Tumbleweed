#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成风滚草的 craft-engine 物品模型资源:
  - resourcepack/assets/tumbleweed/models/item/tumbleweed.json   (原版元素格式圆柱球近似)
  - resourcepack/assets/tumbleweed/textures/item/tumbleweed.png  (复制自 ModelEngine 纹理)
  - configuration/items.yml                                     (craft-engine 物品定义)
  - pack.yml                                                    (包定义)

模型设计:
  - 16x16x16 原版模型空间,球心 (8,8,8),半径 6 (直径 12/16 = 0.75 格,即 mcSize(size=1))
  - 5 层 x 12 段薄板,绕 Y 轴旋转围成正 12 边形棱柱环,半径按高度呈球面渐变
  - 每段板宽 3.2 (12 边形边长 2*6*sin15° ≈ 3.11,微重叠无缝隙)
  - 元素 rotation.angle 全部 <= 45°,符合原版元素限制
"""
import json
import math
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "src/main/resources/craftengine"
TEX_SRC = ROOT / "src/main/resources/modelengine/textures/tumbleweed.png"

SEGMENTS = 12           # 每层板数
LAYERS = [              # (y0, y1, radius)
    (2.0, 4.4, 3.6),
    (4.4, 6.8, 5.5),
    (6.8, 9.2, 6.0),
    (9.2, 11.6, 5.5),
    (11.6, 14.0, 3.6),
]
THICK = 0.5             # 板厚(径向)
WIDTH = 3.2             # 板宽(切向)

def build_elements():
    elements = []
    for y0, y1, r in LAYERS:
        cy = (y0 + y1) / 2.0
        half = WIDTH / 2.0
        for i in range(SEGMENTS):
            angle = i * (360.0 / SEGMENTS)
            elements.append({
                "from": [r - THICK / 2, y0, 8.0 - half],
                "to": [r + THICK / 2, y1, 8.0 + half],
                "rotation": {
                    "angle": angle,
                    "axis": "y",
                    "origin": [8.0, cy, 8.0],
                },
                "faces": {
                    # 外表面:整张草编纹理
                    "east": {"uv": [0.0, 0.0, 32.0, 32.0], "texture": "#0"},
                    "west": {"uv": [0.0, 0.0, 32.0, 32.0], "texture": "#0"},
                    # 侧面/顶底:窄条纹理
                    "north": {"uv": [0.0, 0.0, 32.0, 4.0], "texture": "#0"},
                    "south": {"uv": [0.0, 0.0, 32.0, 4.0], "texture": "#0"},
                    "up": {"uv": [0.0, 0.0, 32.0, 4.0], "texture": "#0"},
                    "down": {"uv": [0.0, 0.0, 32.0, 4.0], "texture": "#0"},
                },
            })
    return elements

def main():
    model = {
        "textures": {
            "0": "tumbleweed:item/tumbleweed",
            "particle": "tumbleweed:item/tumbleweed",
        },
        "elements": build_elements(),
    }

    model_dir = OUT / "resourcepack/assets/tumbleweed/models/item"
    tex_dir = OUT / "resourcepack/assets/tumbleweed/textures/item"
    cfg_dir = OUT / "configuration"
    model_dir.mkdir(parents=True, exist_ok=True)
    tex_dir.mkdir(parents=True, exist_ok=True)
    cfg_dir.mkdir(parents=True, exist_ok=True)

    (model_dir / "tumbleweed.json").write_text(
        json.dumps(model, indent=2, ensure_ascii=False), encoding="utf-8")

    shutil.copyfile(TEX_SRC, tex_dir / "tumbleweed.png")

    (cfg_dir / "items.yml").write_text(
        "items:\n"
        "  tumbleweed:tumbleweed:\n"
        "    material: paper\n"
        "    data:\n"
        "      item_name: \"<!i><#C8A24B>风滚草\"\n"
        "    model: tumbleweed:item/tumbleweed\n",
        encoding="utf-8")

    (OUT / "pack.yml").write_text(
        "author: Tumbleweed\n"
        "version: 1.0\n"
        "description: Tumbleweed item model for CraftEngine\n"
        "namespace: tumbleweed\n",
        encoding="utf-8")

    print(f"elements: {len(model['elements'])}")
    print(f"written to {OUT}")


if __name__ == "__main__":
    main()
