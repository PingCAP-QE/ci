@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tiflow"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final REFS = readJSON(text: params.JOB_SPEC).refs
final POD_TEMPLATE_FILE = 'pipelines/pingcap-inc/tiflash-scripts/latest/pull_schrodinger_test/pod.yaml'

prow.setPRDescription(REFS)

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yaml pod_label.withCiLabels(POD_TEMPLATE_FILE, REFS)
            defaultContainer 'runner'
            retries 2
        }
    }
    environment {
        OCI_ARTIFACT_HOST = 'us-docker.pkg.dev/pingcap-testing-account/hub'
    }
    options {
        timeout(time: 6, unit: 'HOURS')
    }
    stages {
        stage('Init Params') {
            steps {
                script {
                    def desc = params.getOrDefault("desc", "TiFlash schrodinger test")
                    def branch = params.getOrDefault("branch", "${REFS.base_ref ?: 'master'}")
                    def version = params.getOrDefault("version", "latest")
                    def testcase = params.getOrDefault("testcase", "schrodinger/bank")
                    def maxRunTime = params.getOrDefault("maxRunTime", "120")
                    def targetBranch = params.getOrDefault("ghprbTargetBranch", "")

                    if (targetBranch != "") {
                        branch = targetBranch
                    }

                    if (branch in ["planner_refactory", "raft"]) {
                        branch = "master"
                    }
                    if (version == null || version.trim() == "") {
                        version = "latest"
                    }
                    if (testcase == null || testcase.trim() == "") {
                        testcase = "schrodinger/bank"
                    }
                    if (maxRunTime == null || maxRunTime.trim() == "") {
                        maxRunTime = "120"
                    }

                    env.TEST_BRANCH = "${branch}"
                    env.TEST_VERSION = "${version}"
                    env.TEST_CASE = "${testcase}"
                    env.TEST_MAX_RUNTIME = "${maxRunTime}"

                    currentBuild.description = "${desc} branch=${branch} version=${version} testcase=${testcase}"
                }
            }
        }

        stage('Checkout') {
            steps {
                dir(REFS.repo) {
                    container('jnlp') {
                        script {
                            prow.checkoutRefs(REFS, credentialsId = GIT_CREDENTIALS_ID, timeout = 10)
                        }
                    }
                }
            }
        }

        stage('Prepare Runtime And Artifacts') {
            steps {
                dir(REFS.repo) {
                    sh '''
                        set -euxo pipefail
                        bash "${WORKSPACE}/pipelines/pingcap-inc/tiflash-scripts/latest/common/prepare_runtime_and_artifacts.sh" schrodinger
                    '''
                }
            }
        }

        stage('Run Schrodinger Test') {
            steps {
                dir(REFS.repo) {
                    sh '''
                        set -euxo pipefail
                        for proc in tidb-server tikv-server pd-server theflash tiflash; do
                            pkill -9 -x "${proc}" || true
                        done
                        rm -rf /tmp/ti /tmp/download || true

                        binaries_dir="$(pwd)/binary"
                        mkdir -p "${binaries_dir}"

                        testcase="${TEST_CASE:-}"
                        max_runtime="${TEST_MAX_RUNTIME:-120}"
                        if ! [[ "${max_runtime}" =~ ^[0-9]+$ ]]; then
                            max_runtime=120
                        fi
                        if (( max_runtime > 330 )); then
                            max_runtime=330
                        fi

                        rm -f integrated/conf/bin.paths
                        cp regression_test/conf/bin.paths integrated/conf/bin.paths
                        integrated/ops/ti.sh download regression_test/download.ti "${binaries_dir}"

                        integrated/ops/ti.sh regression_test/download.ti burn : up : ver : burn

                        if [[ "${testcase}" == schrodinger/sqllogic* ]]; then
                            set +e
                            timeout 330m regression_test/schrodinger.sh "${testcase}"
                            rc="$?"
                            set -e
                            exit "${rc}"
                        fi

                        start_ts="$(date +%s)"
                        set +e
                        timeout "${max_runtime}m" regression_test/schrodinger.sh "${testcase}"
                        rc="$?"
                        set -e
                        end_ts="$(date +%s)"
                        elapsed_min="$(( (end_ts - start_ts) / 60 ))"

                        if [[ "${rc}" == "124" || "${rc}" == "137" ]]; then
                            echo "schrodinger timed out after ${elapsed_min}m, treat as success"
                            exit 0
                        fi
                        if [[ "${rc}" == "0" ]]; then
                            echo "schrodinger exited before timeout in ${elapsed_min}m, treat as failure"
                            exit 1
                        fi
                        if (( elapsed_min < max_runtime )); then
                            echo "schrodinger failed before expected runtime (${elapsed_min}m < ${max_runtime}m)"
                            exit "${rc}"
                        fi
                        echo "schrodinger reached expected runtime with rc=${rc}, treat as success"
                    '''
                }
            }
            post {
                unsuccessful {
                    dir(REFS.repo) {
                        sh '''
                            set +e
                            out_name="tiflash-schrodinger-logs"
                            out_dir="artifacts/${out_name}"
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
                            tar -czf "artifacts/${out_name}.tar.gz" -C artifacts "${out_name}" || true
                        '''
                        archiveArtifacts artifacts: 'artifacts/tiflash-schrodinger-logs.tar.gz', allowEmptyArchive: true
                    }
                }
            }
        }
    }
}
