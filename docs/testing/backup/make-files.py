#!/usr/bin/env python3
"""Synthetic files for the phone backup's emulator run (ADR 0024, phase A1): never real photos.

    python3 make-files.py <out>

<out>/phone/DCIM/Camera   PXL_*.jpg (coloured noise, distinct), one large JPEG (~12 MB, for the interrupted
                          upload), a PNG screenshot-like file, VID_1.mp4 (needs ffmpeg), IMG_0001.CR3 (a fake CR3:
                          an ISO-BMFF 'crx ' header + noise) and IMG_0001.xmp
<out>/phone/Pictures/Screenshots/Screenshot_1.png
<out>/nas/Phone/PXL_20261101_090000.jpg   a byte-identical copy of one phone photo (referenced, not uploaded)
"""
import os
import random
import subprocess
import sys

from PIL import Image

out = sys.argv[1]
phone = os.path.join(out, "phone", "DCIM", "Camera")
shots = os.path.join(out, "phone", "Pictures", "Screenshots")
nas = os.path.join(out, "nas", "Phone")
for d in (phone, shots, nas):
    os.makedirs(d, exist_ok=True)


def noise_jpeg(path, w, h, seed, quality=92):
    rnd = random.Random(seed)
    img = Image.new("RGB", (w, h), (rnd.randrange(256), rnd.randrange(256), rnd.randrange(256)))
    px = img.load()
    for y in range(0, h, 4):
        for x in range(0, w, 4):
            c = (rnd.randrange(256), rnd.randrange(256), rnd.randrange(256))
            for dy in range(4):
                for dx in range(4):
                    if x + dx < w and y + dy < h:
                        px[x + dx, y + dy] = c
    img.save(path, "JPEG", quality=quality)


for i, stamp in enumerate(["20261101_090000", "20261102_100000", "20261103_110000", "20261104_120000"]):
    noise_jpeg(os.path.join(phone, f"PXL_{stamp}.jpg"), 1600, 1200, seed=i)
# Large enough for several 8 MiB chunks: the process is killed in the middle of it.
noise_jpeg(os.path.join(phone, "PXL_20261105_130000_BIG.jpg"), 6000, 4500, seed=99, quality=100)
Image.new("RGB", (1080, 2400), (30, 60, 90)).save(os.path.join(shots, "Screenshot_20261105_140000.png"))
with open(os.path.join(phone, "PXL_20261101_090000.jpg"), "rb") as f:
    data = f.read()
with open(os.path.join(nas, "PXL_20261101_090000.jpg"), "wb") as f:
    f.write(data)
# A fake CR3 (MediaStore does not list it): 'ftyp' 'crx ' then noise.
rnd = random.Random(7)
with open(os.path.join(phone, "IMG_0001.CR3"), "wb") as f:
    f.write(b"\x00\x00\x00\x18ftypcrx \x00\x00\x00\x01crx isom")
    f.write(bytes(rnd.randrange(256) for _ in range(3_000_000)))
with open(os.path.join(phone, "IMG_0001.xmp"), "w") as f:
    f.write('<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">'
            '<rdf:Description xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:Rating="3"/></rdf:RDF></x:xmpmeta>\n')
try:
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc=size=640x360:rate=25", "-t", "4",
                    "-pix_fmt", "yuv420p", os.path.join(phone, "VID_20261105_150000.mp4")], check=True)
except (OSError, subprocess.CalledProcessError) as e:
    print("no video:", e)
print("files in", out)
