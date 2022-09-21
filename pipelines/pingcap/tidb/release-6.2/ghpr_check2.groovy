// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and release-6.2.x branches
final K8S_NAMESPACE = "jenkins-tidb"
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/latest/pod-ghpr_check2.yaml'

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'golang'
        }
    }
    options {
        timeout(time: 30, unit: 'MINUTES')
        parallelsAlwaysFailFast()
    }
    environment {
        FILE_SERVER_URL = 'http://fileserver.pingcap.net'
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
            // FIXME(wuhuizuo): catch AbortException and set the job abort status
            // REF: https://github.com/jenkinsci/git-plugin/blob/master/src/main/java/hudson/plugins/git/GitSCM.java#L1161
            steps {
                dir('tidb') {
                    cache(path: "./", filter: '**/*', key: "git/pingcap/tidb/rev-${ghprbActualCommit}", restoreKeys: ['git/pingcap/tidb/rev-']) {
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
                                        [$class: 'CloneOption', timeout: 5],
                                    ],
                                    submoduleCfg: [],
                                    userRemoteConfigs: [[
                                        refspec: "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*",
                                        url: "https://github.com/${GIT_FULL_REPO_NAME}.git"
                                    ]],
                                ]
                            )
                        }
                    }
                }
            }
        }
        stage("Prepare") {
            steps {
                dir('tidb') {
                    cache(path: "./", filter: '**/*', key: "binary/pingcap/tidb/tidb-server/rev-${ghprbActualCommit}") {
                        sh label: 'tidb-server', script: 'ls bin/explain_test_tidb-server || go build -o bin/explain_test_tidb-server github.com/pingcap/tidb/tidb-server'
                    }
                    sh label: 'tikv-server', script: '''#! /usr/bin/env bash

                        # parse tikv branch from comment.
                        #   tikv=branchXxx or tikv=pr/123
                        commentBodyBranchReg="\\btikv\\s*=\\s*(\\S+)\\b"
                        if [[ "${ghprbCommentBody}" =~ $commentBodyBranchReg ]]; then
                            TIKV_BRANCH=${BASH_REMATCH[1]}
                        else
                            TIKV_BRANCH=${ghprbTargetBranch}
                        fi
                        echo "TIKV_BRANCH=${TIKV_BRANCH}"

                        refs="${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_BRANCH}/sha1"
                        sha1="$(curl --fail ${refs} | head -1)"
                        url="${FILE_SERVER_URL}/download/builds/pingcap/tikv/${sha1}/centos7/tikv-server.tar.gz"
                        curl --fail ${url} | tar xz
                        '''
                    sh label: 'pd-server', script: '''#! /usr/bin/env bash

                        # parse pd branch from comment.
                        #   pd=branchXxx or pd=pr/123
                        commentBodyBranchReg="\\bpd\\s*=\\s*(\\S+)\\b"
                        if [[ "${ghprbCommentBody}" =~ $commentBodyBranchReg ]]; then
                            PD_BRANCH=${BASH_REMATCH[1]}
                        else
                            PD_BRANCH=${ghprbTargetBranch}
                        fi
                        echo "PD_BRANCH=${PD_BRANCH}"

                        refs="${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1"
                        sha1="$(curl --fail ${refs} | head -1)"
                        url="${FILE_SERVER_URL}/download/builds/pingcap/pd/${sha1}/centos7/pd-server.tar.gz"
                        curl --fail ${url} | tar xz bin
                        '''
                    
                    // cache it for other pods
                    cache(path: "./", filter: '**/*', key: "ws/${BUILD_TAG}") {
                        sh  'touch rev-${ghprbActualCommit}'
                    }
                }
            }
        }
        stage('Checks') {
            matrix {
                axes {
                    axis {
                        name 'SCRIPT_AND_ARGS'
                        values(
                            'explaintest.sh y', 
                            'explaintest.sh n', 
                            'run_real_tikv_tests.sh bazel_brietest', 
                            'run_real_tikv_tests.sh bazel_pessimistictest', 
                            'run_real_tikv_tests.sh bazel_sessiontest', 
                            'run_real_tikv_tests.sh bazel_statisticstest',
                            'run_real_tikv_tests.sh bazel_txntest',
                            'run_real_tikv_tests.sh bazel_addindextest',
                        )
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
                    stage('Test')  {
                        options { timeout(time: 30, unit: 'MINUTES') }
                        steps {
                            dir('tidb') {
                                cache(path: "./", filter: '**/*', key: "ws/${BUILD_TAG}") {
                                    sh 'ls -l rev-${ghprbActualCommit}' // will fail when not found in cache or no cached.
                                }

                                sh 'chmod +x ../scripts/pingcap/tidb/*.sh'
                                sh "${WORKSPACE}/scripts/pingcap/tidb/${SCRIPT_AND_ARGS}"
                            }
                        }
                        post {
                            failure {
                                dir("checks-collation-enabled") {
                                    archiveArtifacts(artifacts: 'pd*.log, tikv*.log, explain-test.out', allowEmptyArchive: true)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    post {
        success {
            // Upload check flag to fileserver
            sh "echo done > done && curl -F ci_check/${JOB_NAME}/${ghprbActualCommit}=@done ${FILE_SERVER_URL}/upload"
        }

        // TODO(wuhuizuo): put into container lifecyle preStop hook.
        always {
            container('report') {                
                sh "bash scripts/plugins/report_job_result.sh ${currentBuild.result} result.json | true"
            }
            archiveArtifacts(artifacts: 'result.json', fingerprint: true, allowEmptyArchive: true)
        }
    }
}