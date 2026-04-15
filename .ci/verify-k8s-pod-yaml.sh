#!/bin/sh

set -eu

KUBECTL_BIN=${KUBECTL_BIN:-kubectl}
KUBE_SERVICEACCOUNT_TOKEN_PATH=${KUBE_SERVICEACCOUNT_TOKEN_PATH:-/var/run/secrets/kubernetes.io/serviceaccount/token}
POD_YAML_TEST_NAME=${POD_YAML_TEST_NAME:-ci-pod-yaml-validate}
export POD_YAML_TEST_NAME

if [ "$#" -gt 0 ]; then
    files="$*"
else
    files=$(find pipelines -type f \( -name '*.yaml' -o -name '*.yml' \) | LC_ALL=C sort)
fi

count=0
failed=0
kubectl_validation_enabled=0

can_run_kubectl_validation() {
    command -v "$KUBECTL_BIN" >/dev/null 2>&1 &&
    [ -n "${KUBERNETES_SERVICE_HOST:-}" ] &&
    [ -n "${KUBERNETES_SERVICE_PORT:-}" ] &&
    [ -f "$KUBE_SERVICEACCOUNT_TOKEN_PATH" ]
}

validate_with_kubectl() {
    file=$1
    manifest=$(mktemp)
    stderr_file=$(mktemp)

    yq e '.metadata = (.metadata // {}) | .metadata.name = (.metadata.name // strenv(POD_YAML_TEST_NAME))' "$file" >"$manifest"

    if ! "$KUBECTL_BIN" apply --dry-run=client --validate=strict -f "$manifest" >/dev/null 2>"$stderr_file"; then
        echo "$file: kubectl client dry-run validation failed:"
        sed 's/^/  /' "$stderr_file"
        failed=1
    fi

    rm -f "$manifest" "$stderr_file"
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

if can_run_kubectl_validation; then
    kubectl_validation_enabled=1
    echo "kubectl client dry-run validation enabled via in-cluster API discovery."
else
    echo "kubectl client dry-run validation skipped: no in-cluster Kubernetes API context detected."
fi

for file in $files; do
    check_file "$file"
done

if [ "$failed" -ne 0 ]; then
    exit 1
fi

echo "Checked $count pipeline Pod YAML files: all passed."
