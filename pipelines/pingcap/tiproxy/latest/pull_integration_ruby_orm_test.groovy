// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'PingCAP-QE/tidb-test'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tiproxy/latest/pod-pull_integration_ruby_orm_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yaml pod_label.withCiLabels(POD_TEMPLATE_FILE, REFS)
            retries 2
            workspaceVolume genericEphemeralVolume(accessModes: 'ReadWriteOnce', requestsSize: '150Gi', storageClassName: 'hyperdisk-rwo')
            defaultContainer 'golang'
        }
    }

    environment {
        OCI_ARTIFACT_HOST = 'us-docker.pkg.dev/pingcap-testing-account/hub'
    }
    options {
        timeout(time: 45, unit: 'MINUTES')
    }
    stages {
        stage('Checkout') {
            options { timeout(time: 5, unit: 'MINUTES') }
            steps {
                dir("tiproxy") {
                    cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                        retry(2) {
                            script {
                                prow.checkoutRefs(REFS)
                            }
                        }
                    }
                }
                dir("tidb-test") {
                    cache(path: "./", includes: '**/*', key: "git/PingCAP-QE/tidb-test/rev-${REFS.pulls[0].sha}", restoreKeys: ['git/PingCAP-QE/tidb-test/rev-']) {
                        retry(2) {
                            script {
                                component.checkoutV2('git@github.com:PingCAP-QE/tidb-test.git', 'tidb-test', "master", REFS.pulls[0].title, GIT_CREDENTIALS_ID)
                            }
                        }
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                dir('tiproxy') {
                    sh label: 'tiproxy', script: '[ -f bin/tiproxy ] || make'
                }
                dir('tidb-test') {
                    cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}") {
                        sh "touch ws-${BUILD_TAG}"
                        dir("bin") {
                            container("utils") {
                                retry(2) {
                                    sh label: 'download binary', script: """
                                    ${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh \
                                        --tidb=master --pd=master --tikv=master
                                    """
                                }
                            }
                        }
                        sh label: 'prepare thirdparty binary', script: """
                        cp ../tiproxy/bin/tiproxy ./bin/
                        ls -alh bin/
                        ./bin/tidb-server -V
                        ./bin/pd-server -V
                        ./bin/tikv-server -V
                        ./bin/tiproxy --version
                        """
                    }
                }
            }
        }
        stage('ORM Tests') {
            matrix {
                axes {
                    axis {
                        name 'TEST_CMDS'
                        values 'make deploy-activerecordtest ARGS="-x"'
                    }
                }
                agent{
                    kubernetes {
                        namespace K8S_NAMESPACE
                        yaml pod_label.withCiLabels(POD_TEMPLATE_FILE, REFS)
                        retries 2
                        workspaceVolume genericEphemeralVolume(accessModes: 'ReadWriteOnce', requestsSize: '150Gi', storageClassName: 'hyperdisk-rwo')
                        defaultContainer 'ruby'
                    }
                }
                when {
                    beforeAgent true
                    expression { return !matrixCache.shouldSkip(REFS, 'Test', [test_cmds: env.TEST_CMDS]) }
                }
                stages {
                    stage("Test") {
                        steps {
                            dir('tidb-test') {
                                cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}") {
                                    container("ruby") {
                                        sh label: "prepare ruby test deps", script: """
                                            #!/usr/bin/env bash
                                            set -euxo pipefail
                                            if ! command -v mysql >/dev/null 2>&1 || ! dpkg-query -W default-libmysqlclient-dev >/dev/null 2>&1 || ! dpkg-query -W libsqlite3-dev >/dev/null 2>&1; then
                                                apt-get update
                                                DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \\
                                                    default-mysql-client \\
                                                    build-essential \\
                                                    default-libmysqlclient-dev \\
                                                    libsqlite3-dev \\
                                                    pkg-config
                                                rm -rf /var/lib/apt/lists/*
                                            fi
                                            if ! gem list -i bundler -v 2.3.17 >/dev/null 2>&1; then
                                                gem install bundler -v 2.3.17
                                            fi
                                            command -v mysql
                                            bundle -v
                                        """
                                        sh label: "test_cmds=${TEST_CMDS} ", script: """
                                            #!/usr/bin/env bash
                                            ${TEST_CMDS}
                                        """
                                    }
                                }
                            }
                        }
                        post{
                            failure {
                                script {
                                    println "Test failed, archive the log"
                                }
                            }
                            success { script { matrixCache.markDone(REFS, 'Test', [test_cmds: env.TEST_CMDS]) } }
                        }
                    }
                }
            }
        }
    }
}
