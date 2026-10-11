#!/usr/bin/env python3
"""Rebuild FocusDuo PNG/ICO from its two-circle SVG with Python 3, stdlib only.

Run from any directory: python desktop/scripts/generate-app-icon.py
The SVG's 88 x 72 artwork is centered in an 88 x 88 transparent canvas.
PNG frames in the ICO are supported by the targeted Windows 10/11 platform.
"""

from __future__ import annotations

import argparse
import binascii
from pathlib import Path
import struct
import xml.etree.ElementTree as ET
import zlib


SIZES = (16, 24, 32, 48, 64, 128, 256)
SAMPLES = 8


def chunk(kind: bytes, content: bytes) -> bytes:
    body = kind + content
    return struct.pack(">I", len(content)) + body + struct.pack(">I", binascii.crc32(body) & 0xFFFFFFFF)


def rasterize(source: Path, size: int) -> bytes:
    root = ET.parse(source).getroot()
    min_x, min_y, width, height = map(float, root.attrib["viewBox"].split())
    canvas = max(width, height)
    offset_x, offset_y = (canvas - width) / 2, (canvas - height) / 2
    circles = []
    for node in root:
        if node.tag.rsplit("}", 1)[-1] != "circle":
            continue
        rgb = tuple(int(node.attrib["fill"][i:i + 2], 16) for i in (1, 3, 5))
        circles.append((float(node.attrib["cx"]), float(node.attrib["cy"]), float(node.attrib["r"]) ** 2,
                        rgb, float(node.attrib.get("fill-opacity", "1"))))
    if len(circles) != 2:
        raise ValueError("The icon renderer expects the FocusDuo SVG's two circles.")
    rows = bytearray()
    total = SAMPLES * SAMPLES
    for y in range(size):
        rows.append(0)  # PNG filter: none; no nondeterministic metadata.
        for x in range(size):
            sums = [0.0, 0.0, 0.0, 0.0]
            for sy in range(SAMPLES):
                py = (y + (sy + 0.5) / SAMPLES) * canvas / size - offset_y + min_y
                for sx in range(SAMPLES):
                    px = (x + (sx + 0.5) / SAMPLES) * canvas / size - offset_x + min_x
                    premult = [0.0, 0.0, 0.0]
                    alpha = 0.0
                    for cx, cy, radius2, rgb, opacity in circles:
                        if (px - cx) ** 2 + (py - cy) ** 2 <= radius2:
                            premult = [rgb[i] * opacity + premult[i] * (1 - opacity) for i in range(3)]
                            alpha = opacity + alpha * (1 - opacity)
                    for i in range(3):
                        sums[i] += premult[i]
                    sums[3] += alpha
            alpha_sum = sums[3]
            rgba = [round(sums[i] / alpha_sum) if alpha_sum else 0 for i in range(3)]
            rgba.append(round(255 * alpha_sum / total))
            rows.extend(rgba)
    header = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(bytes(rows), 9)) + chunk(b"IEND", b"")


def main() -> None:
    default_dir = Path(__file__).resolve().parents[1] / "src/main/resources/icons"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=default_dir)
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    frames = [(size, rasterize(default_dir / "focusduo.svg", size)) for size in SIZES]
    offset = 6 + 16 * len(frames)
    directory = bytearray(struct.pack("<HHH", 0, 1, len(frames)))
    for size, frame in frames:
        directory.extend(struct.pack("<BBBBHHII", size % 256, size % 256, 0, 0, 1, 32, len(frame), offset))
        offset += len(frame)
    (args.output_dir / "focusduo.ico").write_bytes(bytes(directory) + b"".join(frame for _, frame in frames))
    (args.output_dir / "focusduo.png").write_bytes(frames[-1][1])
    print(f"Generated focusduo.ico ({', '.join(map(str, SIZES))}) and focusduo.png (256) in {args.output_dir}")


if __name__ == "__main__":
    main()
