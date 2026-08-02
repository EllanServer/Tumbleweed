#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成风滚草的 craft-engine 家具资源 (原版 ModelTumbleweed 9 板编织球结构):
  - resourcepack/assets/tumbleweed/models/item/tumbleweed.json  (9 板交叉薄板)
  - resourcepack/assets/tumbleweed/textures/item/tumbleweed.png (原版 16x16 纹理)
  - configuration/items.yml                                    (家具物品定义)
  - configuration/furniture/tumbleweed.yml                     (家具定义:显示元素 + interaction hitbox)
  - pack.yml                                                   (包定义)

原版模型结构 (konwboj/Tumbleweed, LGPL-3.0) — ModelTumbleweed.java 4 组 9 块零厚度板:
  组 1 (无旋转)        : YZ 板 (x=0) + XZ 板 (y=0) + XY 板 (z=0)
  组 2 (rotateAngleY 45): YZ 板 + XY 板
  组 3 (rotateAngleZ 45): YZ 板 + XZ 板
  组 4 (rotateAngleX 45): XZ 板 + XY 板

模型空间:16x16x16 (blockbench 坐标 0..16),球心 (8,8,8) -> 0.5 格;
渲染矩阵由插件每 tick 设置:T(0, mcSize/2, 0) * 中心旋转 * S (球心与碰撞箱中心对齐)。
板厚 0.2 (原版零厚度板用薄板近似,同 ModelEngine 蓝图)。
"""
import json
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "src/main/resources/craftengine"
TEX_SRC = ROOT / "model-engine-vendor/tumbleweed.png"   # 原版纹理 (vendor 目录)

THICK = 0.2      # 板厚 (格,0..16 空间)
UV = [0.0, 0.0, 16.0, 16.0]


def faces():
    return {
        "east": {"uv": UV, "texture": "#0"},
        "west": {"uv": UV, "texture": "#0"},
        "north": {"uv": UV, "texture": "#0"},
        "south": {"uv": UV, "texture": "#0"},
        "up": {"uv": UV, "texture": "#0"},
        "down": {"uv": UV, "texture": "#0"},
    }


def build_elements():
    """9 块交叉薄板:YZ/XZ/XY 正交组 + 绕 Y/Z/X 各 45° 组 (原版 ModelTumbleweed)。"""
    elements = []

    def yz_plate(name):
        # x=0 平面 (原版 addBox(0,-8,-8, 0,16,16))
        return {"name": name,
                "from": [8.0 - THICK / 2, 0.0, 0.0],
                "to": [8.0 + THICK / 2, 16.0, 16.0],
                "rotation": {"angle": 0.0, "axis": "y", "origin": [8.0, 8.0, 8.0]},
                "faces": faces()}

    def xz_plate(name):
        # y=0 平面 (原版 addBox(-8,0,-8, 16,0,16))
        return {"name": name,
                "from": [0.0, 8.0 - THICK / 2, 0.0],
                "to": [16.0, 8.0 + THICK / 2, 16.0],
                "rotation": {"angle": 0.0, "axis": "y", "origin": [8.0, 8.0, 8.0]},
                "faces": faces()}

    def xy_plate(name):
        # z=0 平面 (原版 addBox(-8,-8,0, 16,16,0))
        return {"name": name,
                "from": [0.0, 0.0, 8.0 - THICK / 2],
                "to": [16.0, 16.0, 8.0 + THICK / 2],
                "rotation": {"angle": 0.0, "axis": "y", "origin": [8.0, 8.0, 8.0]},
                "faces": faces()}

    groups = [
        ("group_ortho", [yz_plate("yz_0"), xz_plate("xz_0"), xy_plate("xy_0")], None),
        ("group_y45", [yz_plate("yz_45y"), xy_plate("xy_45y")], ("y", 45)),
        ("group_z45", [yz_plate("yz_45z"), xz_plate("xz_45z")], ("z", 45)),
        ("group_x45", [xz_plate("xz_45x"), xy_plate("xy_45x")], ("x", 45)),
    ]
    for gname, plates, rot in groups:
        for plate in plates:
            if rot is not None:
                plate["rotation"]["angle"] = float(rot[1])
                plate["rotation"]["axis"] = rot[0]
            elements.append(plate)
    return elements


ITEMS_YML = """items:
  tumbleweed:tumbleweed:
    material: paper
    data:
      item_name: "<!i><#C8A24B>风滚草"
    model: tumbleweed:item/tumbleweed
"""

FURNITURE_YML = """items:
  tumbleweed:tumbleweed:
    material: paper
    data:
      item_name: "<!i><#C8A24B>风滚草"
    model: tumbleweed:item/tumbleweed
furniture:
  tumbleweed:tumbleweed:
    settings:
      item: tumbleweed:tumbleweed
      hit_times: 1
    variants:
      default:
        elements:
          - item: tumbleweed:tumbleweed
            display_transform: none
            billboard: fixed
            position: 0,0,0
            translation: 0,0,0
        hitboxes:
          - type: interaction
            can_use_item_on: true
            can_be_hit_by_projectile: true
            blocks_building: false
            position: 0,0,0
            width: 1.2
            height: 1.2
            interactive: true
            invisible: true
"""

PACK_YML = """author: Tumbleweed
version: 1.0
description: Tumbleweed furniture for CraftEngine
namespace: tumbleweed
"""


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
    fur_dir = cfg_dir / "furniture"
    model_dir.mkdir(parents=True, exist_ok=True)
    tex_dir.mkdir(parents=True, exist_ok=True)
    cfg_dir.mkdir(parents=True, exist_ok=True)
    fur_dir.mkdir(parents=True, exist_ok=True)

    (model_dir / "tumbleweed.json").write_text(
        json.dumps(model, indent=2, ensure_ascii=False), encoding="utf-8")
    shutil.copyfile(TEX_SRC, tex_dir / "tumbleweed.png")
    (cfg_dir / "items.yml").write_text(ITEMS_YML, encoding="utf-8")
    (fur_dir / "tumbleweed.yml").write_text(FURNITURE_YML, encoding="utf-8")
    (OUT / "pack.yml").write_text(PACK_YML, encoding="utf-8")

    print(f"elements: {len(model['elements'])}")
    print(f"written to {OUT}")


if __name__ == "__main__":
    main()
