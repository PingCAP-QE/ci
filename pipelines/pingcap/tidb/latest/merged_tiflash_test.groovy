// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/latest/pod-merged_tiflash_test.yaml'
final REFS = prow.getJobRefs(params.PROW_DECK_URL, params.PROW_JOB_ID)

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
                    cache(path: "./", filter: '**/*', key: "git/${REFS.org}/${REFS.repo}/rev-${REFS.base_sha}", restoreKeys: ['git/pingcap/tidb/rev-']) {
                        retry(2) {
                            script {
                                prow.checkoutRefs(REFS)
                            }
                        }
                    }
                }
                dir("tiflash") {
                    cache(path: "./", filter: '**/*', key: "git/pingcap/tiflash/rev-${REFS.base_sha}", restoreKeys: ['git/pingcap/tiflash/rev-']) {
                        retry(2) {
                            checkout(
                                changelog: false,
                                poll: false,
                                scm: [
                                    $class: 'GitSCM', branches: [[name: "${REFS.base_ref}" ]],
                                    doGenerateSubmoduleConfigurations: false,
                                    extensions: [
                                        [$class: 'PruneStaleBranch'],
                                        [$class: 'CleanBeforeCheckout'],
                                        [$class: 'CloneOption', timeout: 15],
                                    ],
                                    submoduleCfg: [],
                                    userRemoteConfigs: [[
                                        refspec: "+refs/heads/*:refs/remotes/origin/*",
                                        url: "https://github.com/pingcap/tiflash.git",
                                    ]],
                                ]
                            )
                        }
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                dir('tidb') {
                    container("golang") {
                        cache(path: "./bin", filter: '**/*', key: "binary/pingcap/tidb/tidb-server/rev-${REFS.base_sha}") {  
                            sh label: 'tidb-server', script: 'ls bin/tidb-server || make'
                        }
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
                        curl -o tidb.Dockerfile https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/tidb.Dockerfile
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
                    script {
                        println "Test failed, archive the log"
                        dir("tiflash/tests/docker") {
                            archiveArtifacts artifacts: 'log/**/*.log', allowEmptyArchive: true 
                            sh label: 'display some log', script: """find log -name '*.log' | xargs tail -n 50"""
                        }
                    }
                }
            }       
        }
    }
}
