@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tiflash"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final REFS = readJSON(text: params.JOB_SPEC).refs
final GIT_FULL_REPO_NAME = "${REFS.org}/${REFS.repo}"
final BRANCH_ALIAS = 'latest'
final POD_TEMPLATE_FILE = "pipelines/${GIT_FULL_REPO_NAME}/${BRANCH_ALIAS}/${JOB_BASE_NAME}/pod.yaml"

def parseCommentValue(String body, String key) {
    if (body == null || body.trim() == "") {
        return ""
    }
    def m = body =~ /(?:^|\s|\\)${key}\s*=\s*([^\s\\]+)(?:\s|\\|$)/
    if (m) {
        return "${m[0][1]}"
    }
    return ""
}

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'runner'
            retries 2
        }
    }
    environment {
        OCI_ARTIFACT_HOST = 'us-docker.pkg.dev/pingcap-testing-account/hub'
    }
    options {
        timeout(time: 20, unit: 'HOURS')
    }
    stages {
        stage('Init Params') {
            steps {
                script {
                    def desc = params.getOrDefault("desc", "TiFlash regression test")
                    def branch = params.getOrDefault("branch", "${REFS.base_ref ?: 'master'}")
                    def version = params.getOrDefault("version", "latest")
                    def commentBody = params.getOrDefault("ghprbCommentBody", "")
                    def targetBranch = params.getOrDefault("ghprbTargetBranch", "")

                    if (targetBranch != "") {
                        branch = targetBranch
                    }
                    def branchFromComment = parseCommentValue(commentBody, 'branch')
                    if (branchFromComment != "") {
                        branch = branchFromComment
                    }
                    def versionFromComment = parseCommentValue(commentBody, 'version')
                    if (versionFromComment != "") {
                        version = versionFromComment
                    }

                    if (branch in ["planner_refactory", "raft"]) {
                        branch = "master"
                    }
                    if (version == null || version.trim() == "") {
                        version = "latest"
                    }

                    env.TEST_BRANCH = "${branch}"
                    env.TEST_VERSION = "${version}"
                    env.TEST_NOTIFY = "${params.getOrDefault('notify', 'false')}"
                    env.TEST_TIDB_COMMIT = "${params.getOrDefault('tidb_commit_hash', '')}"
                    env.TEST_TIKV_COMMIT = "${params.getOrDefault('tikv_commit_hash', '')}"
                    env.TEST_PD_COMMIT = "${params.getOrDefault('pd_commit_hash', '')}"
                    env.TEST_TIFLASH_COMMIT = "${params.getOrDefault('tiflash_commit_hash', '')}"

                    currentBuild.description = "${desc} branch=${branch} version=${version}"
                }
            }
        }

        stage('Checkout') {
            steps {
                dir(REFS.repo) {
                    deleteDir()
                    script {
                        prow.checkoutRefs(REFS, credentialsId = GIT_CREDENTIALS_ID, timeout = 10)
                    }
                    sh '''
                        set -euxo pipefail
                        git rev-parse HEAD
                        git status --short
                    '''
                }
            }
        }

        stage('Prepare Runtime And Artifacts') {
            steps {
                dir(REFS.repo) {
                    sh '''
                        set -euxo pipefail

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

                        oci_script="/tmp/download_pingcap_oci_artifact.sh"
                        curl -fsSL https://raw.githubusercontent.com/PingCAP-QE/ci/main/scripts/artifacts/download_pingcap_oci_artifact.sh -o "${oci_script}"

                        branch="${TEST_BRANCH:-master}"
                        if [[ -z "${branch}" || "${branch}" == "raft" ]]; then
                          branch="master"
                        fi

                        if [[ -n "${TEST_TIDB_COMMIT:-}" || -n "${TEST_TIKV_COMMIT:-}" || -n "${TEST_PD_COMMIT:-}" || -n "${TEST_TIFLASH_COMMIT:-}" ]]; then
                          echo "commit-hash parameters are ignored in GCP mode; OCI branch tags are used instead"
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
                    '''
                }
            }
        }

        stage('Run Regression Test') {
            steps {
                dir(REFS.repo) {
                    sh '''
                        set -euxo pipefail
                        for proc in tidb-server tikv-server pd-server theflash tiflash tikv-server-rngine; do
                          pkill -9 -x "${proc}" || true
                        done
                        rm -rf /tmp/ti /tmp/download || true

                        binaries_dir="$(pwd)/binary"
                        mkdir -p "${binaries_dir}"

                        rm -f integrated/conf/bin.paths
                        cp regression_test/conf/bin.paths integrated/conf/bin.paths
                        integrated/ops/ti.sh download regression_test/download.ti "${binaries_dir}"

                        integrated/ops/ti.sh regression_test/download.ti burn : up : ver : burn
                        timeout 1080m regression_test/daily.sh
                    '''
                }
            }
            post {
                always {
                    dir(REFS.repo) {
                        sh '''
                            set +e
                            out_dir="artifacts/tiflash-regression-logs"
                            mkdir -p "${out_dir}"

                            while IFS= read -r -d '' f; do
                              safe="${f#/}"
                              safe="$(echo "${safe}" | tr '/' '_')"
                              cp -f "${f}" "${out_dir}/${safe}" || true
                            done < <(find . -type f -name '*.log' -print0 2>/dev/null || true)

                            while IFS= read -r -d '' f; do
                              safe="${f#/}"
                              safe="$(echo "${safe}" | tr '/' '_')"
                              cp -f "${f}" "${out_dir}/${safe}" || true
                            done < <(find /tmp/ti -type f -name '*.log' ! -path '*/data/*' ! -path '*/tiflash/db*' -print0 2>/dev/null || true)

                            if [[ -d /tmp/ti ]]; then
                              tar -czf "${out_dir}/tmp-ti.tar.gz" -C /tmp ti || true
                            fi

                            tar -czf artifacts/tiflash-regression-logs.tar.gz -C artifacts tiflash-regression-logs || true
                        '''
                        archiveArtifacts artifacts: 'artifacts/tiflash-regression-logs.tar.gz', allowEmptyArchive: true
                    }
                }
            }
        }
    }
}
