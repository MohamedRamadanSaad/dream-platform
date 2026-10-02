#!/usr/bin/env bash
# Sets KEY=value lines (read from stdin) in an env file IN PLACE: existing keys keep their position, missing keys are
# appended, values are compared exactly (no reordering, no shell evaluation of values).
# usage: printf 'KEY=value\n...' | bash setenv.sh /opt/saadat/deploy/backend.env   → prints "updated" or "unchanged"
set -euo pipefail
file=$1
touch "$file"
changed=0
while IFS= read -r line || [ -n "$line" ]; do
  [ -z "$line" ] && continue
  key=${line%%=*}
  value=${line#*=}
  if grep -q "^${key}=" "$file"; then
    current=$(grep -m1 "^${key}=" "$file")
    current=${current#*=}
    [ "$current" = "$value" ] && continue
    tmp=$(mktemp)
    K="$key" V="$value" awk 'BEGIN { k = ENVIRON["K"]; v = ENVIRON["V"] } index($0, k "=") == 1 { print k "=" v; next } { print }' "$file" > "$tmp"
    cat "$tmp" > "$file"
    rm -f "$tmp"
  else
    printf '%s=%s\n' "$key" "$value" >> "$file"
  fi
  changed=1
done
if [ "$changed" = 1 ]; then echo updated; else echo unchanged; fi
