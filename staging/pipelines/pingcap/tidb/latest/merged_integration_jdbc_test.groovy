// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final COMMIT_CONTEXT = 'staging/integration-jdbc-test'
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'staging/pipelines/pingcap/tidb/latest/pod-merged_integration_jdbc_test.yaml'

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'java'
        }
    }
    environment {
        FILE_SERVER_URL = 'http://fileserver.pingcap.net'
        GITHUB_TOKEN = credentials('github-bot-token')
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
                dir("tidb-test") {
                    cache(path: "./", filter: '**/*', key: "git/pingcap/tidb-test/rev-${GIT_MERGE_COMMIT}", restoreKeys: ['git/pingcap/tidb-test/rev-']) {
                        retry(2) {
                            script {
                                component.checkout('git@github.com:pingcap/tidb-test.git', 'tidb-test', GIT_BASE_BRANCH, "", GIT_CREDENTIALS_ID)
                            }
                        }
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                dir('tidb') {
                    cache(path: "./bin", filter: '**/*', key: "binary/pingcap/tidb/merged_integration_jdbc_test/rev-${BUILD_TAG}") {
                        container("java") {
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
                dir('tidb-test') {
                    cache(path: "./", filter: '**/*', key: "ws/${BUILD_TAG}/tidb-test") {
                        sh 'touch ws-${BUILD_TAG}'
                        // sh """
                        // cd tidb_test && ./build.sh && cd ..
                        // cd randgen-test && ./build.sh && cd ..
                        // """
                    }
                }
            }
        }
        stage('Tests') {
            matrix {
                axes {
                    axis {
                        name 'TEST_PARAMS'
                        values 'jdbc8_test ./test_fast.sh', 'jdbc8_test ./test_slow.sh', 'hibernate_test/hibernate-orm-test ./test.sh',
                            'mybatis_test ./test.sh', 'jooq_test ./test.sh', 'tidb_jdbc_test/tidb_jdbc8_test ./test_fast.sh',
                            'tidb_jdbc_test/tidb_jdbc8_test ./test_slow.sh', 'tidb_jdbc_test/tidb_jdbc_unique_test ./test.sh',
                            'tidb_jdbc_test/tidb_jdbc8_tls_test ./test_slow.sh', 'tidb_jdbc_test/tidb_jdbc8_tls_test ./test_tls.sh'
                    }
                    axis {
                        name 'TEST_STORE'
                        values "tikv"
                    }
                }
                agent{
                    kubernetes {
                        namespace K8S_NAMESPACE
                        yamlFile POD_TEMPLATE_FILE
                        defaultContainer 'java'
                    }
                } 
                stages {
                    stage("Test") {
                        options { timeout(time: 40, unit: 'MINUTES') }
                        steps {
                            dir('tidb') {
                                cache(path: "./bin", filter: '**/*', key: "binary/pingcap/tidb/merged_integration_jdbc_test/rev-${BUILD_TAG}") {
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
                                    container("java") {
                                        sh label: "test_params=${TEST_PARAMS} ", script: """
                                            #!/usr/bin/env bash
                                            params_array=(\${TEST_PARAMS})
                                            TEST_DIR=\${params_array[0]}
                                            TEST_SCRIPT=\${params_array[1]}
                                            echo "TEST_DIR=\${TEST_DIR}"
                                            echo "TEST_SCRIPT=\${TEST_SCRIPT}"

                                            if [[ "${TEST_STORE}" == "tikv" ]]; then
                                                echo '[storage]\nreserve-space = "0MB"'> tikv_config.toml
                                                bash ${WORKSPACE}/scripts/pingcap/tidb-test/start_tikv.sh
                                                export TIDB_SERVER_PATH="${WORKSPACE}/tidb-test/bin/tidb-server"
                                                export TIKV_PATH="127.0.0.1:2379"
                                                export TIDB_TEST_STORE_NAME="tikv"
                                                cd \${TEST_DIR} && \${TEST_SCRIPT}
                                            else
                                                export TIDB_SERVER_PATH="${WORKSPACE}/tidb-test/bin/tidb-server"
                                                export TIDB_TEST_STORE_NAME="unistore"
                                                cd \${TEST_DIR} && \${TEST_SCRIPT}
                                            fi
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
