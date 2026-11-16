"""Synthetic test photos for the trash's Samba container (never real photos): five coloured JPEGs and a sidecar."""
import os
import sys

from PIL import Image, ImageDraw

out = os.path.join(sys.argv[1] if len(sys.argv) > 1 else ".", "files")
os.makedirs(out, exist_ok=True)
for n, colour in [(1, (200, 40, 40)), (2, (40, 160, 40)), (3, (40, 40, 200)), (4, (180, 180, 40)), (5, (120, 40, 160))]:
    im = Image.new("RGB", (640, 480), colour)
    ImageDraw.Draw(im).text((20, 20), f"synthetic {n}", fill=(255, 255, 255))
    im.save(os.path.join(out, f"DSC0000{n}.JPG"), "JPEG", quality=85)
with open(os.path.join(out, "DSC00001.xmp"), "w") as f:
    f.write('<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">'
            '<rdf:Description xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:Rating="-1"/></rdf:RDF></x:xmpmeta>\n')
