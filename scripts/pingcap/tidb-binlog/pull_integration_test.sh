#!/usr/bin/env bash

set -euo pipefail

ARTIFACTS_DIR="${ARTIFACTS:-/logs/artifacts}"
TOOLS_DIR="/tmp/bin-tools"
OCI_SCRIPT="/tmp/download_pingcap_oci_artifact.sh"
OCI_ARTIFACT_HOST="${OCI_ARTIFACT_HOST:-us-docker.pkg.dev/pingcap-testing-account/hub}"
OCI_ARTIFACT_HOST_COMMUNITY="${OCI_ARTIFACT_HOST_COMMUNITY:-us-docker.pkg.dev/pingcap-testing-account/hub}"
TIDB_BINLOG_SUPPORTED_REFS="${TIDB_BINLOG_SUPPORTED_REFS:-release-8.1,release-7.5,release-7.1}"
REPO_ROOT="$(pwd)"

mkdir -p "${ARTIFACTS_DIR}" "${TOOLS_DIR}" /tools
export PATH="${TOOLS_DIR}:${PATH}"

log() {
  printf '%s %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$*" | tee -a "${ARTIFACTS_DIR}/steps.log"
}

fail() {
  log "ERROR: $*"
  exit 1
}

normalize_component_ref() {
  local ref="$1"

  if [[ "${ref}" =~ ^release-([0-9]+\.[0-9]+)(-beta\.[0-9]+)?([.-].*)?$ ]]; then
    echo "release-${BASH_REMATCH[1]}${BASH_REMATCH[2]}"
    return 0
  fi

  echo "${ref}"
}

normalize_oci_tag() {
  local ref="$1"
  echo "${ref//\//-}"
}

is_supported_binlog_ref() {
  local ref="$1"
  case ",${TIDB_BINLOG_SUPPORTED_REFS}," in
    *,"${ref}",*) return 0 ;;
  esac
  return 1
}

determine_dependency_ref() {
  local base_ref="$1"
  local normalized_ref

  normalized_ref="$(normalize_component_ref "${base_ref}")"

  if is_supported_binlog_ref "${normalized_ref}"; then
    echo "${normalized_ref}"
    return 0
  fi

  fail "Base ref ${base_ref} is outside the maintained binlog-compatible TiDB matrix (${TIDB_BINLOG_SUPPORTED_REFS}). This job only serves release-8.1, release-7.5, and release-7.1."
}

install_oras() {
  local version="1.2.3"

  if command -v oras >/dev/null 2>&1; then
    return 0
  fi

  log "Installing oras ${version}"
  curl -fsSL --retry 3 \
    "https://github.com/oras-project/oras/releases/download/v${version}/oras_${version}_linux_amd64.tar.gz" \
    | tar -xz -C "${TOOLS_DIR}" oras
  chmod +x "${TOOLS_DIR}/oras"
}

install_yq() {
  local version="v4.47.2"

  if command -v yq >/dev/null 2>&1; then
    return 0
  fi

  log "Installing yq ${version}"
  curl -fsSL --retry 3 \
    "https://github.com/mikefarah/yq/releases/download/${version}/yq_linux_amd64" \
    -o "${TOOLS_DIR}/yq"
  chmod +x "${TOOLS_DIR}/yq"
}

prepare_download_tools() {
  command -v curl >/dev/null 2>&1 || fail "curl is required"
  command -v tar >/dev/null 2>&1 || fail "tar is required"

  install_oras
  install_yq

  if [[ -f /ci/download_pingcap_oci_artifact.sh ]]; then
    log "Using mounted OCI download helper"
    cp /ci/download_pingcap_oci_artifact.sh "${OCI_SCRIPT}"
  else
    log "Fetching OCI download helper"
    curl -fsSL --retry 3 \
      "https://raw.githubusercontent.com/PingCAP-QE/ci/main/scripts/artifacts/download_pingcap_oci_artifact.sh" \
      -o "${OCI_SCRIPT}"
  fi
  chmod +x "${OCI_SCRIPT}"
}

wait_for_port() {
  local name="$1"
  local port="$2"

  for _ in $(seq 1 60); do
    if (echo > /dev/tcp/127.0.0.1/${port}) >/dev/null 2>&1; then
      log "${name} is ready on 127.0.0.1:${port}"
      return 0
    fi
    sleep 1
  done

  return 1
}

collect_logs() {
  local src="/tmp/tidb_binlog_test"
  local dest="${ARTIFACTS_DIR}/tidb_binlog_test"

  if [[ ! -d "${src}" ]]; then
    return 0
  fi

  mkdir -p "${dest}"
  find "${src}" -maxdepth 1 -type f \( -name "*.log" -o -name "*.out" \) -print0 \
    | while IFS= read -r -d '' file; do
        cp -f "${file}" "${dest}/$(basename "${file}")" || true
      done
}

print_failure_logs() {
  local src="/tmp/tidb_binlog_test"
  local files=(
    "pd.log"
    "tikv.log"
    "tidb.log"
    "drainer.log"
    "pump_8250.log"
    "pump_8251.log"
    "reparo.log"
    "binlog.out"
    "kafka.out"
  )

  for name in "${files[@]}"; do
    local file="${src}/${name}"
    if [[ -f "${file}" ]]; then
      log "===== ${name} ====="
      tail -n 200 "${file}" || true
    fi
  done
}

cleanup() {
  local exit_code="$1"

  if [[ "${exit_code}" -ne 0 ]]; then
    print_failure_logs
  fi

  collect_logs
  touch /tools/run.done || true
}

main() {
  trap 'exit_code=$?; cleanup "${exit_code}"; exit "${exit_code}"' EXIT

  local base_ref="${PULL_BASE_REF:-release-8.1}"
  local dependency_ref
  local tikv_ref
  local pd_ref
  local tidb_ref
  local tidb_tools_ref
  local tikv_tag
  local pd_tag
  local tidb_tag
  local tidb_tools_tag
  local tidb_tools_tarball="${REPO_ROOT}/bin/tidb-tools.tar.gz"
  local tidb_tools_extract_dir="/tmp/tidb-tools-bin"
  local tidb_tools_bin_dir

  if ! command -v git >/dev/null 2>&1; then
    fail "git is required"
  fi
  if ! command -v make >/dev/null 2>&1; then
    fail "make is required"
  fi

  log "Repo root: ${REPO_ROOT}"
  log "Base ref: ${base_ref}"
  log "Supported TiDB Binlog compatibility refs: ${TIDB_BINLOG_SUPPORTED_REFS}"

  dependency_ref="$(determine_dependency_ref "${base_ref}")"
  tikv_ref="${dependency_ref}"
  pd_ref="${dependency_ref}"
  tidb_ref="${dependency_ref}"
  tidb_tools_ref="master"

  log "Base ref ${base_ref} uses maintained binlog-compatible TiDB ref ${dependency_ref}"
  log "tidb-tools uses the maintained public artifact line: ${tidb_tools_ref}"

  tikv_tag="$(normalize_oci_tag "${tikv_ref}")"
  pd_tag="$(normalize_oci_tag "${pd_ref}")"
  tidb_tag="$(normalize_oci_tag "${tidb_ref}")"
  tidb_tools_tag="$(normalize_oci_tag "${tidb_tools_ref}")"

  log "Resolved TiKV ref: ${tikv_ref} -> OCI tag ${tikv_tag}"
  log "Resolved PD ref: ${pd_ref} -> OCI tag ${pd_tag}"
  log "Resolved TiDB ref: ${tidb_ref} -> OCI tag ${tidb_tag}"
  log "Resolved tidb-tools ref: ${tidb_tools_ref} -> OCI tag ${tidb_tools_tag}"

  prepare_download_tools

  log "Building tidb-binlog"
  make build
  ls -alh "${REPO_ROOT}/bin"

  log "Downloading TiDB/TiKV/PD artifacts from OCI"
  (
    cd "${REPO_ROOT}/bin"
    OCI_ARTIFACT_HOST="${OCI_ARTIFACT_HOST}" bash "${OCI_SCRIPT}" \
      --pd="${pd_tag}" \
      --tikv="${tikv_tag}" \
      --tidb="${tidb_tag}"
  )

  log "Downloading tidb-tools artifacts from OCI/public artifact"
  rm -rf "${tidb_tools_extract_dir}"
  mkdir -p "${tidb_tools_extract_dir}"
  (
    cd "${REPO_ROOT}/bin"
    OCI_ARTIFACT_HOST="${OCI_ARTIFACT_HOST}" \
    OCI_ARTIFACT_HOST_COMMUNITY="${OCI_ARTIFACT_HOST_COMMUNITY}" \
      bash "${OCI_SCRIPT}" --tidb-tools="${tidb_tools_tag}"
  )
  tar -xzf "${tidb_tools_tarball}" -C "${tidb_tools_extract_dir}"
  tidb_tools_bin_dir="$(find "${tidb_tools_extract_dir}" -type d -name bin | head -n1 || true)"
  if [[ -n "${tidb_tools_bin_dir}" ]]; then
    find "${tidb_tools_bin_dir}" -maxdepth 1 -type f -exec cp -f {} "${REPO_ROOT}/bin/" \;
  else
    log "tidb-tools archive uses flat binary layout"
    find "${tidb_tools_extract_dir}" -maxdepth 1 -type f -exec cp -f {} "${REPO_ROOT}/bin/" \;
  fi
  rm -f "${tidb_tools_tarball}"
  rm -f "${REPO_ROOT}/bin/ddl_checker" "${REPO_ROOT}/bin/importer"

  log "Prepared binary layout"
  ls -alh "${REPO_ROOT}/bin"

  wait_for_port "Zookeeper" 2181 || fail "Zookeeper did not become ready in time"
  wait_for_port "Kafka" 9092 || fail "Kafka did not become ready in time"

  log "Running binlog integration test"
  KAFKA_ADDRS=127.0.0.1:9092 make integration_test
  log "Binlog integration test finished successfully"
}

main "$@"
