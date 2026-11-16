#!/usr/bin/env python3
"""Synthetic files for the A3 emulator run (ADR 0024, "Free up space"): never real photos.

    python3 make-files.py <out>

<out>/phone/DCIM/Camera (taken in August and September 2026, EXIF DateTimeOriginal; today's photo is recent)
    PXL_20260802_101500.jpg            freed whole
    PXL_20260803_181000.{jpg,dng,xmp}  a pair with a sidecar: the JPEG and the DNG by consent, then the sidecar
    PXL_20260815_090000.jpg            its server copy is damaged in the container: verify answers differs, it stays
    PXL_20260816_090000.jpg            named by the simulated Reveal's photo record: kept for Reveal (until freed-adopt)
    PXL_20260901_080000.jpg            first request of the partial consent (allowed)
    PXL_20260902_080000.jpg            second request (denied): stays
    PXL_20261116_090000.jpg            taken today: kept by "Keep the last 30 days"
"""
import os
import random
import sys

from PIL import Image

out = sys.argv[1]
camera = os.path.join(out, "phone", "DCIM", "Camera")
os.makedirs(camera, exist_ok=True)


def noise_jpeg(name, seed, taken, w=1600, h=1200):
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
    exif = Image.Exif()
    exif.get_ifd(0x8769)[0x9003] = taken  # DateTimeOriginal
    img.save(os.path.join(camera, name), "JPEG", quality=92, exif=exif)


noise_jpeg("PXL_20260802_101500.jpg", 1, "2026:08:02 10:15:00")
noise_jpeg("PXL_20260803_181000.jpg", 2, "2026:08:03 18:10:00")
rnd = random.Random(3)
with open(os.path.join(camera, "PXL_20260803_181000.dng"), "wb") as f:
    f.write(b"II*\x00\x08\x00\x00\x00")
    f.write(bytes(rnd.randrange(256) for _ in range(2_500_000)))
with open(os.path.join(camera, "PXL_20260803_181000.xmp"), "w") as f:
    f.write('<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">'
            '<rdf:Description xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:Rating="4"/></rdf:RDF></x:xmpmeta>\n')
noise_jpeg("PXL_20260815_090000.jpg", 4, "2026:08:15 09:00:00")
noise_jpeg("PXL_20260816_090000.jpg", 5, "2026:08:16 09:00:00")
noise_jpeg("PXL_20260901_080000.jpg", 6, "2026:09:01 08:00:00")
noise_jpeg("PXL_20260902_080000.jpg", 7, "2026:09:02 08:00:00")
noise_jpeg("PXL_20261116_090000.jpg", 8, "2026:11:16 09:00:00")
