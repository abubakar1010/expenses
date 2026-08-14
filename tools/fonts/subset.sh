#!/usr/bin/env bash
# Regenerates app/src/main/res/font/plex_mono_medium.ttf.
#
# 05-ui-ux-guide.md §4.1 turns the font budget into the type strategy: bundle
# exactly one face, subsetted to the characters the app sets large, and take the
# system faces (Roboto, Noto Sans Bengali) for everything else at zero cost.
#
# Requires: pip install fonttools brotli
set -euo pipefail
cd "$(dirname "$0")"

UPSTREAM=upstream/IBMPlexMono-Medium.ttf
OUT=../../app/src/main/res/font/plex_mono_medium.ttf

if [ ! -f "$UPSTREAM" ]; then
  mkdir -p upstream
  curl -sSL -o "$UPSTREAM" \
    https://raw.githubusercontent.com/google/fonts/main/ofl/ibmplexmono/IBMPlexMono-Medium.ttf
fi

# The guide lists 0-9 ৳ , . − + — but IBM Plex Mono is a Latin/Greek/Cyrillic
# family and has no U+09F3 BENGALI RUPEE SIGN, so ৳ is not in this subset. That
# costs nothing: §4.3 already sets the symbol as a separate 0.7em `ink-soft`
# span, which resolves through the system Noto Sans Bengali.
#
# --name-IDs='*' keeps the name table, including the OFL notice and licence URL.
# The licence requires it and it costs a few hundred bytes.
python -m fontTools.subset "$UPSTREAM" \
  --unicodes="U+0030-0039,U+002C,U+002E,U+002B,U+2212" \
  --layout-features="" \
  --name-IDs="*" \
  --drop-tables+=DSIG \
  --output-file="$OUT"

echo "wrote $OUT ($(wc -c < "$OUT") bytes; budget is ~12-18 KB)"
