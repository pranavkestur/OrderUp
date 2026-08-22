#!/usr/bin/env bash
# Re-download the current Nifty 50 and Nifty Smallcap 250 constituents from niftyindices.com
# and rewrite the two watchlist files. Run this once a quarter after NSE rebalances.
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$HERE/src/main/resources/watchlist"           # source tree (baked into next JAR build)
RUNTIME="$HERE/watchlists"                          # runtime hot-reload dir
mkdir -p "$RUNTIME"
UA='Mozilla/5.0'

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "Downloading Nifty 50 …"
curl -fsSL -A "$UA" 'https://niftyindices.com/IndexConstituent/ind_nifty50list.csv' -o "$TMP/n50.csv"
echo "Downloading Nifty Smallcap 250 …"
curl -fsSL -A "$UA" 'https://niftyindices.com/IndexConstituent/ind_niftysmallcap250list.csv' -o "$TMP/nsm.csv"

extract() { tail -n +2 "$1" | awk -F',' '$4=="EQ"{print $3}' | tr -d '\r' | sort -u ; }

write_list() {
  local csv="$1" name="$2" label="$3" url="$4"
  local tmpfile="$TMP/$name"
  {
    echo "# $label — refreshed $(date '+%Y-%m-%d')"
    echo "# Source: $url"
    extract "$csv"
  } > "$tmpfile"
  cp "$tmpfile" "$SRC/$name"
  cp "$tmpfile" "$RUNTIME/$name"
}

write_list "$TMP/n50.csv" nifty50.txt          "Nifty 50 constituents"          "https://niftyindices.com/IndexConstituent/ind_nifty50list.csv"
write_list "$TMP/nsm.csv" nifty_smallcap.txt   "Nifty Smallcap 250 constituents" "https://niftyindices.com/IndexConstituent/ind_niftysmallcap250list.csv"

wc -l "$SRC/nifty50.txt" "$SRC/nifty_smallcap.txt"
echo "Wrote runtime copies to: $RUNTIME"
echo "Done."

