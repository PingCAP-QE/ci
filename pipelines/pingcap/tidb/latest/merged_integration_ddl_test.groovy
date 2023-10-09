// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/latest/pod-merged_integration_ddl_test.yaml'
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
            options { timeout(time: 10, unit: 'MINUTES') }
            steps {
                dir("tidb") {
                    cache(path: "./", filter: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                        retry(2) {
                            script {
                                prow.checkoutRefs(REFS)
                            }
                        }
                    }
                }
                dir("tidb-test") {
                    cache(path: "./", filter: '**/*', key: "git/PingCAP-QE/tidb-test/rev-${REFS.base_sha}", restoreKeys: ['git/PingCAP-QE/tidb-test/rev-']) {
                        retry(2) {
                            script {
                                component.checkout('git@github.com:PingCAP-QE/tidb-test.git', 'tidb-test', REFS.base_ref, "", GIT_CREDENTIALS_ID)
                            }
                        }
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                dir('tidb') {
                    cache(path: "./bin", filter: '**/*', key: "binary/pingcap/tidb/merged_integration_ddl_test/rev-${BUILD_TAG}") {
                        container("golang") {
                            sh label: 'tidb-server', script: 'ls bin/tidb-server || make'
                            sh label: 'ddl-test', script: 'ls bin/ddltest || make ddltest'
                            sh label: 'download binary', script: """
                            chmod +x ${WORKSPACE}/scripts/PingCAP-QE/tidb-test/*.sh
                            ${WORKSPACE}/scripts/PingCAP-QE/tidb-test/download_pingcap_artifact.sh --pd=${REFS.base_ref} --tikv=${REFS.base_ref}
                            mv third_bin/* bin/
                            ls -alh bin/
                            """
                        }
                    }
                }
                dir('tidb-test') {
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
                        name 'DDL_TEST'
                        values "^TestSimple.*Insert\$", "^TestSimple.*Update\$",  "^TestSimple.*Delete\$", 
                            "^TestSimp(le\$|leMixed\$|leInc\$)", "^TestColumn\$",  "^TestIndex\$"
                    }
                }
                agent{
                    kubernetes {
                        namespace K8S_NAMESPACE
                        yamlFile POD_TEMPLATE_FILE
                        defaultContainer 'golang'
                    }
                } 
                stages {
                    stage("Test") {
                        options { timeout(time: 40, unit: 'MINUTES') }
                        steps {
                            dir('tidb') {
                                cache(path: "./bin", filter: '**/*', key: "binary/pingcap/tidb/merged_integration_ddl_test/rev-${BUILD_TAG}") {
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
                                    container("golang") {
                                        sh label: "ddl_test ${DDL_TEST}", script: """
                                            #!/usr/bin/env bash
                                            echo '[storage]\nreserve-space = "0MB"'> tikv_config.toml
                                            bash ${WORKSPACE}/scripts/PingCAP-QE/tidb-test/start_tikv.sh
                                            cp bin/tidb-server bin/ddltest_tidb-server && ls -alh bin/
                                            export log_level=debug
                                            export PATH=`pwd`/bin:\$PATH
                                            export DDLTEST_PATH="${WORKSPACE}/tidb-test/bin/ddltest"
                                            export TIDB_SERVER_PATH="${WORKSPACE}/tidb-test/bin/ddltest_tidb-server"
                                            cd ddl_test/ && pwd && ./test.sh -test.run="${DDL_TEST}"
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
}
