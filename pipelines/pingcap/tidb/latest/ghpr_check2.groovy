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
        }
    }
    environment {
        FILE_SERVER_URL = 'http://fileserver.pingcap.net'
    }
    options {
        timeout(time: 20, unit: 'MINUTES')
        parallelsAlwaysFailFast()
    }
    stages {
        stage('Checks') {
            matrix {
                axes {
                    axis {
                        name 'SCRIPT_AND_ARGS'
                        values( 
                            'explaintest.sh y', 
                            'explaintest.sh n', 
                            'run_real_tikv_tests.sh brietest', 
                            'run_real_tikv_tests.sh pessimistictest', 
                            'run_real_tikv_tests.sh sessiontest', 
                            'run_real_tikv_tests.sh statisticstest',
                            'run_real_tikv_tests.sh txntest',
                            )
                    }
                }
                agent{
                    kubernetes {
                        cloud K8S_COULD
                        namespace K8S_NAMESPACE
                        defaultContainer 'golang'
                        yaml POD_TEMPLATE
                    }
                }
                stages {                    
                    stage('Checkout') {
                        // FIXME(wuhuizuo): catch AbortException and set the job abort status
                        // REF: https://github.com/jenkinsci/git-plugin/blob/master/src/main/java/hudson/plugins/git/GitSCM.java#L1161
                        steps {
                            dir('tidb') {
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
                    stage("Prepare") {
                        steps {
                            dir('tidb') {
                                cache(path: "${ENV_GOPATH}/pkg/mod", key: "pingcap-tidb-gomodcache-${ghprbActualCommit}", restoreKeys: ['pingcap-tidb-gomodcache-']) {
                                    cache(path: ENV_GOCACHE, key: "pingcap-tidb-gocache-${ghprbActualCommit}", restoreKeys: ['pingcap-tidb-gocache-']) {
                                        cache(path: "./", filter: "bin/*", key: "pingcap-tidb-bin-${ghprbActualCommit}_tidb-server") {
                                            sh label: 'tidb-server', script: 'ls bin/explain_test_tidb-server || go build -o bin/explain_test_tidb-server github.com/pingcap/tidb/tidb-server'
                                        }
                                    }
                                }
                                withEnv(["TIKV_BRANCH=${ghprbTargetBranch}", "PD_BRANCH=${ghprbTargetBranch}"]) {
                                    sh label: 'tikv-server', script: '''
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
                            }
                            sh 'chmod +x scripts/pingcap/tidb/*.sh'
                        }
                    }
                    stage('Test')  {
                        options { timeout(time: 30, unit: 'MINUTES') }
                        steps { 
                            dir('tidb') {
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
        stage("Upload check flag to fileserver") {
            steps {
                sh "echo done > done && curl -F ci_check/${JOB_NAME}/${ghprbActualCommit}=@done ${FILE_SERVER_URL}/upload"
            }
        }
    }
}