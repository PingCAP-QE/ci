#!/bin/sh

set -eu

KUBECTL_BIN=${KUBECTL_BIN:-kubectl}
KUBE_SERVICEACCOUNT_TOKEN_PATH=${KUBE_SERVICEACCOUNT_TOKEN_PATH:-/var/run/secrets/kubernetes.io/serviceaccount/token}
KUBE_SERVICEACCOUNT_NAMESPACE_PATH=${KUBE_SERVICEACCOUNT_NAMESPACE_PATH:-/var/run/secrets/kubernetes.io/serviceaccount/namespace}
POD_YAML_TEST_NAME=${POD_YAML_TEST_NAME:-ci-pod-yaml-validate}
export POD_YAML_TEST_NAME

if [ -z "${POD_YAML_TEST_NAMESPACE:-}" ] && [ -f "$KUBE_SERVICEACCOUNT_NAMESPACE_PATH" ]; then
    POD_YAML_TEST_NAMESPACE=$(tr -d '\n' < "$KUBE_SERVICEACCOUNT_NAMESPACE_PATH")
fi
POD_YAML_TEST_NAMESPACE=${POD_YAML_TEST_NAMESPACE:-}
export POD_YAML_TEST_NAMESPACE

if [ "$#" -gt 0 ]; then
    files="$*"
else
    files=$(find pipelines -type f \( -name '*.yaml' -o -name '*.yml' \) | LC_ALL=C sort)
fi

count=0
failed=0
kubectl_validation_enabled=1

validate_with_kubectl() {
    file=$1
    manifest=$(mktemp)
    manifest_with_ns=$(mktemp)
    stderr_file=$(mktemp)

    yq e '.metadata = (.metadata // {}) | .metadata.name = (.metadata.name // strenv(POD_YAML_TEST_NAME))' "$file" >"$manifest"
    if [ -n "$POD_YAML_TEST_NAMESPACE" ]; then
        yq e '.metadata.namespace = (.metadata.namespace // strenv(POD_YAML_TEST_NAMESPACE))' "$manifest" >"$manifest_with_ns"
        mv "$manifest_with_ns" "$manifest"
    fi

    if ! "$KUBECTL_BIN" apply --dry-run=client --validate=strict -f "$manifest" >/dev/null 2>"$stderr_file"; then
        echo "$file: kubectl client dry-run validation failed:"
        sed 's/^/  /' "$stderr_file"
        failed=1
    fi

    # : >"$stderr_file"
    # if ! "$KUBECTL_BIN" apply --dry-run=server --validate=strict -f "$manifest" >/dev/null 2>"$stderr_file"; then
    #     echo "$file: kubectl server dry-run validation failed:"
    #     sed 's/^/  /' "$stderr_file"
    #     failed=1
    # fi

    rm -f "$manifest" "$manifest_with_ns" "$stderr_file"
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

    if [ "$kubectl_validation_enabled" -eq 1 ]; then
        validate_with_kubectl "$file"
    fi
}

for file in $files; do
    check_file "$file"
done

if [ "$failed" -ne 0 ]; then
    exit 1
fi

echo "Checked $count pipeline Pod YAML files: all passed."
