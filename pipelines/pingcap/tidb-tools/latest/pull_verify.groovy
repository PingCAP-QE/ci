// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_FULL_REPO_NAME = 'pingcap/tidb-tools'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb-tools/latest/pod-pull_verify.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'runner'
        }
    }
    environment {
        FILE_SERVER_URL = 'http://fileserver.pingcap.net'
    }
    options {
        timeout(time: 40, unit: 'MINUTES')
        parallelsAlwaysFailFast()
    }
    stages {
        stage('Debug info') {
            steps {
                sh label: 'Debug info', script: """
                    printenv
                    echo "-------------------------"
                    node -v
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
            options { timeout(time: 10, unit: 'MINUTES') }
            steps {
                dir(REFS.repo) {
                    cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                        script {
                            retry(2) {
                                prow.checkoutRefs(REFS, timeout = 5, credentialsId = '', gitBaseUrl = 'https://github.com', withSubmodule=true)
                            }
                        }
                    }
                }
            }
        }
        stage('Build') {
            steps {
                sh "GOOS=darwin GOARCH=amd64 make build -C ${REFS.repo}"
                sh "GOOS=darwin GOARCH=arm64 make build -C ${REFS.repo}"
                sh "GOOS=linux GOARCH=arm64 make build -C ${REFS.repo}"
                sh "GOOS=linux GOARCH=amd64 make build -C ${REFS.repo}" // be the last order to build, the unit test will use it.
            }
        }
        stage('Unit Test') {
            steps {
                sh label: 'test mysql connection', script: '''
                    for i in {1..10} mysqladmin ping -h0.0.0.0 -P 3306 -uroot --silent; do
                        if [ $? -eq 0 ]; then 
                            break
                        else 
                            if [ $i -eq 10 ]; then 
                                exit 2
                            fi
                            sleep 1
                        fi; 
                    done
                '''
                sh label: 'test', script: "MYSQL_HOST=127.0.0.1 MYSQL_PORT=3306 make test -C ${REFS.repo}"
            }
        }
    }
}
