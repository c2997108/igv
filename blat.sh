#!/usr/bin/env bash
set -euo pipefail

# Usage: blat.sh <DB> <SEQUENCE>
#   DB:      genome database path or identifier (passed raw from IGV)
#   SEQUENCE:query sequence string (passed raw from IGV)
# set IGV Preferences > Advanced > BLAT URL
#    /path/to/igv/blat.sh $DB $SEQUENCE

DB="$1"
SEQ="$2"

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

qry="$tmpdir/query.fa"
psl="$tmpdir/out.psl"

cat > "$qry" <<EOF
>YourSeq
$SEQ
EOF

# Adjust options to your BLAT installation as needed.
# Redirect BLAT progress output away from stdout so IGV receives only JSON.
blat "$DB" "$qry" "$psl" -out=pslx 1>&2

python3 - "$psl" <<'PY'
import json, sys
rows = []
with open(sys.argv[1]) as f:
    for line in f:
        s = line.strip()
        if not s:
            continue
        # Skip header lines (allowing for leading spaces)
        if s.startswith("psLayout") or s.startswith("match"):
            continue
        if s[0].isalpha():
            continue

        cols = s.split('\t')
        # PSLX may append query/target sequences; keep first 21 fields.
        if len(cols) < 21:
            continue
        cols = cols[:21]

        row = [int(cols[0]), int(cols[1]), int(cols[2]), int(cols[3]),
               int(cols[4]), int(cols[5]), int(cols[6]), int(cols[7]),
               cols[8], cols[9], int(cols[10]), int(cols[11]), int(cols[12]),
               cols[13], int(cols[14]), int(cols[15]), int(cols[16]),
               int(cols[17]), cols[18].rstrip(','), cols[19].rstrip(','), cols[20].rstrip(',')]
        rows.append(row)
json.dump({"blat": rows}, sys.stdout)
PY
