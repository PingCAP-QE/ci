@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tiflow"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final REFS = readJSON(text: params.JOB_SPEC).refs
final POD_TEMPLATE_FILE = 'pipelines/pingcap-inc/tiflash-scripts/latest/pull_regression_test/pod.yaml'

prow.setPRDescription(REFS)

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
            yaml pod_label.withCiLabels(POD_TEMPLATE_FILE, REFS)
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
                        bash "${WORKSPACE}/.ci/tiflash-scripts/prepare_runtime_and_artifacts.sh" regression
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
                            bash "${WORKSPACE}/.ci/tiflash-scripts/archive_logs.sh" tiflash-regression-logs
                        '''
                        archiveArtifacts artifacts: 'artifacts/tiflash-regression-logs.tar.gz', allowEmptyArchive: true
                    }
                }
            }
        }
    }
}
