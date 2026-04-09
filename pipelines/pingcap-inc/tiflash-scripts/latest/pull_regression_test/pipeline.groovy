@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tiflow"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final REFS = readJSON(text: params.JOB_SPEC).refs
final POD_TEMPLATE_FILE = 'pipelines/pingcap-inc/tiflash-scripts/latest/pull_regression_test/pod.yaml'

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
        timeout(time: 12, unit: 'HOURS')
    }
    stages {
        stage('Init Params') {
            steps {
                script {
                    def desc = params.getOrDefault("desc", "TiFlash regression test")
                    def branch = params.getOrDefault("branch", "${REFS.base_ref ?: 'master'}")
                    def version = params.getOrDefault("version", "latest")
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

                    env.TEST_BRANCH = "${branch}"
                    env.TEST_VERSION = "${version}"

                    currentBuild.description = "${desc} branch=${branch} version=${version}"
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
                        bash "${WORKSPACE}/pipelines/pingcap-inc/tiflash-scripts/latest/common/prepare_runtime_and_artifacts.sh" regression
                    '''
                }
            }
        }

        stage('Run Regression Test') {
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

                        rm -f integrated/conf/bin.paths
                        cp regression_test/conf/bin.paths integrated/conf/bin.paths
                        integrated/ops/ti.sh download regression_test/download.ti "${binaries_dir}"

                        integrated/ops/ti.sh regression_test/download.ti burn : up : ver : burn
                        timeout 660m regression_test/daily.sh
                    '''
                }
            }
            post {
                unsuccessful {
                    dir(REFS.repo) {
                        sh '''
                            set +e
                            out_name="tiflash-regression-logs"
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
                        archiveArtifacts artifacts: 'artifacts/tiflash-regression-logs.tar.gz', allowEmptyArchive: true
                    }
                }
            }
        }
    }
}
