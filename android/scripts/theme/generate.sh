#!/usr/bin/env bash
# Regenerates the hfps colour tokens of :core:designsystem for the five palettes (ADR 0019), from the copies of
# hfps-kit's brand/theme.css and brand/palettes.css next to this script (the same tokens as src/index.css).
# `scripts/theme/generate.sh --check` writes nothing and fails when a file is stale or a WCAG pair fails.
set -euo pipefail
cd "$(dirname "$0")/../.."
pkg=dev.hfps.hoard.designsystem.theme
dir=core/designsystem/src/main/java/dev/hfps/hoard/designsystem/theme/tokens
mkdir -p "$dir"
for palette in blue olive violet rose graphite; do
  object="HfpsTokens$(tr '[:lower:]' '[:upper:]' <<<"${palette:0:1}")${palette:1}"
  extra=()
  # The window / splash colours (values/ + values-night/hfps_colors.xml) follow the default palette.
  if [[ $palette == blue ]]; then extra=(--res native/src/main/res); fi
  python3 scripts/theme/convert_theme.py scripts/theme/theme.css --palettes scripts/theme/palettes.css \
    --palette "$palette" --package "$pkg.tokens" --object "$object" --out "$dir/$object.kt" "${extra[@]}" "$@" \
    >/dev/null
done
python3 scripts/theme/palettes_kt.py "$@"
echo "tokens ok: $dir"
