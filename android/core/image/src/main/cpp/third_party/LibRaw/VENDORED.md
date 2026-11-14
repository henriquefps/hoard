# LibRaw (vendored)

- Upstream: https://github.com/LibRaw/LibRaw, tag **0.22.3**
  (`https://github.com/LibRaw/LibRaw/archive/refs/tags/0.22.3.tar.gz`,
  sha256 `7edf96c5b70cff88980a02ab0fc8af125cb6a4a377985573c654439d9d02355c`).
- Copied unmodified: `src/` (minus its `Makefile`), `libraw/`, `internal/`, `COPYRIGHT`,
  `LICENSE.LGPL`, `LICENSE.CDDL`, `README.md`. Nothing else from the release is used.
- License: LibRaw is dual-licensed **LGPL-2.1** or **CDDL-1.0** (at the user's choice); Hoard uses it
  under LGPL-2.1, which is compatible with Hoard's AGPL-3.0-or-later. See `COPYRIGHT`.
- Built by `../../CMakeLists.txt` as a static library linked into `libhoardimage.so` (no RawSpeed,
  DNG SDK, libjpeg, zlib, LCMS or OpenMP: lossy-JPEG DNG and a few exotic formats are not decoded;
  Sony, Canon, Nikon, Fuji, Panasonic, Olympus, Pentax and lossless DNG are).
- To update: replace these folders with a newer release's, update the tag and checksum here and in
  `docs/adr/0006-image-core.md`, rebuild, and rerun the image harness (ADR 0006).
