#!/usr/bin/env bash
set -euo pipefail

scriptloglite_root=$(cd -- "$(dirname -- "$0")" && pwd)
cd "$scriptloglite_root"
flatlaf_version=3.7
flatlaf_jar=".deps/flatlaf-${flatlaf_version}.jar"

# Nimbus needs no download. Opt in to fetching the optional FlatLaf themes.
if [[ "${1:-}" == "--with-flatlaf" ]]; then
    shift
    if [[ ! -s "$flatlaf_jar" ]]; then
    mkdir -p .deps
    flatlaf_download=$(mktemp .deps/flatlaf-download.XXXXXX)
    trap 'rm -f "$flatlaf_download"' EXIT
    curl --fail --location --show-error --silent --connect-timeout 15 --max-time 120 \
        "https://repo.maven.apache.org/maven2/com/formdev/flatlaf/${flatlaf_version}/flatlaf-${flatlaf_version}.jar" \
        --output "$flatlaf_download"
    mv -- "$flatlaf_download" "$flatlaf_jar"
    trap - EXIT
    fi
fi

if [[ -s "$flatlaf_jar" ]]; then
    exec java --class-path "$flatlaf_jar" src/main/java/se/lu/scriptloglite/ScriptLogLite.java "$@"
fi
exec java src/main/java/se/lu/scriptloglite/ScriptLogLite.java "$@"
