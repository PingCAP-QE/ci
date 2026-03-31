#!/usr/bin/env bash
set -euxo pipefail

mode="${1:-regression}"
case "${mode}" in
  regression|schrodinger)
    ;;
  *)
    echo "unsupported mode: ${mode}" >&2
    exit 1
    ;;
esac

missing_pkgs=()
append_pkg() {
  local pkg="$1"
  local it=""
  for it in "${missing_pkgs[@]:-}"; do
    if [[ "${it}" == "${pkg}" ]]; then
      return 0
    fi
  done
  missing_pkgs+=("${pkg}")
}
need_cmd_pkg() {
  local cmd="$1"
  local pkg="$2"
  if ! command -v "${cmd}" >/dev/null 2>&1; then
    append_pkg "${pkg}"
  fi
}

need_cmd_pkg curl curl
need_cmd_pkg wget wget
need_cmd_pkg git git
need_cmd_pkg jq jq
need_cmd_pkg tar tar
need_cmd_pkg xz xz
need_cmd_pkg bzip2 bzip2
need_cmd_pkg gzip gzip
need_cmd_pkg python python
need_cmd_pkg java java-1.8.0-openjdk-headless
need_cmd_pkg timeout coreutils
need_cmd_pkg fio fio
need_cmd_pkg mysql mariadb
need_cmd_pkg lsof lsof
if ! command -v ifconfig >/dev/null 2>&1 && ! command -v netstat >/dev/null 2>&1; then
  append_pkg net-tools
fi
if ! command -v nc >/dev/null 2>&1; then
  append_pkg nmap-ncat
fi

if [[ "${#missing_pkgs[@]}" -gt 0 ]]; then
  if command -v yum >/dev/null 2>&1 && [[ "$(id -u)" == "0" ]]; then
    yum install -y "${missing_pkgs[@]}"
  else
    echo "missing required commands and cannot auto install: ${missing_pkgs[*]}" >&2
    exit 1
  fi
fi

local_bin_dir="/usr/local/bin"
if [[ ! -w "${local_bin_dir}" ]]; then
  local_bin_dir="$(pwd)/.bin"
  mkdir -p "${local_bin_dir}"
fi
export PATH="${local_bin_dir}:${PATH}"

oras_version="1.2.3"
if ! command -v oras >/dev/null 2>&1; then
  curl -fsSL "https://github.com/oras-project/oras/releases/download/v${oras_version}/oras_${oras_version}_linux_amd64.tar.gz" \
    | tar -xz -C "${local_bin_dir}" oras
  chmod +x "${local_bin_dir}/oras"
fi

yq_version="v4.47.2"
if ! command -v yq >/dev/null 2>&1; then
  curl -fsSL "https://github.com/mikefarah/yq/releases/download/${yq_version}/yq_linux_amd64" -o "${local_bin_dir}/yq"
  chmod +x "${local_bin_dir}/yq"
fi

oci_script="${OCI_SCRIPT_PATH:-}"
if [[ -z "${oci_script}" && -n "${WORKSPACE:-}" && -f "${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh" ]]; then
  oci_script="${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh"
fi
if [[ -z "${oci_script}" || ! -f "${oci_script}" ]]; then
  oci_script="/tmp/download_pingcap_oci_artifact.sh"
  curl -fsSL "https://raw.githubusercontent.com/PingCAP-QE/ci/main/scripts/artifacts/download_pingcap_oci_artifact.sh" -o "${oci_script}"
fi

branch="${TEST_BRANCH:-master}"
if [[ -z "${branch}" || "${branch}" == "raft" ]]; then
  branch="master"
fi

artifact_dir="/tmp/oci-artifacts-${BUILD_NUMBER:-0}-${RANDOM}"
rm -rf "${artifact_dir}"
mkdir -p "${artifact_dir}"

download_component() {
  local component="$1"
  local tag="$2"
  if ! OCI_ARTIFACT_HOST="${OCI_ARTIFACT_HOST}" /bin/bash "${oci_script}" --"${component}"="${tag}"; then
    echo "component=${component} tag=${tag} not found in OCI, fallback to master"
    OCI_ARTIFACT_HOST="${OCI_ARTIFACT_HOST}" /bin/bash "${oci_script}" --"${component}"=master
  fi
}

download_tidb() {
  local tag="$1"
  local candidates=(
    "${tag}-failpoint"
    "${tag}_failpoint"
    "${tag}-failpoints"
    "${tag}_failpoints"
    "${tag}"
  )
  local t=""
  for t in "${candidates[@]}"; do
    if OCI_ARTIFACT_HOST="${OCI_ARTIFACT_HOST}" /bin/bash "${oci_script}" --tidb="${t}"; then
      return 0
    fi
  done
  return 1
}

(
  cd "${artifact_dir}"
  download_component tikv "${branch}"
  download_component pd "${branch}"
  download_component pd-ctl "${branch}"
  download_component tikv-ctl "${branch}"
  download_component tiflash "${branch}"
  if ! download_tidb "${branch}"; then
    echo "tidb tag '${branch}' not found in OCI" >&2
    exit 1
  fi
)

mkdir -p \
  integrated/binary/tidb integrated/binary/tikv integrated/binary/pd integrated/binary/tiflash \
  binary/tidb binary/tikv binary/pd binary/tiflash
cp -f "${artifact_dir}/tidb-server" integrated/binary/tidb/tidb-server
cp -f "${artifact_dir}/tikv-server" integrated/binary/tikv/tikv-server
cp -f "${artifact_dir}/pd-server" integrated/binary/pd/pd-server
cp -f "${artifact_dir}/pd-ctl" integrated/binary/pd/pd-ctl
cp -f "${artifact_dir}/tikv-ctl" integrated/binary/tikv/tikv-ctl
cp -f "${artifact_dir}/tidb-server" binary/tidb/tidb-server
cp -f "${artifact_dir}/tikv-server" binary/tikv/tikv-server
cp -f "${artifact_dir}/pd-server" binary/pd/pd-server
cp -f "${artifact_dir}/pd-ctl" binary/pd/pd-ctl
cp -f "${artifact_dir}/tikv-ctl" binary/tikv/tikv-ctl
tiflash_pack_dir="${artifact_dir}/_tiflash_pack"
rm -rf "${tiflash_pack_dir}"
mkdir -p "${tiflash_pack_dir}/tiflash"
cp -a "${artifact_dir}/tiflash_dir/." "${tiflash_pack_dir}/tiflash/"
tar -czf integrated/binary/tiflash/tiflash.tar.gz -C "${tiflash_pack_dir}" tiflash
tar -czf binary/tiflash/tiflash.tar.gz -C "${tiflash_pack_dir}" tiflash

for f in integrated/conf/bin.urls integrated/conf/bin.urls.mac; do
  if [[ -f "${f}" ]]; then
    sed -i 's#https://download.pingcap.org/#https://download.pingcap.com/#g' "${f}"
    sed -i 's#http://download.pingcap.org/#https://download.pingcap.com/#g' "${f}"
    sed -i 's#http://fileserver.pingcap.net/download/#https://download.pingcap.com/download/#g' "${f}"
  fi
done

tools_kv_file="integrated/conf/tools.kv"
if [[ -f "${tools_kv_file}" ]]; then
  sed -i 's#https://download.pingcap.org/#https://download.pingcap.com/#g' "${tools_kv_file}"
  sed -i 's#http://download.pingcap.org/#https://download.pingcap.com/#g' "${tools_kv_file}"
fi

ti_conf_file="integrated/_base/ti_file/ti_conf.sh"
if [[ -f "${ti_conf_file}" ]]; then
  sed -i 's#http://fileserver.pingcap.net#https://download.pingcap.com#g' "${ti_conf_file}"
fi

if [[ "${mode}" == "schrodinger" ]]; then
  sqllogic_script="integrated/ops/ti.sh.cmds/schrodinger/sqllogic.sh.summary"
  if [[ -f "${sqllogic_script}" ]]; then
    sed -i 's#http://fileserver.pingcap.net/download/#https://download.pingcap.com/download/#g' "${sqllogic_script}"
  fi
fi

bin_paths_file="regression_test/conf/bin.paths"
if ! grep -qE '^pd_ctl[[:space:]]' "${bin_paths_file}"; then
  cat >> "${bin_paths_file}" <<'EOC'
pd_ctl           pd-ctl                    {integrated}/../binary/pd/pd-ctl
EOC
fi
if ! grep -qE '^tikv_ctl[[:space:]]' "${bin_paths_file}"; then
  cat >> "${bin_paths_file}" <<'EOC'
tikv_ctl         tikv-ctl                  {integrated}/../binary/tikv/tikv-ctl
EOC
fi

syncing_py="integrated/ops/ti.sh.cmds/syncing/show_syncing_table.py"
if [[ -f "${syncing_py}" ]]; then
  sed -i "/if s.find(' does not exist') >= 0:/{n;s/return/continue/;}" "${syncing_py}"
fi

proc_cross_file="integrated/_base/cross_platform/proc.sh"
if [[ -f "${proc_cross_file}" ]] && ! grep -q "print_procs_ci_zombie_guard" "${proc_cross_file}"; then
  cat >> "${proc_cross_file}" <<'EOC'
# print_procs_ci_zombie_guard
function print_procs()
{
	if [ -z "${1+x}" ]; then
		echo "[func print_procs] usage: <func> str_for_finding_the_procs [str2]" >&2
		return 1
	fi

	local find_str="${1}"
	local str2=''
	if [ ! -z "${2+x}" ]; then
		local str2="${2}"
	fi

	if [ `uname` == "Darwin" ]; then
		local pids=`pgrep -f "${find_str}"`
		if [ ! -z "${pids}" ]; then
			echo "${pids}" | while read pid; do
				ps -fp "${pid}" | { grep "${pid}" || test $? = 1; } | { grep "${str2}" || test $? = 1; } | { grep -v '^UID' || test $? = 1; } | { grep -v 'defunct' || test $? = 1; }
			done
		fi
	else
		ps -ef | { grep "${find_str}" || test $? = 1; } | { grep "${str2}" || test $? = 1; } | { grep -v 'grep' || test $? = 1; } | { grep -v 'defunct' || test $? = 1; }
	fi
}
export -f print_procs
EOC
fi

proc_file="integrated/_base/proc.sh"
if [[ -f "${proc_file}" ]] && ! grep -q "pid_exists_ci_zombie_guard" "${proc_file}"; then
  cat >> "${proc_file}" <<'EOC'
# pid_exists_ci_zombie_guard
function pid_exists()
{
	if [ -z "${1+x}" ]; then
		echo "[func pid_exists] usage: <func> pid" >&2
		return 1
	fi

	local pid="${1}"
	local exists=`ps -fp "${pid}" | { grep "${pid}" || test $? = 1; } | { grep -v "defunct" || test $? = 1; } | awk '{print $2}'`
	if [ -z "${exists}" ]; then
		echo 'false'
	else
		echo 'true'
	fi
}
export -f pid_exists
EOC
fi

ver_file="regression_test/download_ver.ti"
if [[ "${TEST_VERSION:-latest}" == "stable" ]]; then
  v="v${branch#release-}.x"
  cat > "${ver_file}" <<EOC
ver=${v}
tidb_branch=''
tidb_hash=''
tikv_branch=''
tikv_hash=''
pd_branch=''
pd_hash=''
tiflash_branch=''
tiflash_hash=''
EOC
else
  cat > "${ver_file}" <<EOC
ver=''
tidb_branch=${branch}
tidb_hash=''
tikv_branch=${branch}
tikv_hash=''
pd_branch=${branch}
pd_hash=''
tiflash_branch=${branch}
tiflash_hash=''
EOC
fi

cat "${ver_file}"
