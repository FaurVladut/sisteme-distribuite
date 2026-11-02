#!/usr/bin/env bash
# Pornește 3 noduri locale (n1 coordonator, n2, n3) în fundal.
# Ieșirea fiecăruia ajunge în logs/<id>.log și este afișată în terminal.
# Ctrl+C oprește toate nodurile. Cu --no-build sare peste compilare.
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ "${1:-}" != "--no-build" ]]; then
    mvn -q package
fi
mkdir -p logs

pids=()
cleanup() {
    kill "${pids[@]}" 2>/dev/null || true
    wait 2>/dev/null || true
}
trap cleanup INT TERM EXIT

start_node() {
    local id=$1 port=$2
    shift 2
    java -cp target/classes ro.ubm.sd.node.StorageNode --id "$id" --port "$port" --dir "data/$id" "$@" \
        > "logs/$id.log" 2>&1 &
    pids+=($!)
}

start_node n1 7001 --coordinator
start_node n2 7002
start_node n3 7003

echo "noduri pornite: ${pids[*]}; clientul: java -cp target/classes ro.ubm.sd.client.Client"
tail -n +1 -f logs/n1.log logs/n2.log logs/n3.log
