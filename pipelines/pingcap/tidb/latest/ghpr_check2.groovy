// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and release-6.2.x branches
final K8S_COULD = "kubernetes-ksyun"
final K8S_NAMESPACE = "jenkins-tidb"
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final ENV_GOPATH = "/home/jenkins/agent/workspace/go"
final ENV_GOCACHE = "${ENV_GOPATH}/.cache/go-build"
final POD_TEMPLATE = """
apiVersion: v1
kind: Pod
spec:
  containers:
    - name: golang
      image: "hub.pingcap.net/wangweizhen/tidb_image:go11920220829"
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
      volumeMounts:
        - mountPath: /home/jenkins/.tidb
          name: bazel-out
        - mountPath: /data/
          name: bazel
          readOnly: true
  volumes:
    - name: bazel-out
      emptyDir: {}
    - name: bazel
      secret:
        secretName: bazel
        optional: true
"""

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
        timeout(time: 30, unit: 'MINUTES')
        parallelsAlwaysFailFast()
    }
    stages {
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
                    cache(path: "${ENV_GOPATH}/pkg/mod", key: "gomodcache/rev-${ghprbActualCommit}", restoreKeys: ['gomodcache/rev-']) {
                        cache(path: "./", filter: "bin/*", key: "binary/pingcap/tidb/tidb-server/rev-${ghprbActualCommit}") {
                            sh label: 'tidb-server', script: 'ls bin/explain_test_tidb-server || go build -o bin/explain_test_tidb-server github.com/pingcap/tidb/tidb-server'
                        }
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
                    cache(path: "./", key: "ws/pingcap/tidb/check2/rev-${ghprbActualCommit}") {
                        sh  "touch rev-${ghprbActualCommit}"
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
                        yaml POD_TEMPLATE
                    }
                }
                stages {                    
                    stage('Test')  {
                        options { timeout(time: 30, unit: 'MINUTES') }
                        steps {
                            sh 'chmod +x scripts/pingcap/tidb/*.sh'
                            dir('tidb') {
                                cache(path: "./", key: "ws/pingcap/tidb/check2/rev-${ghprbActualCommit}") {
                                    sh "ls -l rev-${ghprbActualCommit}"
                                }
                                cache(path: "${ENV_GOPATH}/pkg/mod", key: "gomodcache/rev-${ghprbActualCommit}") {
                                    sh "${WORKSPACE}/scripts/pingcap/tidb/${SCRIPT_AND_ARGS}"
                                }
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