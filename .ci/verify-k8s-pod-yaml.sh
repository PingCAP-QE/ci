#!/bin/sh

set -eu

if [ "$#" -gt 0 ]; then
    files="$*"
else
    files=$(find pipelines -type f \( -name '*.yaml' -o -name '*.yml' \) | LC_ALL=C sort)
fi

count=0
failed=0

check_file() {
    file=$1
    count=$((count + 1))

    if ! yq e '.' "$file" >/dev/null 2>&1; then
        echo "$file: invalid YAML"
        failed=1
        return
    fi

    if ! yq -e '.apiVersion == "v1"' "$file" >/dev/null 2>&1; then
        echo "$file: expected apiVersion: v1"
        failed=1
    fi

    if ! yq -e '.kind == "Pod"' "$file" >/dev/null 2>&1; then
        echo "$file: expected kind: Pod"
        failed=1
    fi

    if ! yq -e '.spec | type == "!!map"' "$file" >/dev/null 2>&1; then
        echo "$file: spec must be a mapping"
        failed=1
    fi

    if ! yq -e '.spec.containers | type == "!!seq" and length > 0' "$file" >/dev/null 2>&1; then
        echo "$file: spec.containers must be a non-empty sequence"
        failed=1
    fi

    null_paths=$(yq e '.. | select(tag == "!!null") | (path | join("."))' "$file" 2>/dev/null | sed '/^$/d' || true)
    if [ -n "$null_paths" ]; then
        echo "$file: explicit null nodes are not allowed in Pod YAML:"
        echo "$null_paths" | sed 's/^/  - /'
        failed=1
    fi
}

for file in $files; do
    check_file "$file"
done

if [ "$failed" -ne 0 ]; then
    exit 1
fi

echo "Checked $count pipeline Pod YAML files: all passed."
