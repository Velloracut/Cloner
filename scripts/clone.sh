#!/usr/bin/env bash
set -euo pipefail
TEMPLATE="${1:?template}"
OUTPUT="${2:?output}"
APP_NAME="${3:?app name}"
APPLICATION_ID="${4:?application id}"
VERSION_NAME="${5:?version name}"
VERSION_CODE="${6:?version code}"

rm -rf "$OUTPUT"
mkdir -p "$OUTPUT"
cp -R "$TEMPLATE"/. "$OUTPUT"/

python3 - "$OUTPUT" "$APP_NAME" "$APPLICATION_ID" "$VERSION_NAME" "$VERSION_CODE" <<'PY'
from pathlib import Path
import sys
root = Path(sys.argv[1])
replacements = {
    "__APP_NAME__": sys.argv[2],
    "__APPLICATION_ID__": sys.argv[3],
    "__VERSION_NAME__": sys.argv[4],
    "__VERSION_CODE__": sys.argv[5],
}
for p in root.rglob("*"):
    if not p.is_file() or p.suffix not in {".kt",".kts",".xml",".gradle",".properties",".json",".txt"}:
        continue
    try:
        s = p.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        continue
    for a,b in replacements.items():
        s = s.replace(a,b)
    p.write_text(s, encoding="utf-8")
PY
echo "Clone generated at $OUTPUT"
