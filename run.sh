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

scriptloglite_main=se.lu.scriptloglite.ScriptLogLite
scriptloglite_vm_args=()
if [[ "${1:-}" == "--self-test" ]]; then
    shift
    java tools/CompileSources.java --tests
    scriptloglite_main=se.lu.scriptloglite.ScriptLogLiteChecks
    scriptloglite_vm_args=(-Djava.awt.headless=true)
else
    java tools/CompileSources.java
fi
scriptloglite_classpath=target/launcher-classes
if [[ -s "$flatlaf_jar" ]]; then
    scriptloglite_classpath="$scriptloglite_classpath:$flatlaf_jar"
fi
exec java "${scriptloglite_vm_args[@]}" --class-path "$scriptloglite_classpath" "$scriptloglite_main" "$@"
