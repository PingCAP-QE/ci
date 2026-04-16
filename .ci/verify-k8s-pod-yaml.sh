#!/bin/sh

set -eu

KUBECTL_BIN=${KUBECTL_BIN:-kubectl}
KUBE_SERVICEACCOUNT_TOKEN_PATH=${KUBE_SERVICEACCOUNT_TOKEN_PATH:-/var/run/secrets/kubernetes.io/serviceaccount/token}
KUBE_SERVICEACCOUNT_NAMESPACE_PATH=${KUBE_SERVICEACCOUNT_NAMESPACE_PATH:-/var/run/secrets/kubernetes.io/serviceaccount/namespace}
KUBE_SERVICEACCOUNT_CA_PATH=${KUBE_SERVICEACCOUNT_CA_PATH:-/var/run/secrets/kubernetes.io/serviceaccount/ca.crt}
KUBE_API_SERVER=${KUBE_API_SERVER:-https://kubernetes.default.svc:443}
POD_YAML_TEST_NAME=${POD_YAML_TEST_NAME:-ci-pod-yaml-validate}
export POD_YAML_TEST_NAME

if [ -z "${POD_YAML_TEST_NAMESPACE:-}" ] && [ -f "$KUBE_SERVICEACCOUNT_NAMESPACE_PATH" ]; then
    POD_YAML_TEST_NAMESPACE=$(tr -d '\n' < "$KUBE_SERVICEACCOUNT_NAMESPACE_PATH")
fi
POD_YAML_TEST_NAMESPACE=${POD_YAML_TEST_NAMESPACE:-}
export POD_YAML_TEST_NAMESPACE

TEMP_KUBECONFIG=""

cleanup() {
    if [ -n "$TEMP_KUBECONFIG" ] && [ -f "$TEMP_KUBECONFIG" ]; then
        rm -f "$TEMP_KUBECONFIG"
    fi
}
trap cleanup EXIT INT TERM

setup_kubeconfig() {
    if ! command -v "$KUBECTL_BIN" >/dev/null 2>&1; then
        echo "error: $KUBECTL_BIN not found in PATH" >&2
        exit 1
    fi

    if [ -n "${KUBECONFIG:-}" ]; then
        return
    fi

    if "$KUBECTL_BIN" config current-context >/dev/null 2>&1; then
        return
    fi

    if [ ! -s "$KUBE_SERVICEACCOUNT_TOKEN_PATH" ]; then
        echo "error: no kubeconfig context found and service account token is unavailable: $KUBE_SERVICEACCOUNT_TOKEN_PATH (ensure this Prow job sets spec.automountServiceAccountToken: true)" >&2
        exit 1
    fi
    if [ ! -s "$KUBE_SERVICEACCOUNT_CA_PATH" ]; then
        echo "error: no kubeconfig context found and service account CA is unavailable: $KUBE_SERVICEACCOUNT_CA_PATH" >&2
        exit 1
    fi

    TEMP_KUBECONFIG=$(mktemp)
    token=$(tr -d '\n' < "$KUBE_SERVICEACCOUNT_TOKEN_PATH")

    "$KUBECTL_BIN" config --kubeconfig="$TEMP_KUBECONFIG" set-cluster in-cluster \
        --server="$KUBE_API_SERVER" \
        --certificate-authority="$KUBE_SERVICEACCOUNT_CA_PATH" \
        --embed-certs=true >/dev/null

    "$KUBECTL_BIN" config --kubeconfig="$TEMP_KUBECONFIG" set-credentials in-cluster-service-account \
        --token="$token" >/dev/null

    if [ -n "$POD_YAML_TEST_NAMESPACE" ]; then
        "$KUBECTL_BIN" config --kubeconfig="$TEMP_KUBECONFIG" set-context in-cluster \
            --cluster=in-cluster \
            --user=in-cluster-service-account \
            --namespace="$POD_YAML_TEST_NAMESPACE" >/dev/null
    else
        "$KUBECTL_BIN" config --kubeconfig="$TEMP_KUBECONFIG" set-context in-cluster \
            --cluster=in-cluster \
            --user=in-cluster-service-account >/dev/null
    fi
    "$KUBECTL_BIN" config --kubeconfig="$TEMP_KUBECONFIG" use-context in-cluster >/dev/null
    export KUBECONFIG="$TEMP_KUBECONFIG"
}

if [ "$#" -gt 0 ]; then
    files="$*"
else
    files=$(find pipelines -type f \( -name '*.yaml' -o -name '*.yml' \) | LC_ALL=C sort)
fi

count=0
failed=0

setup_kubeconfig

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

    : >"$stderr_file"
    if ! "$KUBECTL_BIN" apply --dry-run=server --validate=strict -f "$manifest" >/dev/null 2>"$stderr_file"; then
        echo "$file: kubectl server dry-run validation failed:"
        sed 's/^/  /' "$stderr_file"
        failed=1
    fi

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

    validate_with_kubectl "$file"
}

for file in $files; do
    check_file "$file"
done

if [ "$failed" -ne 0 ]; then
    exit 1
fi

echo "Checked $count pipeline Pod YAML files: all passed."
