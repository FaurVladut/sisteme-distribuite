#!/usr/bin/env bash
# Obiectivul de integritate din docs/cerinte.md: încarcă și descarcă N fișiere aleatoare
# și numără descărcările cu conținut diferit. Nodurile trebuie să ruleze deja
# (scripts/start-local.sh). Utilizare: scripts/integrity-test.sh [N] [dimensiune-KiB]
set -euo pipefail
cd "$(dirname "$0")/.."

count=${1:-100}
kib=${2:-256}
work=out/integrity
rm -rf "$work"
mkdir -p "$work/src" "$work/dst"

commands=""
for i in $(seq 1 "$count"); do
    head -c $((kib * 1024)) /dev/urandom > "$work/src/test-$i.bin"
    commands+="put $work/src/test-$i.bin"$'\n'
    commands+="get test-$i.bin $work/dst"$'\n'
    commands+="rm test-$i.bin"$'\n'
done
commands+="exit"$'\n'

printf '%s' "$commands" | java -cp target/classes ro.ubm.sd.client.Client > "$work/client.log"

bad=0
for i in $(seq 1 "$count"); do
    if ! cmp -s "$work/src/test-$i.bin" "$work/dst/test-$i.bin"; then
        bad=$((bad + 1))
    fi
done
echo "$count fisiere de $kib KiB: $bad diferite sau lipsa (jurnal: $work/client.log)"
[[ $bad -eq 0 ]]
