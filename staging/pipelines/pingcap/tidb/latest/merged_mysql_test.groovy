// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
// TODO: remove env GIT_BRANCH and GIT_COMMIT
final GIT_BRANCH = 'master'
final GIT_COMMIT = '4aa89a6274f0195195f1d70281aa545007413aa1'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
// final POD_TEMPLATE_FILE = 'staging/pipelines/pingcap/tidb/latest/pod-merged_mysql_test.yaml'


def podYaml = '''
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: net-tool
    image: wbitt/network-multitool
    tty: true
    resources:
    limits:
        memory: 128Mi
        cpu: 100m
  - name: docker
    image: docker:dind
    args: ["--registry-mirror=https://registry-mirror.pingcap.net"]
    env:
    - name: REGISTRY
      value: hub.pingcap.net
    - name: DOCKER_TLS_CERTDIR
      value: ""
    - name: DOCKER_HOST
      value: tcp://localhost:2375
    securityContext:
      privileged: true
    tty: true
    readinessProbe:
      exec:
        command: ["docker", "info"]
      initialDelaySeconds: 10
      failureThreshold: 6
  - name: jnlp
    image: jenkins/inbound-agent:4.10-3
'''


pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            // yamlFile POD_TEMPLATE_FILE
            yaml podYaml
            defaultContainer 'docker'
        }
    }
    environment {
        FILE_SERVER_URL = 'http://fileserver.pingcap.net'
    }
    options {
        timeout(time: 40, unit: 'MINUTES')
        // parallelsAlwaysFailFast()
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
                    cache(path: "./", filter: '**/*', key: "git/pingcap/tidb/rev-${GIT_COMMIT}", restoreKeys: ['git/pingcap/tidb/rev-']) {
                        retry(2) {
                            checkout(
                                changelog: false,
                                poll: false,
                                scm: [
                                    $class: 'GitSCM', branches: [[name: GIT_COMMIT ]],
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
                dir("tidb-test") {
                    cache(path: "./", filter: '**/*', key: "git/pingcap/tidb-test/rev-${ghprbActualCommit}", restoreKeys: ['git/pingcap/tidb-test/rev-']) {
                        retry(2) {
                            script {
                                component.checkout('git@github.com:pingcap/tidb-test.git', 'tidb-test', ghprbTargetBranch, ghprbCommentBody, GIT_CREDENTIALS_ID)
                            }
                        }
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                dir('tidb') {
                    sh "git branch && git status"
                    cache(path: "./bin", filter: '**/*', key: "binary/pingcap/tidb/merged_mysql_test/rev-${BUILD_TAG}") {
                        // FIXME: https://github.com/pingcap/tidb-test/issues/1987
                        sh label: 'tidb-server', script: 'ls bin/tidb-server || make'
                        sh label: 'download binary', script: """
                        chmod +x ${WORKSPACE}/scripts/pingcap/tidb-test/*.sh
                        ${WORKSPACE}/scripts/pingcap/tidb-test/download_pingcap_artifact.sh --pd=${ghprbTargetBranch} --tikv=${ghprbTargetBranch}
                        mv third_bin/* bin/
                        ls -alh bin/
                        """
                    }
                }
                dir('tidb-test') {
                    sh "git branch && git status"
                    cache(path: "./", filter: '**/*', key: "ws/${BUILD_TAG}/tidb-test") {
                        sh 'touch ws-${BUILD_TAG}'
                    }
                }
            }
        }
        stage('Tests') {
            matrix {
                axes {
                    axis {
                        name 'CACHE_ENABLED'
                        values '0', "1"
                    }
                }
                agent{
                    kubernetes {
                        namespace K8S_NAMESPACE
                        defaultContainer 'golang'
                        yamlFile POD_TEMPLATE_FILE
                    }
                } 
                stages {
                    parallel {
                        stage("unistore") {
                            options { timeout(time: 25, unit: 'MINUTES') }
                            steps {
                                dir('tidb') {
                                    cache(path: "./", filter: '**/*', key: "git/pingcap/tidb/rev-${GIT_COMMIT}") { 
                                        sh """git status && ls -alh""" 
                                        cache(path: "./bin", filter: '**/*', key: "binary/pingcap/tidb/merged_mysql_test/rev-${BUILD_TAG}") {
                                            sh label: 'tidb-server', script: 'ls bin/tidb-server && chmod +x bin/tidb-server'
                                        }
                                    }
                                }
                                dir('tidb-test') {
                                    cache(path: "./", filter: '**/*', key: "ws/${BUILD_TAG}/tidb-test") {
                                        sh 'ls mysql_test' // if cache missed, fail it(should not miss).
                                        sh """
                                            mkdir -p bin
                                            cp ${WORKSPACE}/tidb/bin/tidb-server bin/
                                            ./bin/tikv-server -V
                                            ls -alh bin/
                                        """
                                        sh label: "unistore cache_enabled=${CACHE_ENABLED}", script: """
                                            docker run --rm --tty --net=host \
                                                --user $(id -u):$(id -g) \
                                                --env TIDB_SERVER_PATH="/workspace/bin/tidb-server" \
                                                --env CACHE_ENABLED=${CACHE_ENABLED} \
                                                --env TIDB_TEST_STORE_NAME="unistore" \
                                                --ulimit nofile=82920:82920 \
                                                --ulimit stack=16777216:16777216 \
                                                --volume "${PWD}:/workspace" \
                                                --workdir "/workspace" \
                                                golang:1.19-bullseye \
                                                bash ${WORKSPACE}/scripts/pingcap/tidb-test/run-mysql-tests.sh  mysql_test/ 
                                        """
                                    }
                                }
                            }
                        }
                        stage("tikv") {
                            options { timeout(time: 25, unit: 'MINUTES') }
                            steps {
                                dir('tidb') {
                                    cache(path: "./bin", filter: '**/*', key: "binary/pingcap/tidb/merged_mysql_test/rev-${BUILD_TAG}") {
                                        sh label: 'tidb-server', script: 'ls bin/tidb-server && chmod +x bin/tidb-server && ./bin/tidb-server -V'  
                                        sh label: 'tikv-server', script: 'ls bin/tikv-server && chmod +x bin/tikv-server && ./bin/tikv-server -V'
                                        sh label: 'pd-server', script: 'ls bin/pd-server && chmod +x bin/pd-server && ./bin/pd-server -V'  
                                    }
                                }
                                dir('tidb-test') {
                                    cache(path: "./", filter: '**/*', key: "ws/${BUILD_TAG}/tidb-test") {
                                        sh """
                                            mkdir -p bin
                                            cp ${WORKSPACE}/tidb/bin/* bin/ && chmod +x bin/*
                                            ls -alh bin/
                                        """
                                        sh label: "unistore cache_enabled=${CACHE_ENABLED}", script: """
                                            docker run --rm --tty --net=host \
                                                --user $(id -u):$(id -g) \
                                                --env TIDB_SERVER_PATH="/workspace/bin/tidb-server" \
                                                --env CACHE_ENABLED=${CACHE_ENABLED} \
                                                --env TIKV_PATH='127.0.0.1:2379' \
                                                --env TIDB_TEST_STORE_NAME="tikv"\
                                                --ulimit nofile=82920:82920 \
                                                --ulimit stack=16777216:16777216 \
                                                --volume "${PWD}:/workspace" \
                                                --workdir "/workspace" \
                                                golang:1.19-bullseye \
                                                bash ${WORKSPACE}/scripts/pingcap/tidb-test/run-integration-mysql-tests.sh  mysql_test/ 
                                        """
                                    }
                                }
                            }
                        }
                    }
                }
            }        
        }
    }
}
