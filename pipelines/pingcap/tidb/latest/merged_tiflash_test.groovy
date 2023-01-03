// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final COMMIT_CONTEXT = 'wip/tiflash-test'
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/latest/pod-merged_tiflash_test.yaml'

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
            options { timeout(time: 5, unit: 'MINUTES') }
            steps {
                dir("tidb") {
                    cache(path: "./", filter: '**/*', key: "git/pingcap/tidb/rev-${GIT_MERGE_COMMIT}", restoreKeys: ['git/pingcap/tidb/rev-']) {
                        retry(2) {
                            checkout(
                                changelog: false,
                                poll: false,
                                scm: [
                                    $class: 'GitSCM', branches: [[name: GIT_MERGE_COMMIT ]],
                                    doGenerateSubmoduleConfigurations: false,
                                    extensions: [
                                        [$class: 'PruneStaleBranch'],
                                        [$class: 'CleanBeforeCheckout'],
                                        [$class: 'CloneOption', timeout: 15],
                                    ],
                                    submoduleCfg: [],
                                    userRemoteConfigs: [[
                                        refspec: "+refs/heads/*:refs/remotes/origin/*",
                                        url: "https://github.com/${GIT_FULL_REPO_NAME}.git",
                                    ]],
                                ]
                            )
                        }
                    }
                }
                dir("tiflash") {
                    cache(path: "./", filter: '**/*', key: "git/pingcap/tiflash/rev-${GIT_MERGE_COMMIT}", restoreKeys: ['git/pingcap/tiflash/rev-']) {
                        retry(2) {
                            checkout(
                                changelog: false,
                                poll: false,
                                scm: [
                                    $class: 'GitSCM', branches: [[name: GIT_BASE_BRANCH ]],
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
                        cache(path: "./bin", filter: '**/*', key: "binary/pingcap/tidb/merged_tiflash_test/rev-${GIT_MERGE_COMMIT}") {  
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
printf 'FROM hub.pingcap.net/jenkins/alpine-glibc:tiflash-test \n
COPY tidb-server /tidb-server \n
WORKDIR / \n
EXPOSE 4000 \n
ENTRYPOINT ["/usr/local/bin/dumb-init", "/tidb-server"] \n' > Dockerfile

                        cat Dockerfile
                        cp ../tidb/bin/tidb-server tidb-server
                        """
                        sh label: 'build tmp tidb image', script: """
                        docker build -t hub.pingcap.net/qa/tidb:${GIT_BASE_BRANCH} -f Dockerfile .
                        """
                    }
                    dir("tiflash/tests/docker") {
                        sh label: 'test', script: """
                        TIDB_CI_ONLY=1 TAG=${GIT_BASE_BRANCH} PD_BRANCH=${GIT_BASE_BRANCH} TIKV_BRANCH=${GIT_BASE_BRANCH} TIDB_BRANCH=${GIT_BASE_BRANCH} bash -xe run.sh
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
