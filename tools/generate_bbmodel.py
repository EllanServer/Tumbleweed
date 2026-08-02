#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从原版 Tumbleweed mod (konwboj/Tumbleweed, LGPL-3.0) 的 ModelTumbleweed.java
重建 ModelEngine 蓝图 tumbleweed.bbmodel (BlockBench free 格式)。

原版模型结构 (4 组 ModelRenderer,9 块零厚度板):
  组 1 (无旋转)        : YZ 板 (x=0) + XZ 板 (y=0) + XY 板 (z=0)
  组 2 (rotateAngleY 45): YZ 板 + XY 板
  组 3 (rotateAngleZ 45): YZ 板 + XZ 板
  组 4 (rotateAngleX 45): XZ 板 + XY 板

坐标转换:
  - 原版模型空间 -8..8 (中心原点) 保留,厚度 0 -> 0.2 (±0.1)
  - 原版渲染 GlStateManager.translate(y + 0.25F):模型中心在实体脚底上方
    0.25 格 -> bbmodel 全部 y 坐标平移 +4 (4px = 0.25 格)
  - 旋转中心 = 模型原点 (0,0,0) -> bbmodel 坐标 (0,4,0)
  - 纹理:原版 16x16 textures/entity/tumbleweed.png
"""
import json
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = ROOT / "src/main/resources/modelengine/blueprints"
TEX_DIR = ROOT / "src/main/resources/modelengine/textures"
TEX_SRC = ROOT / "model-engine-vendor/tumbleweed.png"   # 原版纹理 (vendor 目录)

TEX_PATH = "tumbleweed.png"
THICK = 0.2

# 每组: (名称, 板列表, 旋转)
# 板: (name, from, to)  - from/to 为 bbmodel 坐标 (y 已 +4)
def yz_plate(name):   # x=0 平面 (原版 addBox(0,-8,-8, 0,16,16))
    return (name, [-THICK / 2, -4, -8], [THICK / 2, 12, 8])

def xz_plate(name):   # y=0 平面 (原版 addBox(-8,0,-8, 16,0,16))
    return (name, [-8, 4 - THICK / 2, -8], [8, 4 + THICK / 2, 8])

def xy_plate(name):   # z=0 平面 (原版 addBox(-8,-8,0, 16,16,0))
    return (name, [-8, -4, -THICK / 2], [8, 12, THICK / 2])

GROUPS = [
    ("group_ortho",  [yz_plate("yz_0"), xz_plate("xz_0"), xy_plate("xy_0")], None),
    ("group_y45",    [yz_plate("yz_45y"), xy_plate("xy_45y")], ("y", 45)),
    ("group_z45",    [yz_plate("yz_45z"), xz_plate("xz_45z")], ("z", 45)),
    ("group_x45",    [xz_plate("xz_45x"), xy_plate("xy_45x")], ("x", 45)),
]

def face(name, texture):
    uv = [0.0, 0.0, 16.0, 16.0]
    return {
        "north": {"uv": uv, "texture": texture},
        "south": {"uv": uv, "texture": texture},
        "east": {"uv": uv, "texture": texture},
        "west": {"uv": uv, "texture": texture},
        "up": {"uv": uv, "texture": texture},
        "down": {"uv": uv, "texture": texture},
    }

def main():
    elements = []
    children = []
    elem_index = 0
    for gname, plates, rot in GROUPS:
        for name, frm, to in plates:
            element = {
                "name": name,
                "box_uv": True,
                "rescale": False,
                "locked": False,
                "from": frm,
                "to": to,
                "uv_offset": [0.0, 0.0],
                "rotation": {
                    "angle": 0.0,
                    "axis": "x",
                    "origin": [0.0, 4.0, 0.0],
                },
                "faces": face(name, 0),
                "type": "cube",
                "uuid": None,
            }
            if rot is not None:
                element["rotation"]["angle"] = float(rot[1])
                element["rotation"]["axis"] = rot[0]
            elements.append(element)
            children.append(elem_index)
            elem_index += 1

    model = {
        "meta": {
            "format_version": "4.5",
            "model_format": "free",
            "box_uv": True,
        },
        "name": "tumbleweed",
        "model_identifier": "tumbleweed",
        "box_uv": True,
        "resolution": {"width": 16, "height": 16},
        "visible_box": [0.0, 0.0, 0.0],
        "elements": elements,
        "textures": [
            {
                "path": "",
                "name": "tumbleweed",
                "source": TEX_PATH,
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
        "outliner": [
            {
                "name": "root",
                "origin": [0.0, 0.0, 0.0],
                "uuid": "00000000-0000-0000-0000-000000000002",
                "children": children,
            }
        ],
        "display": {},
        "overrides": [],
    }

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    TEX_DIR.mkdir(parents=True, exist_ok=True)
    (OUT_DIR / "tumbleweed.bbmodel").write_text(
        json.dumps(model, indent=2, ensure_ascii=False), encoding="utf-8")
    shutil.copyfile(TEX_SRC, TEX_DIR / "tumbleweed.png")

    print(f"elements: {len(elements)}")
    print(f"written: {OUT_DIR / 'tumbleweed.bbmodel'}")


if __name__ == "__main__":
    main()
