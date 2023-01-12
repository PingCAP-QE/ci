// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'pingcap/tidb-test'
final POD_TEMPLATE_FILE = 'staging/pipelines/pingcap/tidb-test/latest/pod-ghpr_build.yaml'

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
        timeout(time: 30, unit: 'MINUTES')
    }
    stages {
        stage('Debug info') {
            // options { }  Valid option types: [cache, catchError, checkoutToSubdirectory, podTemplate, retry, script, skipDefaultCheckout, timeout, waitUntil, warnError, withChecks, withContext, withCredentials, withEnv, wrap, ws]
            steps {
                sh label: 'Debug info', script: """
                    printenv
                    echo "-------------------------"
                    go env
                    echo "-------------------------"
                    ls -l /dev/null
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
                    cache(path: "./", filter: '**/*', key: "git/pingcap/tidb/rev-${ghprbActualCommit}", restoreKeys: ['git/pingcap/tidb/rev-']) {
                        retry(2) {
                            checkout(
                                changelog: false,
                                poll: false,
                                scm: [
                                    $class: 'GitSCM', branches: [[name: ghprbTargetBranch]],
                                    doGenerateSubmoduleConfigurations: false,
                                    extensions: [
                                        [$class: 'PruneStaleBranch'],
                                        [$class: 'CleanBeforeCheckout'],
                                        [$class: 'CloneOption', timeout: 15],
                                    ],
                                    submoduleCfg: [],
                                    userRemoteConfigs: [[
                                        refspec: "+refs/heads/*:refs/remotes/origin/*",
                                        url: "https://github.com/pingcap/tidb.git",
                                    ]],
                                ]
                            )
                        }
                    }
                }
                dir("tidb-test") {
                    cache(path: "./", filter: '**/*', key: "git/pingcap/tidb-test/rev-${ghprbActualCommit}", restoreKeys: ['git/pingcap/tidb-test/rev-']) {
                        retry(2) {
                            checkout(
                                changelog: false,
                                poll: false,
                                scm: [
                                    $class: 'GitSCM', branches: [[name: ghprbActualCommit]],
                                    doGenerateSubmoduleConfigurations: false,
                                    extensions: [
                                        [$class: 'PruneStaleBranch'],
                                        [$class: 'CleanBeforeCheckout'],
                                        [$class: 'CloneOption', timeout: 15],
                                    ],
                                    submoduleCfg: [],
                                    userRemoteConfigs: [[
                                        credentialsId: GIT_CREDENTIALS_ID,
                                        refspec: "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*",
                                        url: "git@github.com:${GIT_FULL_REPO_NAME}.git",
                                    ]],
                                ]
                            )
                        }
                    }
                }
            }
        }
        stage("Build"){
            steps {
                dir("tidb-test") {
                    sh """
                    TIDB_SRC_PATH=${WORKSPACE}/tidb make check
                    for binCase in {partition_test,coprocessor_test,concurrent-sql}; do
                        cd \${binCase} && chmod +x build.sh && ./build.sh
                        cd ..
                        sleep 5
                    done
                    """
                }
            }
        }
        // stage("Upload"){
        //     steps {
        //         dir("tidb-test") {

        //             sh """
        //                 rm -rf .git
        //                 tar czvf tidb-test.tar.gz ./*
        //                 filepath="builds/pingcap/tidb-test/pr/${ghprbActualCommit}/centos7/tidb-test.tar.gz"
        //                 refspath="refs/pingcap/tidb-test/pr/${ghprbPullId}/sha1"
        //                 curl -f -F ${filepath}=@tidb-test.tar.gz ${FILE_SERVER_URL}/upload
        //                 echo "pr/${ghprbActualCommit}" > sha1
        //                 curl -f -F ${refspath}=@sha1 ${FILE_SERVER_URL}/upload   
        //             """
        //         }
        //     }
        // }
    }
}
