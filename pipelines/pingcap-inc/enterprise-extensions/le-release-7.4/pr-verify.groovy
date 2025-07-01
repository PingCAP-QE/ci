// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'pingcap-inc/enterprise-extensions'
final POD_TEMPLATE_FILE = 'pipelines/pingcap-inc/enterprise-extensions/le-release-7.4/pod-pr-verify.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'golang'
        }
    }
    options {
        timeout(time: 90, unit: 'MINUTES')
    }
    stages {
        stage('Debug info') {
            steps {
                sh label: 'Debug info', script: """
                    printenv
                    echo "-------------------------"
                    go env
                    echo "-------------------------"
                    echo "debug command: kubectl -n ${K8S_NAMESPACE} exec -ti ${NODE_NAME} bash"
                """
                container(name: 'net-tool') {
                    sh 'dig github.com'
                    script {
                        currentBuild.description = "PR #${REFS.pulls[0].number}: ${REFS.pulls[0].title} ${REFS.pulls[0].link}"
                    }
                }
            }
        }
        stage('Checkout') {
            steps {
                dir('tidb') {
                    cache(path: "./", includes: '**/*', key: "git/pingcap/tidb/rev-${REFS.pulls[0].sha}", restoreKeys: ['git/pingcap/tidb/rev-']) {
                        retry(2) {
                            script {
                                component.checkoutWithMergeBase('https://github.com/pingcap/tidb.git', 'tidb', REFS.base_ref, REFS.pulls[0].title, trunkBranch=REFS.base_ref, timeout=5, credentialsId="")
                                sh """
                                git rev-parse --show-toplevel
                                git status -s .
                                git log --format="%h %B" --oneline -n 3
                                """
                            }
                        }
                    }
                }
                dir('tidb/extension/enterprise') {
                    retry(2) {
                        script {
                            prow.checkoutPrivateRefs(REFS, GIT_CREDENTIALS_ID, timeout=5)
                            sh """
                            git rev-parse --show-toplevel
                            git status -s .
                            git log --format="%h %B" --oneline -n 3
                            """
                        }
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                container('golang') {
                    sh '''
                        git config --global --add safe.directory $(pwd)
                        git config --global --add safe.directory $(pwd)/tidb
                        git config --global --add safe.directory $(pwd)/tidb/extension/enterprise
                    '''
                }
            }
        }
        stage('Check') {
            steps {
                container('golang') {
                    sh script: 'make gogenerate check -C tidb'
                }
            }
        }
        stage("Test") {
            steps {
                container('golang') {
                    dir('tidb') {
                        sh label: 'Unit Test', script: 'go test --tags intest -v ./extension/enterprise/...'
                    }
                }
            }
        }
        stage("Build") {
            steps {
                container('golang') {
                    // We should not update `extension` dir with `enterprise-server` make task.
                    sh 'make enterprise-prepare enterprise-server-build -C tidb'
                }
            }
            post {
                success {
                    // should not archive it for enterprise edition.
                    echo 'Wont archive artifacts publicly for enterprise building'
                    // archiveArtifacts(artifacts: 'tidb/tidb-server', fingerprint: true, allowEmptyArchive: true)
                }
            }
        }
    }
}
