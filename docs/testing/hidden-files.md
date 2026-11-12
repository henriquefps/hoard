# Culling ignores hidden files (1.9.2)

- **Device**: own emulator `hoard-hidden` (API 36.1 arm64), port 5590, `-no-window`; shut down after. Never the phone.
- **Folder** `/sdcard/Pictures/hiddentest`: 3 synthetic RAW+JPEG pairs (`DSC01..03.ARW/.JPG`, gradient JPEGs; the ARW is a
  minimal TIFF with the JPEG as preview) plus 4 KB random AppleDouble copies (`._DSC0n.ARW`, `._DSC0n.JPG`), `.DS_Store`
  and `._.DS_Store`: 14 files.
- **Result**: log `HoardCull: open 3 shots`, three `HoardImage: analysis DSC0n.JPG` lines, no failures; the page shows
  "All · 3", three RAW+JPG tiles, "All shots analysed" ([screenshot](hidden-files/culling-3-shots.png)).
  Before the fix the same folder opened 6 shots (3 grey `._DSC0n` pairs).
- JVM: `CullLogicTest` (hidden files never shots), `CullSidecarsTest` (hidden `.X.xmp` still read). JS: `model.test.ts`,
  `handoff.test.ts`.
