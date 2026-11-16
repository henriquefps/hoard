#!/usr/bin/env python3
"""Synthetic files for the A2 emulator run (ADR 0024, "Other devices see backups"): never real photos.

    python3 make-files.py <out>

<out>/phone/DCIM/Camera   PXL_20261110_100000.{jpg,dng,xmp}  a whole shot: JPEG, a fake DNG (TIFF header + noise), a sidecar
                          PXL_20261111_110000.jpg            deleted on the phone later (the badge on the tablet)
                          PXL_20261112_120000.jpg            a plain photo
                          PXL_20261113_130000_BIG.jpg        ~10 MB, opened on the tablet (streamed by range)
"""
import os
import random
import sys

from PIL import Image

out = sys.argv[1]
camera = os.path.join(out, "phone", "DCIM", "Camera")
os.makedirs(camera, exist_ok=True)


def noise_jpeg(path, w, h, seed, quality=92):
    rnd = random.Random(seed)
    img = Image.new("RGB", (w, h))
    px = img.load()
    base = (rnd.randrange(256), rnd.randrange(256), rnd.randrange(256))
    for y in range(0, h, 8):
        for x in range(0, w, 8):
            c = tuple((b + rnd.randrange(-40, 40)) % 256 for b in base)
            for dy in range(8):
                for dx in range(8):
                    if x + dx < w and y + dy < h:
                        px[x + dx, y + dy] = c
    img.save(path, "JPEG", quality=quality)


noise_jpeg(os.path.join(camera, "PXL_20261110_100000.jpg"), 1600, 1200, 1)
rnd = random.Random(2)
with open(os.path.join(camera, "PXL_20261110_100000.dng"), "wb") as f:
    f.write(b"II*\x00\x08\x00\x00\x00")
    f.write(bytes(rnd.randrange(256) for _ in range(2_500_000)))
with open(os.path.join(camera, "PXL_20261110_100000.xmp"), "w") as f:
    f.write('<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">'
            '<rdf:Description xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:Rating="4"/></rdf:RDF></x:xmpmeta>\n')
noise_jpeg(os.path.join(camera, "PXL_20261111_110000.jpg"), 1600, 1200, 3)
noise_jpeg(os.path.join(camera, "PXL_20261112_120000.jpg"), 1600, 1200, 4)
noise_jpeg(os.path.join(camera, "PXL_20261113_130000_BIG.jpg"), 5000, 3750, 5, quality=100)
