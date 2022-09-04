// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and release-6.2.x branches
final K8S_COULD = "kubernetes-ksyun"
final K8S_NAMESPACE = "jenkins-tidb"
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/latest/pod-ghpr_mysql_test.yaml'

// TODO(wuhuizuo): tidb-test should delivered by docker image.
pipeline {
    agent {
        kubernetes {
            cloud K8S_COULD
            namespace K8S_NAMESPACE
            defaultContainer 'golang'
            yamlFile POD_TEMPLATE_FILE
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
                    go env
                    echo "-------------------------"
                    echo "debug command: kubectl -n ${K8S_NAMESPACE} exec -ti ${NODE_NAME} bash"
                """
                container(name: 'net-tool') {
                    sh 'dig github.com'
                }
            }
        }        
        stage('MySQL Tests') {
            matrix {
                axes {
                    axis {
                        name 'PART'
                        values '1', '2', '3', '4'
                    }
                }
                agent{
                    kubernetes {
                        cloud K8S_COULD
                        namespace K8S_NAMESPACE
                        defaultContainer 'golang'
                        yamlFile POD_TEMPLATE_FILE
                    }
                }
                stages {
                    stage('Prepare') {
                        options { timeout(time: 10, unit: 'MINUTES') }
                        steps {
                            dir("tidb") {
                                retry(3){
                                    sh label: 'get tidb-server binnary', script: '''#! /usr/bin/env bash
                                        tidb_done_url="${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/done"
                                        tidb_url="${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/tidb-server.tar.gz"
                                        while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
                                        curl --fail ${tidb_url} | tar xz
                                        '''
                                }
                            }
                            dir("tidb-test") {
                                sh label: 'download tidb-test and build mysql_test', script: '''#! /usr/bin/env bash
                                    trunkBranch="master"
                                    releaseOrHotfixBranchReg="^(release-)?([0-9]+\\.[0-9]+)(\\.[0-9]+\\-.+)?"
                                    featureBranchReg="^feature[/_].+"
                                    commentBodyBranchReg="\\btidb[-_]test\\s*=\\s*(\\S+)\\b"

                                    if [[ "${ghprbCommentBody}" =~ $commentBodyBranchReg ]]; then
                                        TIDB_TEST_BRANCH=${BASH_REMATCH[1]}
                                    elif [[ "${ghprbTargetBranch}" =~ $releaseOrHotfixBranchReg ]]; then
                                        TIDB_TEST_BRANCH="release-${BASH_REMATCH[2]}"
                                    elif [[ "${ghprbTargetBranch}" =~ $featureBranchReg ]]; then
                                        TIDB_TEST_BRANCH="${trunkBranch}"
                                    else
                                        TIDB_TEST_BRANCH="${ghprbTargetBranch}"
                                    fi

                                    echo "TIDB_TEST_BRANCH or PR: ${TIDB_TEST_BRANCH}"

                                    tidb_test_refs="${FILE_SERVER_URL}/download/refs/pingcap/tidb-test/${TIDB_TEST_BRANCH}/sha1"
                                    echo "${tidb_test_refs}"
                                    while ! curl --output /dev/null --silent --head --fail ${tidb_test_refs}; do sleep 5; done
                                    tidb_test_sha1="$(curl --fail ${tidb_test_refs})"

                                    tidb_test_url="${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/${tidb_test_sha1}/centos7/tidb-test.tar.gz"
                                    while ! curl --output /dev/null --silent --head --fail ${tidb_test_url}; do sleep 5; done
                                    curl --fail ${tidb_test_url} | tar xz

                                    cd mysql_test
                                    TIDB_SRC_PATH=$(realpath ../../tidb) ./build.sh
                                    '''
                            }
                            // TODO(wuhuizuo): store files:
                            // - tidb/bin/tidb-server
                            // - tidb-test/mysql-test
                        }
                    }
                    stage("Test") {
                        options { timeout(time: 25, unit: 'MINUTES') }
                        steps {
                            dir("tidb-test/mysql_test") {
                                sh label: "part ${PART}", script: '''#! /usr/bin/env bash

                                pwd && ls -alh
                                exit_code=0
                                { # try block
                                    TIDB_SERVER_PATH=../../tidb/bin/tidb-server ./test.sh -backlist=1 -part=${PART}
                                } || { # catch block
                                    exit_code="$?"  # exit code of last command which is 44
                                }
                                # finally block:
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb

                                if [[ "exit_code" != '0' ]]; then
                                    exit \${exit_code}
                                fi
                                '''
                            }
                        }
                        post{
                            always {
                                junit(testResults: "**/result.xml")
                            }
                            failure {
                                archiveArtifacts(artifacts: 'mysql-test.out*', allowEmptyArchive: true)
                            }
                        }
                    }
                }
            }        
        }
    }
}