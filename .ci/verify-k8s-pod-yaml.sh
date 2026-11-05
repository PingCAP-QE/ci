#!/bin/sh

set -eu

# Structural validation for Jenkins agent Pod templates
# (jenkins/jobs/<org>/<repo>/<branch>/<job>/pod*.yaml).
#
# What this gate checks:
#   - the file is valid YAML, a `v1` `Pod` with a non-empty containers list
#   - no explicit null nodes (they silently override Pod defaults)
#   - every container image resolves in its registry (crane, best effort)
#
# What this gate intentionally does NOT do: `kubectl apply --dry-run` schema
# validation. Client-side `--validate=strict` is a no-op in current kubectl
# (field validation is server-side), and `--dry-run=server` requires `create
# pods` RBAC that the presubmit ServiceAccount does not have. Enabling it would
# only ever print a permission error or silently skip, so the call was dead
# code and was removed; a fabricated "validated" message would be worse than
# no check. Schema errors are caught when the Jenkins Kubernetes plugin uses
# the template, and the structural checks above run on every changed file.
#
# Usage:
#   .ci/verify-k8s-pod-yaml.sh [file ...]
# With no arguments every `jenkins/jobs/**/pod*.yaml` is checked.

if [ "$#" -gt 0 ]; then
    files="$*"
else
    files=$(
        if [ -d jenkins/jobs ]; then
            find jenkins/jobs -type f -name 'pod*.yaml'
        fi | LC_ALL=C sort
    )
fi

CRANE_BIN=${CRANE_BIN:-crane}

count=0
failed=0
crane_validation_enabled=0

if command -v "$CRANE_BIN" >/dev/null 2>&1; then
    crane_validation_enabled=1
fi

validate_images_with_crane() {
    file=$1

    images=$(yq e '.spec.containers[].image, .spec.initContainers[].image' "$file" 2>/dev/null | grep -v '^$' | grep -v '^null$' | sort -u || true)
    if [ -z "$images" ]; then
        return
    fi

    for image in $images; do
        if ! "$CRANE_BIN" digest "$image" >/dev/null 2>&1; then
            echo "$file: image not found: $image"
            failed=1
        fi
    done
}

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

    if [ "$crane_validation_enabled" -eq 1 ]; then
        validate_images_with_crane "$file"
    fi
}

for file in $files; do
    check_file "$file"
done

if [ "$failed" -ne 0 ]; then
    exit 1
fi

echo "Checked $count pipeline Pod YAML files: all passed."
