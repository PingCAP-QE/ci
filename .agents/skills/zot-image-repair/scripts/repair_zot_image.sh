#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'EOF'
Usage: repair_zot_image.sh --target-image <ref> --source-image <ref> [options]

Launch the zot-validate-repair-image Tekton TaskRun in the ksy-pingcap-cicd
cluster to validate and repair a broken hub-zot.pingcap.net image.

Options:
  --target-image <ref>    Broken image reference on hub-zot.pingcap.net (required)
  --source-image <ref>    Upstream source image that has the healthy blobs (required)
  --bucket <name>         KSY S3 bucket that backs zot (default: ee-zot)
  --config-file-path <p>  ks3util config path inside the workspace (default: .ks3utilconfig)
  --secret <name>         Secret mounted as the ks3util-config workspace (default: ks3utilconfig)
  --namespace <ns>        Kubernetes namespace (default: ee-cd)
  --context <ctx>         kubectl context (default: ksy-pingcap-cicd)
  --wait                  Follow TaskRun logs until completion, then report status
  -h, --help              Show this help

Example:
  bash repair_zot_image.sh \
    --target-image hub-zot.pingcap.net/mirrors/hub/pingcap/tidb/images/tidb-server:v7.5.7 \
    --source-image us-docker.pkg.dev/pingcap-testing-account/hub/pingcap/tidb/images/tidb-server:v7.5.7 \
    --wait
EOF
}

die() {
    echo "[ERROR] $*" >&2
    exit 1
}

# Reject values that would break shell or YAML interpolation below.
validate_value() {
    local value="$1"
    case "$value" in
        *$'\n'* | *$'\r'* | *$'\t'*) die "value contains a control character: $value" ;;
    esac
}

target_image=""
source_image=""
bucket="ee-zot"
config_file_path=".ks3utilconfig"
secret="ks3utilconfig"
namespace="ee-cd"
context="ksy-pingcap-cicd"
wait_run=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --target-image)
            target_image="${2:-}"
            shift 2
            ;;
        --source-image)
            source_image="${2:-}"
            shift 2
            ;;
        --bucket)
            bucket="${2:-}"
            shift 2
            ;;
        --config-file-path)
            config_file_path="${2:-}"
            shift 2
            ;;
        --secret)
            secret="${2:-}"
            shift 2
            ;;
        --namespace)
            namespace="${2:-}"
            shift 2
            ;;
        --context)
            context="${2:-}"
            shift 2
            ;;
        --wait)
            wait_run=true
            shift
            ;;
        -h | --help)
            usage
            exit 0
            ;;
        *)
            die "Unknown option: $1"
            ;;
    esac
done

[[ -n "$target_image" ]] || die "--target-image is required"
[[ -n "$source_image" ]] || die "--source-image is required"

for v in "$target_image" "$source_image" "$bucket" "$config_file_path" "$secret" "$namespace" "$context"; do
    validate_value "$v"
done

# Build a short, unique TaskRun name from the target image reference.
slug="$(printf '%s' "$target_image" | sed -E 's#[^A-Za-z0-9]+#-#g; s#(^-|-$)##g' | tr '[:upper:]' '[:lower:]')"
[[ -n "$slug" ]] || slug="image"
slug="${slug:0:40}"
name="zot-repair-${slug}"

tmp_yaml="$(mktemp "${TMPDIR:-/tmp}/zot-repair-XXXXXX.yaml")"
trap 'rm -f "$tmp_yaml"' EXIT

cat >"$tmp_yaml" <<EOF
apiVersion: tekton.dev/v1
kind: TaskRun
metadata:
  name: $name
  namespace: $namespace
spec:
  taskRef:
    name: zot-validate-repair-image
  params:
    - name: target-image
      value: $target_image
    - name: source-image
      value: $source_image
    - name: bucket
      value: $bucket
    - name: config-file-path
      value: $config_file_path
  workspaces:
    - name: ks3util-config
      secret:
        secretName: $secret
EOF

echo "[INFO] Creating TaskRun $name in $namespace ($context)"
kubectl --context "$context" -n "$namespace" apply -f "$tmp_yaml"

if ! $wait_run; then
    echo "[INFO] TaskRun created. Follow with:"
    echo "  tkn taskrun logs $name -n $namespace --context $context --follow"
    exit 0
fi

echo "[INFO] Waiting for TaskRun $name ..."
if command -v tkn >/dev/null 2>&1; then
    tkn taskrun logs "$name" -n "$namespace" --context "$context" --follow || true
else
    kubectl --context "$context" -n "$namespace" wait \
        --for=condition=Succeeded "taskrun/$name" --timeout=60m || true
    kubectl --context "$context" -n "$namespace" logs "pod/$name-pod" --tail=-1 || true
fi

reason="$(kubectl --context "$context" -n "$namespace" get taskrun "$name" \
    -o jsonpath='{.status.conditions[0].reason}' 2>/dev/null || echo Unknown)"
echo "[INFO] TaskRun $name final status: $reason"
if [[ "$reason" == "Succeeded" ]]; then
    exit 0
fi
exit 1
