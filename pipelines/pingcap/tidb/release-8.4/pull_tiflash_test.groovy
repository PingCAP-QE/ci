// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master release branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/release-8.4/pod-pull_tiflash_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs

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
        timeout(time: 40, unit: 'MINUTES')
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
                }
            }
        }
        stage('Checkout') {
            options { timeout(time: 10, unit: 'MINUTES') }
            steps {
                dir("tidb") {
                    cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                        retry(2) {
                            script {
                                prow.checkoutRefs(REFS)
                            }
                        }
                    }
                }
                dir("tiflash") {
                    cache(path: "./", includes: '**/*', key: "git/pingcap/tiflash/rev-${REFS.pulls[0].sha}", restoreKeys: ['git/pingcap/tiflash/rev-']) {
                        retry(2) {
                            script {
                                component.checkout('https://github.com/pingcap/tiflash.git', 'tiflash', REFS.base_ref, REFS.pulls[0].title, GIT_CREDENTIALS_ID)
                            }
                        }
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                dir('tidb') {
                    container("golang") {
                        sh label: 'tidb-server', script: 'make'
                    }
                }
            }
        }
        stage('Tests') {
            options { timeout(time: 30, unit: 'MINUTES') }
            steps {
                container("docker") {
                    sh label: 'test docker', script: """
                    docker version
                    docker info
                    """
                    dir('tidb') {
                        sh label: 'tidb-server', script: 'ls bin/tidb-server && chmod +x bin/tidb-server && ./bin/tidb-server -V'
                    }
                    dir("build-docker-image") {
                        sh label: 'generate dockerfile', script: """
                        curl -o tidb.Dockerfile https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/tidb/tidb.Dockerfile
                        cat tidb.Dockerfile
                        cp ../tidb/bin/tidb-server tidb-server
                        ./tidb-server -V
                        """
                        sh label: 'build tmp tidb image', script: """
                        docker build -t hub.pingcap.net/qa/tidb:${REFS.base_ref} -f tidb.Dockerfile .
                        """
                    }
                    dir("tiflash/tests/docker") {
                        sh label: 'test', script: """
                        TIDB_CI_ONLY=1 TAG=${REFS.base_ref} PD_BRANCH=${REFS.base_ref} TIKV_BRANCH=${REFS.base_ref} TIDB_BRANCH=${REFS.base_ref} bash -xe run.sh
                        """
                    }
                }
            }
            post{
                failure {
                    container("docker") {
                        script {
                            println "Test failed, archive the log"
                            dir("tiflash/tests/docker") {
                                sh label: 'display and collect log', script: """
                                find log -name '*.log' | xargs tail -n 50
                                ls -alh log/
                                tar -cvzf log.tar.gz \$(find log/ -type f -name "*.log")
                                ls -alh  log.tar.gz
                                """
                                archiveArtifacts artifacts: "log.tar.gz", allowEmptyArchive: true
                            }
                        }
                    }
                }
            }
        }
    }
}
