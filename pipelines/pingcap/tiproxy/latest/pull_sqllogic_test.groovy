// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tiproxy/latest/pod-pull_sqllogic_test.yaml'
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
        CI = "1"
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
                    env
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
            options { timeout(time: 5, unit: 'MINUTES') }
            steps {
                dir("tiproxy") {
                    cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                        retry(2) {
                            script {
                                prow.checkoutRefs(REFS)
                            }
                        }
                    }
                }
                dir("tidb-test") {
                    cache(path: "./", includes: '**/*', key: "git/PingCAP-QE/tidb-test/rev-${REFS.pulls[0].sha}", restoreKeys: ['git/PingCAP-QE/tidb-test/rev-']) {
                        retry(2) {
                            script {
                                component.checkoutV2('git@github.com:PingCAP-QE/tidb-test.git', 'tidb-test', "master", REFS.pulls[0].title, GIT_CREDENTIALS_ID)
                            }
                        }
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                dir('tiproxy') {
                    sh label: 'tiproxy', script: '[ -f bin/tiproxy ] || make'
                }
                dir('tidb-test') {
                    cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}") {
                        sh "touch ws-${BUILD_TAG}"
                        sh "mkdir -p bin"
                        dir("bin") {
                            container("utils") {
                                retry(2) {
                                    sh label: 'download binary', script: """
                                    ${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh \
                                        --tidb=master --pd=master --tikv=master
                                    """
                                }
                            }
                        }
                        sh label: 'prepare thirdparty binary', script: """
                        cp ../tiproxy/bin/tiproxy ./bin/
                        ls -alh bin/
                        ./bin/tidb-server -V
                        ./bin/pd-server -V
                        ./bin/tikv-server -V
                        ./bin/tiproxy --version
                        """
                    }
                }
            }
        }
        stage('TestsGroup1') {
            matrix {
                axes {
                    axis {
                        name 'TEST_PATH_STRING'
                        values 'index/between/1 index/between/10 index/between/100', 'index/between/100 index/between/1000 index/commute/10',
                            'index/commute/100 index/commute/1000_n1 index/commute/1000_n2',
                            'index/delete/1 index/delete/10 index/delete/100 index/delete/1000 index/delete/10000',
                            'random/aggregates_n1 random/aggregates_n2 random/expr' , 'random/expr random/select_n1 random/select_n2',
                            'select random/groupby'
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
                            dir('tidb-test') {
                                cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}") {
                                    sh label: "test_path: ${TEST_PATH_STRING}", script: """
                                        #!/usr/bin/env bash
                                        path_array=(${TEST_PATH_STRING})
                                        for path in \${path_array[@]}; do
                                            echo "test path: \${path}"
                                            SQLLOGIC_TEST_PATH="/git/sqllogictest/test/\${path}" \
                                            make deploy-sqllogictest ARGS="-x -c y -s tikv -p \${SQLLOGIC_TEST_PATH}"
                                        done
                                    """
                                }
                            }
                        }
                    }
                }
            }
        }
        stage('TestsGroup2') {
            matrix {
                axes {
                    axis {
                        name 'TEST_PATH_STRING'
                        values 'index/in/10 index/in/1000_n1 index/in/1000_n2', 'index/orderby/10 index/orderby/100',
                            'index/orderby/1000_n1 index/orderby/1000_n2', 'index/orderby_nosort/10 index/orderby_nosort/100',
                            'index/orderby_nosort/1000_n1 index/orderby_nosort/1000_n2', 'index/random/10 index/random/100 index/random/1000'
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
                            dir('tidb-test') {
                                cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}") {
                                    sh label: "test_path: ${TEST_PATH_STRING}", script: """
                                        #!/usr/bin/env bash
                                        path_array=(${TEST_PATH_STRING})
                                        for path in \${path_array[@]}; do
                                            echo "test path: \${path}"
                                            SQLLOGIC_TEST_PATH="/git/sqllogictest/test/\${path}" \
                                            make deploy-sqllogictest ARGS="-x -c y -s tikv -p \${SQLLOGIC_TEST_PATH}"
                                        done
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
