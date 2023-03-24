// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
// @Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tiflow"
final GIT_FULL_REPO_NAME = 'pingcap/tiflow'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tiflow/latest/pod-pull_engine_integration_test.yaml'
final IMAGE_TAG = "engine-ci-test-pull-${ghprbPullId}"
final ENGINE_TEST_TAG = "dataflow:test"

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
        timeout(time: 60, unit: 'MINUTES')
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
                dir("tiflow") {
                    cache(path: "./", filter: '**/*', key: "git/pingcap/tiflow/rev-${ghprbActualCommit}", restoreKeys: ['git/pingcap/tiflow/rev-']) {
                        retry(2) {
                            checkout(
                                changelog: false,
                                poll: false,
                                scm: [
                                    $class: 'GitSCM', branches: [[name: ghprbActualCommit ]],
                                    doGenerateSubmoduleConfigurations: false,
                                    extensions: [
                                        [$class: 'PruneStaleBranch'],
                                        [$class: 'CleanBeforeCheckout'],
                                        [$class: 'CloneOption', timeout: 15],
                                    ],
                                    submoduleCfg: [],
                                    userRemoteConfigs: [[
                                        refspec: "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*",
                                        url: "https://github.com/${GIT_FULL_REPO_NAME}.git",
                                    ]],
                                ]
                            )
                        }
                    }
                }
            }
        }
        stage("prepare") {
            options { timeout(time: 20, unit: 'MINUTES') }
            steps {
                container("docker") { 
                    dir("tiflow") {
                        withCredentials([usernamePassword(credentialsId: 'harbor-tiflow-engine', usernameVariable: 'HARBOR_CRED_USR', passwordVariable: 'HARBOR_CRED_PSW')]) {
                            sh label: "check env", script: """
                                sleep 10
                                docker version || true
                                docker-compose version || true
                                echo "$HARBOR_CRED_PSW" | docker login -u $HARBOR_CRED_USR --password-stdin hub.pingcap.net
                            """
                        }
                        sh label: "build binary for integration test", script: """
                            git config --global --add safe.directory '*'
                            make tiflow tiflow-demo
                            touch ./bin/tiflow-chaos-case
                            make engine_image_from_local
                            docker tag ${ENGINE_TEST_TAG} hub.pingcap.net/tiflow/engine:${IMAGE_TAG}
                            docker push hub.pingcap.net/tiflow/engine:${IMAGE_TAG}
                        """                              
                    }
                }
            }
        }
        stage('Tests') {
            matrix {
                axes {
                    axis {
                        name 'TEST_GROUP'
                        values 'G00', 'G01', 'G02', 'G03', 'G04', 'others'
                    }
                }
                agent{
                    kubernetes {
                        namespace K8S_NAMESPACE
                        yamlFile POD_TEMPLATE_FILE
                        defaultContainer 'docker'
                    }
                } 
                stages {
                    stage("Test") {
                        options { timeout(time: 30, unit: 'MINUTES') }
                        steps {
                            dir('tiflow') {
                                cache(path: "./", filter: '**/*', key: "git/pingcap/tiflow/rev-${ghprbActualCommit}") {
                                    container("golang") {
                                        sh label: "prepare", script: """
                                            git rev-parse HEAD
                                            git status
                                            sync_diff_download_url="http://fileserver.pingcap.net/download/test/tiflow/engine/ci/sync_diff.tar.gz"
                                            mkdir -p ./bin/ && cd ./bin/
                                            curl \${sync_diff_download_url} | tar -xz
                                            ./sync_diff_inspector -V
                                            cd -        
                                        """
                                    }
                                    withCredentials([usernamePassword(credentialsId: 'harbor-tiflow-engine', usernameVariable: 'HARBOR_CRED_USR', passwordVariable: 'HARBOR_CRED_PSW')]) {
                                        sh label: "check env", script: """
                                            sleep 10
                                            docker version || true
                                            docker-compose version || true
                                            echo "$HARBOR_CRED_PSW" | docker login -u $HARBOR_CRED_USR --password-stdin hub.pingcap.net
                                        """
                                    }
                                    sh label: "prepare image", script: """
                                        TIDB_CLUSTER_BRANCH=${ghprbTargetBranch}
                                        TIDB_TEST_TAG=nightly

                                        docker pull hub.pingcap.net/tiflow/minio:latest
                                        docker tag hub.pingcap.net/tiflow/minio:latest minio/minio:latest
                                        docker pull hub.pingcap.net/tiflow/minio:mc
                                        docker tag hub.pingcap.net/tiflow/minio:mc minio/mc:latest
                                        docker pull hub.pingcap.net/tiflow/mysql:5.7
                                        docker tag hub.pingcap.net/tiflow/mysql:5.7 mysql:5.7
                                        docker pull hub.pingcap.net/tiflow/mysql:8.0
                                        docker tag hub.pingcap.net/tiflow/mysql:8.0 mysql:8.0
                                        docker pull hub.pingcap.net/tiflow/etcd:latest
                                        docker tag hub.pingcap.net/tiflow/etcd:latest quay.io/coreos/etcd:latest
                                        docker pull hub.pingcap.net/qa/tidb:\${TIDB_CLUSTER_BRANCH}
                                        docker tag hub.pingcap.net/qa/tidb:\${TIDB_CLUSTER_BRANCH} pingcap/tidb:\${TIDB_TEST_TAG} 
                                        docker pull hub.pingcap.net/qa/tikv:\${TIDB_CLUSTER_BRANCH}
                                        docker tag hub.pingcap.net/qa/tikv:\${TIDB_CLUSTER_BRANCH} pingcap/tikv:\${TIDB_TEST_TAG}
                                        docker pull hub.pingcap.net/qa/pd:\${TIDB_CLUSTER_BRANCH}
                                        docker tag hub.pingcap.net/qa/pd:\${TIDB_CLUSTER_BRANCH} pingcap/pd:\${TIDB_TEST_TAG}
                                        docker pull hub.pingcap.net/tiflow/engine:${IMAGE_TAG}
                                        docker tag hub.pingcap.net/tiflow/engine:${IMAGE_TAG} ${ENGINE_TEST_TAG}
                                        docker images
                                    """
                                    sh label: "${TEST_GROUP}", script: """
                                        git config --global --add safe.directory '*'
                                        chmod +x engine/test/integration_tests/*.sh
                                        ./engine/test/integration_tests/run_group.sh ${TEST_GROUP}
                                    """
                                }
                            }
                        }
                        post {
                            failure {
                                sh label: "collect logs", script: """
                                    ls /tmp/tiflow_engine_test/ || true
                                    tar -cvzf log-${TEST_GROUP}.tar.gz \$(find /tmp/tiflow_engine_test/ -type f -name "*.log") || true  
                                    ls -alh log-${TEST_GROUP}.tar.gz || true
                                """
                                archiveArtifacts artifacts: "log-${TEST_GROUP}.tar.gz", allowEmptyArchive: true
                            }
                        }
                    }
                }
            }        
        }
        stage("cleanup") {
            steps {
                container("docker") { 
                    withCredentials([usernamePassword(credentialsId: 'harbor-tiflow-engine', usernameVariable: 'HARBOR_CRED_USR', passwordVariable: 'HARBOR_CRED_PSW')]) {
                        sh label: "check env", script: """
                            sleep 10
                            docker version || true
                            docker-compose version || true
                            echo "$HARBOR_CRED_PSW" | docker login -u $HARBOR_CRED_USR --password-stdin hub.pingcap.net
                        """
                    }
                    sh """
                        docker pull hub.pingcap.net/tiflow/engine:dummy || true
                        docker tag hub.pingcap.net/tiflow/engine:dummy hub.pingcap.net/tiflow/engine:${IMAGE_TAG} || true
                        docker push hub.pingcap.net/tiflow/engine:${IMAGE_TAG} || true
                    """
                }
            }
        }
    }
}
