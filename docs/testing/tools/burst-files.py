#!/usr/bin/env python3
"""Writes the hand-off burst test's files (docs/testing/handoff-burst.md) into a folder.

burst-<seed>.<ext>: byte at offset o is ((o & 4095) + seed*17 + (o >> 12)*13) & 255, so the client checks any
offset without the file. 20 sidecar-sized (.xmp, 8 KiB), 20 JPEG-sized (.jpg, 3 MiB), 20 RAW-sized (.arw, 24 MiB).
"""
import os
import sys

ROW = bytes(i & 255 for i in range(4096 + 256))


def block(c):
    c &= 255
    return ROW[c:c + 4096]


def write(path, seed, size):
    with open(path, "wb") as f:
        k = 0
        left = size
        while left > 0:
            b = block(seed * 17 + k * 13)
            n = min(left, 4096)
            f.write(b[:n])
            left -= n
            k += 1


def main(out):
    os.makedirs(out, exist_ok=True)
    plan = [("xmp", 8 * 1024), ("jpg", 3 * 1024 * 1024), ("arw", 24 * 1024 * 1024)]
    seed = 1
    for ext, size in plan:
        for _ in range(20):
            write(os.path.join(out, f"burst-{seed}.{ext}"), seed, size)
            seed += 1


if __name__ == "__main__":
    main(sys.argv[1])
