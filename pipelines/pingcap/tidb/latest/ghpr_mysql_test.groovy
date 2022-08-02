// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and release-6.2.x branches
final K8S_COULD = "kubernetes-ng"
final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final GIT_TRUNK_BRANCH = "master"
final POD_TEMPLATE = '''
apiVersion: v1
kind: Pod
spec:
  containers:
    - name: golang
      image: "hub.pingcap.net/jenkins/centos7_golang-1.18:latest"
      tty: true
      resources:
        requests:
          memory: 4Gi
          cpu: 2
      command: [/bin/sh, -c]
      args: [cat]
      env:
        - name: GOPATH
          value: /go
      volumeMounts:
        - name: tmp
          mountPath: /tmp
  volumes:
    - name: tmp
      emptyDir: {}
'''


// TODO(wuhuizuo): cache git code with https://plugins.jenkins.io/jobcacher/ and S3 service.
// TODO(wuhuizuo): tidb-test should delivered by docker image.
pipeline {
    agent {
        kubernetes {
            cloud K8S_COULD
            namespace K8S_NAMESPACE
            defaultContainer 'golang'
            yaml POD_TEMPLATE
        }
    }
    options {
        timeout(time: 15, unit: 'MINUTES')
    }
    stages {
        stage('Prepare') {
            options { timeout(time: 10, unit: 'MINUTES') }
            steps {
                dir("tidb") {
                    script {
                        retry(3){
                            sh label: 'get tidb-server binnary', script: """
                                tidb_done_url="${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/done"
                                tidb_url="${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/tidb-server.tar.gz"
                                while ! curl --output /dev/null --silent --head --fail \${tidb_done_url}; do sleep 1; done
                                curl \${tidb_url} | tar xz
                                """
                        }
                    }
                }
                dir("tidb-test") {
                    script {
                        def releaseOrHotfixBranchReg = /^(release\-)?(\d+\.\d+)(\.\d+\-.+)?/

                        def pluginBranch = ghprbTargetBranch
                        if (ghprbTargetBranch =~ releaseOrHotfixBranchReg) {
                            pluginBranch = (ghprbTargetBranch =~ releaseOrHotfixBranchReg)[0][2]
                        }  
                        sh label: 'download tidb-test and build mysql_test', script: """
                            TIDB_TEST_BRANCH="${ghprbTargetBranch}"                            
                            tidb_test_refs="${FILE_SERVER_URL}/download/refs/pingcap/tidb-test/\${TIDB_TEST_BRANCH}/sha1"
                            while ! curl --output /dev/null --silent --head --fail \${tidb_test_refs}; do sleep 5; done
                            tidb_test_sha1="$(curl '\${tidb_test_refs}')"

                            tidb_test_url="${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/\${tidb_test_sha1}/centos7/tidb-test.tar.gz"
                            while ! curl --output /dev/null --silent --head --fail \${tidb_test_url}; do sleep 5; done
                            curl \${tidb_test_url} | tar xz

                            cd mysql_test
                            TIDB_SRC_PATH=../tidb ./build.sh
                            """
                    }
                }
                // TODO(wuhuizuo): store files:
                // - tidb/bin/tidb-server
                // - tidb-test/mysql-test
        }
        stage('MySQL Tests') {
            failFast true
            matrix {
                axis {
                    name 'PART'
                    values '1', '2', '3', '4'
                }
                stages {
                    stage("Test") {
                        options { timeout(time: 25, unit: 'MINUTES') }
                        steps {
                            dir("tidb-test/mysql_test") {
                                sh """#! /usr/bin/env bash

                                pwd && ls -alh
                                exit_code=0
                                { # try block
                                    TIDB_SERVER_PATH=${WORKSPACE}/tidb/bin/tidb-server ./test.sh -backlist=1 -part=${PART}
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
                                """
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