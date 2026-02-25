// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final BRANCH_ALIAS = 'latest'
final POD_TEMPLATE_FILE = "pipelines/${GIT_FULL_REPO_NAME}/${BRANCH_ALIAS}/${JOB_BASE_NAME}/pod.yaml"
final REFS = readJSON(text: params.JOB_SPEC).refs

prow.setPRDescription(REFS)
pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'golang'
        }
    }
    environment {
        FILE_SERVER_URL = 'http://fileserver.pingcap.net'
    }
    options {
        timeout(time: 90, unit: 'MINUTES')
        parallelsAlwaysFailFast()
    }
    stages {
        stage('Debug info') {
            steps {
                sh label: 'Debug info', script: """
                    printenv
                    echo "-------------------------"
                    go env
                    echo "-------------------------"
                    ls -l /dev/null
                    echo "debug command: kubectl -n ${K8S_NAMESPACE} exec -ti ${NODE_NAME} bash"
                """
                container(name: 'net-tool') {
                    sh 'dig github.com'
                }
            }
        }
        stage('Checkout') {
            steps {
                dir(REFS.repo) {
                    cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                        script {
                            git.setSshKey(GIT_CREDENTIALS_ID)
                            retry(2) {
                                prow.checkoutRefs(REFS, timeout = 5, credentialsId = '', gitBaseUrl = 'https://github.com', withSubmodule=true)
                            }
                        }
                    }
                }
            }
        }
        stage("Build tidb-server community edition"){
            steps {
                dir(REFS.repo) {
                    sh "make bazel_build"
                }
            }
            post {
                always {
                    dir(REFS.repo) {
                        archiveArtifacts(artifacts: 'importer.log,tidb-server-check.log', allowEmptyArchive: true)
                    }
                }
            }
        }
        stage("Build tidb-server enterprise edition") {
            steps {
                dir(REFS.repo) {
                    sh "make enterprise-prepare enterprise-server-build && ./bin/tidb-server -V"
                }
            }
        }
        stage("Test plugin") {
            when { not { expression { REFS.base_ref ==~ /^feature[\/_].*/ || REFS.base_ref ==~ /^release-fts-[0-9]+$/ } } } // skip for feature and release-fts branches.
            steps {
                dir('enterprise-plugin') {
                    cache(path: "./", includes: '**/*', key: "git/pingcap-inc/enterprise-plugin/rev-${REFS.pulls[0].sha}", restoreKeys: ['git/pingcap-inc/enterprise-plugin/rev-']) {
                        retry(2) {
                            script {
                                component.checkout('git@github.com:pingcap-inc/enterprise-plugin.git', 'plugin', REFS.base_ref, REFS.pulls[0].title, GIT_CREDENTIALS_ID)
                            }
                        }
                    }
                }
                sh label: 'Test plugins', script: """
                    mkdir -p plugin-so

                    # compile go plugin: audit
                    pushd enterprise-plugin/audit && go mod tidy && popd
                    pushd ${REFS.repo} && go run ./cmd/pluginpkg -pkg-dir ../enterprise-plugin/audit -out-dir ../plugin-so && popd

                    # compile go plugin: whitelist
                    pushd enterprise-plugin/whitelist && go mod tidy && popd
                    pushd ${REFS.repo} && go run ./cmd/pluginpkg -pkg-dir ../enterprise-plugin/whitelist -out-dir ../plugin-so && popd

                    # test them.
                    make server -C ${REFS.repo}
                    ./${REFS.repo}/bin/tidb-server -plugin-dir=./plugin-so -plugin-load=audit-1,whitelist-1 | tee ./loading-plugin.log &

                    sleep 30
                    ps aux | grep tidb-server
                    killall -9 -r tidb-server
                """
            }
        }
    }
}
