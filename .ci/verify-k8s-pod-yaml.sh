#!/bin/sh

set -eu

KUBECTL_BIN=${KUBECTL_BIN:-kubectl}
KUBE_APISERVER_HOST=${KUBE_APISERVER_HOST:-${KUBERNETES_SERVICE_HOST:-kubernetes.default.svc}}
KUBE_APISERVER_PORT=${KUBE_APISERVER_PORT:-${KUBERNETES_SERVICE_PORT_HTTPS:-443}}
KUBE_SERVICEACCOUNT_TOKEN_PATH=${KUBE_SERVICEACCOUNT_TOKEN_PATH:-/var/run/secrets/kubernetes.io/serviceaccount/token}
KUBE_SERVICEACCOUNT_CA_PATH=${KUBE_SERVICEACCOUNT_CA_PATH:-/var/run/secrets/kubernetes.io/serviceaccount/ca.crt}
KUBE_SERVICEACCOUNT_NAMESPACE_PATH=${KUBE_SERVICEACCOUNT_NAMESPACE_PATH:-/var/run/secrets/kubernetes.io/serviceaccount/namespace}
POD_YAML_TEST_NAME=${POD_YAML_TEST_NAME:-ci-pod-yaml-validate}
KUBE_CONTEXT_NAME=${KUBE_CONTEXT_NAME:-in-cluster-validation}
KUBE_CLUSTER_NAME=${KUBE_CLUSTER_NAME:-in-cluster}
KUBE_USER_NAME=${KUBE_USER_NAME:-service-account}
KUBECTL_REQUEST_TIMEOUT=${KUBECTL_REQUEST_TIMEOUT:-20s}
export POD_YAML_TEST_NAME

if [ -z "${POD_YAML_TEST_NAMESPACE:-}" ] && [ -f "$KUBE_SERVICEACCOUNT_NAMESPACE_PATH" ]; then
    POD_YAML_TEST_NAMESPACE=$(tr -d '\n' < "$KUBE_SERVICEACCOUNT_NAMESPACE_PATH")
fi
POD_YAML_TEST_NAMESPACE=${POD_YAML_TEST_NAMESPACE:-}
export POD_YAML_TEST_NAMESPACE

temp_kubeconfig=""

cleanup() {
    if [ -n "$temp_kubeconfig" ] && [ -f "$temp_kubeconfig" ]; then
        rm -f "$temp_kubeconfig"
    fi
}

trap cleanup EXIT INT TERM

if [ "$#" -gt 0 ]; then
    files="$*"
else
    files=$(find pipelines -type f \( -name '*.yaml' -o -name '*.yml' \) | LC_ALL=C sort)
fi

count=0
failed=0

require_file() {
    path=$1
    description=$2
    if [ ! -f "$path" ]; then
        echo "missing $description at $path"
        exit 1
    fi
}

prepare_kubeconfig() {
    if [ -n "${KUBECONFIG:-}" ]; then
        return
    fi

    require_file "$KUBE_SERVICEACCOUNT_TOKEN_PATH" "service account token"
    require_file "$KUBE_SERVICEACCOUNT_CA_PATH" "service account CA certificate"

    token=$(tr -d '\n' < "$KUBE_SERVICEACCOUNT_TOKEN_PATH")
    if [ -z "$token" ]; then
        echo "service account token is empty: $KUBE_SERVICEACCOUNT_TOKEN_PATH"
        exit 1
    fi

    temp_kubeconfig=$(mktemp)
    cat >"$temp_kubeconfig" <<EOF
apiVersion: v1
kind: Config
clusters:
  - name: $KUBE_CLUSTER_NAME
    cluster:
      certificate-authority: $KUBE_SERVICEACCOUNT_CA_PATH
      server: https://$KUBE_APISERVER_HOST:$KUBE_APISERVER_PORT
users:
  - name: $KUBE_USER_NAME
    user:
      token: $token
contexts:
  - name: $KUBE_CONTEXT_NAME
    context:
      cluster: $KUBE_CLUSTER_NAME
      user: $KUBE_USER_NAME
      namespace: ${POD_YAML_TEST_NAMESPACE:-default}
current-context: $KUBE_CONTEXT_NAME
EOF

    export KUBECONFIG="$temp_kubeconfig"
}

render_manifest_with_defaults() {
    input_file=$1
    output_file=$2

    if [ -n "$POD_YAML_TEST_NAMESPACE" ]; then
        yq e '.metadata = (.metadata // {}) |
            .metadata.name = (.metadata.name // strenv(POD_YAML_TEST_NAME)) |
            .metadata.namespace = (.metadata.namespace // strenv(POD_YAML_TEST_NAMESPACE))' "$input_file" >"$output_file"
    else
        yq e '.metadata = (.metadata // {}) |
            .metadata.name = (.metadata.name // strenv(POD_YAML_TEST_NAME))' "$input_file" >"$output_file"
    fi
}

preflight_kubectl() {
    manifest=$(mktemp)
    rendered_manifest=$(mktemp)
    stderr_file=$(mktemp)

    cat >"$manifest" <<EOF
apiVersion: v1
kind: Pod
metadata:
  name: ${POD_YAML_TEST_NAME}-preflight
spec:
  containers:
    - name: preflight
      image: busybox:1.36
      command: ["sh", "-c", "exit 0"]
EOF

    render_manifest_with_defaults "$manifest" "$rendered_manifest"

    if ! "$KUBECTL_BIN" apply --request-timeout="$KUBECTL_REQUEST_TIMEOUT" --dry-run=client --validate=strict -f "$rendered_manifest" >/dev/null 2>"$stderr_file"; then
        echo "kubectl client dry-run preflight failed:"
        sed 's/^/  /' "$stderr_file"
        rm -f "$manifest" "$rendered_manifest" "$stderr_file"
        exit 1
    fi

    : >"$stderr_file"
    if ! "$KUBECTL_BIN" apply --request-timeout="$KUBECTL_REQUEST_TIMEOUT" --dry-run=server --validate=strict -f "$rendered_manifest" >/dev/null 2>"$stderr_file"; then
        echo "kubectl server dry-run preflight failed:"
        sed 's/^/  /' "$stderr_file"
        rm -f "$manifest" "$rendered_manifest" "$stderr_file"
        exit 1
    fi

    rm -f "$manifest" "$rendered_manifest" "$stderr_file"
}

validate_with_kubectl() {
    file=$1
    manifest=$(mktemp)
    stderr_file=$(mktemp)

    render_manifest_with_defaults "$file" "$manifest"

    if ! "$KUBECTL_BIN" apply --request-timeout="$KUBECTL_REQUEST_TIMEOUT" --dry-run=client --validate=strict -f "$manifest" >/dev/null 2>"$stderr_file"; then
        echo "$file: kubectl client dry-run validation failed:"
        sed 's/^/  /' "$stderr_file"
        failed=1
    fi

    : >"$stderr_file"
    if ! "$KUBECTL_BIN" apply --request-timeout="$KUBECTL_REQUEST_TIMEOUT" --dry-run=server --validate=strict -f "$manifest" >/dev/null 2>"$stderr_file"; then
        echo "$file: kubectl server dry-run validation failed:"
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

    validate_with_kubectl "$file"
}

prepare_kubeconfig
preflight_kubectl

for file in $files; do
    check_file "$file"
done

if [ "$failed" -ne 0 ]; then
    exit 1
fi

echo "Checked $count pipeline Pod YAML files: all passed."
