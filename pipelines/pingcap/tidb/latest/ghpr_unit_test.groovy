// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and release-6.2.x branches
final K8S_COULD = "kubernetes-ng"
final K8S_NAMESPACE = "jenkins-tidb"
final K8S_LABEL = "tidb_ghpr_unit_test-${BUILD_NUMBER}"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_OPENAPI_CREDENTIALS_ID = 'sre-bot-token'
final GIT_TRUNK_BRANCH = "master"
final SLACK_TOKEN_CREDENTIAL_ID = 'slack-pingcap-token'
final POD_TEMPLATE = '''
apiVersion: v1
kind: Pod
spec:
  containers:
    - name: golang
      image: "hub.pingcap.net/wangweizhen/tidb_image:20220718"
      tty: true
      resources:
        requests:
          memory: 16Gi
          cpu: 4000m
      command: [/bin/sh, -c]
      args: [cat]
      env:
        - name: GOPATH
          value: /go
      volumeMounts:
        - name: jenkins-home
          mountPath: /home/jenkins
    - name: ruby
      image: "hub.pingcap.net/jenkins/centos7_ruby-2.6.3:latest"
      tty: true
      resources:
        requests:
          cpu: 100m
          memory: 256Mi
        limits:
          cpu: 200m
          memory: 1Gi
      command: [/bin/sh, -c]
      args: [cat]
      env:
        - name: GOPATH
          value: /go
'''

// TODO(flarezuo): cache git code with https://plugins.jenkins.io/jobcacher/ and S3 service.
pipeline {
    agent {
        kubernetes {
            label K8S_LABEL
            cloud K8S_COULD
            namespace K8S_NAMESPACE
            defaultContainer "golang"
            yaml POD_TEMPLATE
        }
    }
    options {
        timeout(time: 60, unit: 'MINUTES')
    }
    stages {
        stage('Checkout') {
            steps {
                retry(2) {
                    script {
                        def specStr = "+refs/heads/*:refs/remotes/origin/*"
                        if (ghprbPullId != null && ghprbPullId != "") {
                            specStr = "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*"
                        }

                        def ret = checkout(
                            changelog: false,
                            poll: false, 
                            scm: [
                                $class: 'GitSCM', branches: [[name: ghprbActualCommit]], 
                                doGenerateSubmoduleConfigurations: false, 
                                extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'], [$class: 'CloneOption', timeout: 2]], 
                                submoduleCfg: [], 
                                userRemoteConfigs: [[credentialsId: GIT_CREDENTIALS_ID, refspec: specStr, url: 'git@github.com:pingcap/tidb.git']],
                            ]
                        )

                        // if checkout failed, fallback to fetch all the PR data
                        if (ret) {
                            echo "checkout failed, retry.."
                            sleep 5                        
                        }
                    }                              
                }
            }
        }

        stage("Test") {
            steps {
                sh './build/jenkins_unit_test.sh'
            }           
            post {
                unsuccessful {
                    archiveArtifacts(artifacts: '**/core.*', allowEmptyArchive: true)
                    archiveArtifacts(artifacts: '**/*.test.bin', allowEmptyArchive: true)
                }
                always {
                    junit(testResults: "**/bazel.xml", allowEmptyResults: true)
                    script {
                        def id = UUID.randomUUID().toString()
                        def filepath = "tipipeline/test/report/${JOB_NAME}/${BUILD_NUMBER}/${id}/report.xml"
                        retry(3) {
                            sh """
                            curl -F ${filepath}=@test_coverage/bazel.xml ${FILE_SERVER_URL}/upload
                            echo "coverage download link: ${FILE_SERVER_URL}/download/${filepath}"
                            """
                        }
                    }
                }
                
            }
        }
        stage("Upload coverage report and notify on github") {
            when{ expression { return (ghprbPullId != null && ghprbPullId != "") } }
            options {
                timeout(time: 1, unit: 'MINUTES')
            }
            environment { 
                GITHUB_TOKEN = credentials(GIT_OPENAPI_CREDENTIALS_ID)
            }
            steps {
                         withCredentials([string(credentialsId: 'codecov-token-tidb', variable: 'CODECOV_TOKEN')]) {
                        timeout(5) {
                            if (ghprbPullId != null && ghprbPullId != "") {
                                if (user_bazel(ghprbTargetBranch)) { 
                                    sh """
                                    codecov -f "./coverage.dat" -t ${CODECOV_TOKEN} -C ${ghprbActualCommit} -P ${ghprbPullId} -b ${BUILD_NUMBER}
                                    """
                                } else {
                                    sh """
                                    curl -LO ${FILE_SERVER_URL}/download/cicd/ci-tools/codecov
                                    chmod +x codecov
                                    ./codecov -f "dumpling.coverage" -f "br.coverage" -f "tidb.coverage"  -t ${CODECOV_TOKEN} -C ${ghprbActualCommit} -P ${ghprbPullId} -b ${BUILD_NUMBER}
                                    """
                                }
                            } else {
                                if (user_bazel(ghprbTargetBranch)) { 
                                    sh """
                                    codecov -f "./coverage.dat" -t ${CODECOV_TOKEN} -C ${ghprbActualCommit} -b ${BUILD_NUMBER} -B ${ghprbTargetBranch}
                                    """
                                } else {
                                    sh """
                                    curl -LO ${FILE_SERVER_URL}/download/cicd/ci-tools/codecov
                                    chmod +x codecov
                                    ./codecov -f "dumpling.coverage" -f "br.coverage" -f "tidb.coverage" -t ${CODECOV_TOKEN} -C ${ghprbActualCommit} -b ${BUILD_NUMBER} -B ${ghprbTargetBranch}
                                    """
                                }     
                            }
                        }
                    }

                container(name: 'ruby') {
                    sh """#!/bin/bash
                    ruby --version
                    gem --version
                    wget ${FILE_SERVER_URL}/download/cicd/scripts/comment-on-pr.rb
                    ruby comment-on-pr.rb "pingcap/tidb" "${ghprbPullId}"  "Code Coverage Details: https://codecov.io/github/pingcap/tidb/commit/${ghprbActualCommit}" true "Code Coverage Details:"
                    """
                }
            }
        }
    }
}






