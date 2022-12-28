// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final COMMIT_CONTEXT = 'staging/tiflash-test'
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'staging/pipelines/pingcap/tidb/latest/pod-merged_tiflash_test.yaml'

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
        GITHUB_TOKEN = credentials('github-bot-token')
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
                        sh label: 'tidb-server', script: 'ls bin/tidb-server || make'
                        sh label: 'download binary', script: """
                        chmod +x ${WORKSPACE}/scripts/pingcap/tidb-test/*.sh
                        ${WORKSPACE}/scripts/pingcap/tidb-test/download_pingcap_artifact.sh --pd=${GIT_BASE_BRANCH} --tikv=${GIT_BASE_BRANCH}
                        mv third_bin/* bin/
                        ls -alh bin/
                        """
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
                        // sh label: 'tikv-server', script: 'ls bin/tikv-server && chmod +x bin/tikv-server && ./bin/tikv-server -V'
                        // sh label: 'pd-server', script: 'ls bin/pd-server && chmod +x bin/pd-server && ./bin/pd-server -V' 
                        sh label: 'build tmp tidb image', script: """
                        printf 'FROM hub.pingcap.net/jenkins/alpine-glibc:tiflash-test \n
                        COPY bin/tidb-server /tidb-server \n
                        WORKDIR / \n
                        EXPOSE 4000 \n
                        ENTRYPOINT ["/usr/local/bin/dumb-init", "/tidb-server"] \n' > Dockerfile

                        cat Dockerfile
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
                            sh label: 'display some log', script: 'find log -name '*.log' | xargs tail -n 50'
                        }
                    }
                }
            }       
        }
    }
    post {
        always {
            script {
                println "build url: ${env.BUILD_URL}"
                println "build blueocean url: ${env.RUN_DISPLAY_URL}"
                println "build name: ${env.JOB_NAME}"
                println "build number: ${env.BUILD_NUMBER}"
                println "build status: ${currentBuild.currentResult}"
            } 
        }
        // success {
        //     container('status-updater') {
        //         sh """
        //             set +x
        //             github-status-updater \
        //                 -action update_state \
        //                 -token ${GITHUB_TOKEN} \
        //                 -owner pingcap \
        //                 -repo tidb \
        //                 -ref  ${GIT_MERGE_COMMIT} \
        //                 -state success \
        //                 -context "${COMMIT_CONTEXT}" \
        //                 -description "test success" \
        //                 -url "${env.RUN_DISPLAY_URL}"
        //         """
        //     }
        // }

        // unsuccessful {
        //     container('status-updater') {
        //         sh """
        //             set +x
        //             github-status-updater \
        //                 -action update_state \
        //                 -token ${GITHUB_TOKEN} \
        //                 -owner pingcap \
        //                 -repo tidb \
        //                 -ref  ${GIT_MERGE_COMMIT} \
        //                 -state failure \
        //                 -context "${COMMIT_CONTEXT}" \
        //                 -description "test failed" \
        //                 -url "${env.RUN_DISPLAY_URL}"
        //         """
        //     }
        // }
    }
}
