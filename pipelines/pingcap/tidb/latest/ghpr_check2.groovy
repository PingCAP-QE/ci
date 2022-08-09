// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and release-6.2.x branches
final K8S_COULD = "kubernetes"
final K8S_NAMESPACE = "apps"
// final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_OPENAPI_CREDENTIALS_ID = 'sre-bot-token'
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final GIT_TRUNK_BRANCH = "master"
final CODECOV_TOKEN_CREDENTIAL_ID = 'codecov-token-tidb'
final ENV_GOPATH = "/home/jenkins/agent/workspace/go"
final ENV_GOCACHE = "${ENV_GOPATH}/.cache/go-build"
final POD_TEMPLATE = """
apiVersion: v1
kind: Pod
spec:
  containers:
    - name: golang
      image: "hub.pingcap.net/wangweizhen/tidb_image:20220805"
      tty: true
      resources:
        requests:
          memory: 8Gi
          cpu: 6
      command: [/bin/sh, -c]
      args: [cat]
      env:
        - name: GOPATH
          value: ${ENV_GOPATH}
        - name: GOCACHE
          value: ${ENV_GOCACHE} 
"""

// TODO(wuhuizuo): cache git code with https://plugins.jenkins.io/jobcacher/ and S3 service.
pipeline {
    agent {
        kubernetes {
            cloud K8S_COULD
            namespace K8S_NAMESPACE            
            defaultContainer 'golang'
            yaml POD_TEMPLATE
        }
    }
    environment {
        FILE_SERVER_URL = 'http://fileserver.pingcap.net'
    }
    options {
        timeout(time: 20, unit: 'MINUTES')
    }
    stages {
        stage('debug info') {
            steps {
                sh label: 'Debug info', script: """
                printenv
                echo "-------------------------"
                echo "debug command: kubectl -n ${K8S_NAMESPACE} exec -ti ${NODE_NAME} bash"
                """
            }
        }
        stage('Checkout') {
            // FIXME(wuhuizuo): catch AbortException and set the job abort status
            // REF: https://github.com/jenkinsci/git-plugin/blob/master/src/main/java/hudson/plugins/git/GitSCM.java#L1161
            steps {
                dir('git') {
                    cache(path: "./", filter: '**/*', key: "pingcap-tidb-cache-src-${ghprbActualCommit}", restoreKeys: ['pingcap-tidb-cache-src-']) {
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
        stage("Prepare binnaries and scripts") {
            steps {
                dir('git') {
                    sh label: 'tidb-server', script: 'go build -o bin/explain_test_tidb-server github.com/pingcap/tidb/tidb-server'
                    withEnv(["TIKV_BRANCH=${ghprbTargetBranch}", "PD_BRANCH=${ghprbTargetBranch}"]) {
                        sh label: 'pd-server', script: '''
                            refs="${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_BRANCH}/sha1"
                            sha1="$(curl --fail ${refs} | head -1)"
                            url="${FILE_SERVER_URL}/download/builds/pingcap/tikv/${sha1}/centos7/tikv-server.tar.gz"
                            curl --fail ${url} | tar xz
                            '''               
                        sh label: 'pd-server', script: '''
                            refs="${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1"
                            sha1="$(curl --fail ${refs} | head -1)"
                            url="${FILE_SERVER_URL}/download/builds/pingcap/pd/${sha1}/centos7/pd-server.tar.gz"
                            curl --fail ${url} | tar xz bin
                            '''
                    }
                    stash includes: "**", name: "tidb"
                }
                sh 'chmod +x scripts/pingcap/tidb/*.sh'
            }
        }
        stage('Checks') {
            failFast true
            parallel {             
                stage('New Collation Enabled')  {
                    options { timeout(time: 15, unit: 'MINUTES') }
                    steps { 
                        dir("checks-collation-enabled") {
                            unstash("tidb")
                            sh '${WORKSPACE}/scripts/pingcap/tidb/explaiintest.sh y'                            
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
                stage('New Collation Disabled') {
                    options { timeout(time: 15, unit: 'MINUTES') }
                    steps { 
                        dir("checks-collation-disabled") {
                            unstash("tidb")
                            sh '${WORKSPACE}/scripts/pingcap/tidb/explaiintest.sh n'
                        }
                    }
                    post {                        
                        failure {
                            dir("checks-collation-disabled") {
                                archiveArtifacts(artifacts: 'pd*.log, tikv*.log, explain-test.out', allowEmptyArchive: true)
                            }
                        }
                    }
                }
                stage('Real TiKV Tests - brietest') {       
                    options { timeout(time: 45, unit: 'MINUTES') }
                    steps { 
                        dir("real-tikv-tests-brietest") {
                            unstash("tidb")
                            sh '${WORKSPACE}/scripts/pingcap/tidb/run_real_tikv_tests.sh brietest'
                        }
                    }

                    post {                       
                        failure {
                            dir("real-tikv-tests-brietest") {
                                archiveArtifacts(artifacts: 'pd*.log, tikv*.log, explain-test.out', allowEmptyArchive: true)
                            }
                        }
                    }
                }
                stage('Real TiKV Tests - pessimistictest') {
                    options { timeout(time: 45, unit: 'MINUTES') }
                    steps {
                        dir('real-tikv-tests-pessimistictest') {
                            unstash("tidb")
                            sh '${WORKSPACE}/scripts/pingcap/tidb/run_real_tikv_tests.sh pessimistictest' 
                        }
                    }
                    post {                        
                        failure {
                            dir('real-tikv-tests-pessimistictest') {
                                archiveArtifacts(artifacts: 'pd*.log, tikv*.log, explain-test.out', allowEmptyArchive: true)
                            }
                        }
                    }
                }
                stage('Real TiKV Tests - sessiontest') {
                    options { timeout(time: 45, unit: 'MINUTES') }
                    steps {
                        dir('real-tikv-tests-sessiontest') {
                            unstash("tidb")
                            sh '${WORKSPACE}/scripts/pingcap/tidb/run_real_tikv_tests.sh sessiontest'
                        }
                    }
                    post {                        
                        failure {
                            dir('real-tikv-tests-sessiontest') {
                                archiveArtifacts(artifacts: 'pd*.log, tikv*.log, explain-test.out', allowEmptyArchive: true)
                            }
                        }
                    }
                }
                stage('Real TiKV Tests - statisticstest') {
                    options { timeout(time: 45, unit: 'MINUTES') }
                    steps {
                        dir('real-tikv-tests-statisticstest') {
                            unstash("tidb")
                            sh '${WORKSPACE}/scripts/pingcap/tidb/run_real_tikv_tests.sh statisticstest'
                        }
                    }
                    post {                        
                        failure {
                            dir('real-tikv-tests-statisticstest') {
                                archiveArtifacts(artifacts: 'pd*.log, tikv*.log, explain-test.out', allowEmptyArchive: true)
                            }
                        }
                    }
                }
                stage('Real TiKV Tests - txntest') {
                    options { timeout(time: 45, unit: 'MINUTES') }
                    steps {
                        dir('real-tikv-tests-txntest') {
                            unstash("tidb")
                            sh '${WORKSPACE}/scripts/pingcap/tidb/run_real_tikv_tests.sh txntest' 
                        }
                    }
                    post {                      
                        failure {
                            dir('real-tikv-tests-txntest') {
                                archiveArtifacts(artifacts: 'pd*.log, tikv*.log, explain-test.out', allowEmptyArchive: true)
                            }
                        }
                    }
                }
            }
        }
        stage("Upload check flag to fileserver") {
            steps {
                sh "echo done > done && curl -F ci_check/${JOB_NAME}/${ghprbActualCommit}=@done ${FILE_SERVER_URL}/upload"
            }
        }
    }
}