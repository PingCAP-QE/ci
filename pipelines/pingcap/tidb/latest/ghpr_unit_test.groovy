// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and release-6.2.x branches
final K8S_COULD = "kubernetes-ksyun"
final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_OPENAPI_CREDENTIALS_ID = 'sre-bot-token'
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final GIT_TRUNK_BRANCH = "master"
final CODECOV_TOKEN_CREDENTIAL_ID = 'codecov-token-tidb'
final POD_TEMPLATE = '''
apiVersion: v1
kind: Pod
spec:
  containers:
    - name: golang
      image: "hub.pingcap.net/wangweizhen/tidb_image:20220801"
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
                                credentialsId: GIT_CREDENTIALS_ID, 
                                refspec: "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*", 
                                url: "git@github.com:${GIT_FULL_REPO_NAME}.git"
                            ]],
                        ]
                    )
                }
            }
        }
        stage('Test') {            
            steps { 
                sh './build/jenkins_unit_test.sh' 
            }
            post {
                unsuccessful {
                    archiveArtifacts(artifacts: '**/core.*', allowEmptyArchive: true)
                    archiveArtifacts(artifacts: '**/*.test.bin', allowEmptyArchive: true)
                }
                always {
                    // archive test report to Jenkins.
                    junit(testResults: "**/bazel.xml", allowEmptyResults: true)

                    // upload coverage report to file server
                    script {
                        def id = UUID.randomUUID().toString()
                        def filepath = "tipipeline/test/report/${JOB_NAME}/${BUILD_NUMBER}/${id}/report.xml"
                        retry(3) {
                            sh label: "upload coverage report to ${FILE_SERVER_URL}", script: """
                            curl -F ${filepath}=@test_coverage/bazel.xml ${FILE_SERVER_URL}/upload
                            echo "coverage download link: ${FILE_SERVER_URL}/download/${filepath}"
                            """
                        }
                    }

                    // upload covrage to codecov.io and notify on github.
                    timeout(time: 1, unit: 'MINUTES') {
                        withCredentials([string(credentialsId: CODECOV_TOKEN_CREDENTIAL_ID, variable: 'CODECOV_TOKEN')]) {
                            sh "codecov -f ./coverage.dat -t ${CODECOV_TOKEN} -C ${ghprbActualCommit} -P ${ghprbPullId} -b ${BUILD_NUMBER}"
                        }
                    }

                    // TODO(wuhuizuo): replace with other jenkins plugin.
                    container(name: 'ruby') {
                        withCredentials([string(credentialsId: GIT_OPENAPI_CREDENTIALS_ID, variable: 'GITHUB_TOKEN')]) {
                            sh label: 'comment coverage report link on github PR', script: """#!/bin/bash
                            detail_url="https://codecov.io/github/${GIT_FULL_REPO_NAME}/commit/${ghprbActualCommit}"
                            wget ${FILE_SERVER_URL}/download/cicd/scripts/comment-on-pr.rb
                            ruby comment-on-pr.rb \
                                ${GIT_FULL_REPO_NAME} \
                                ${ghprbPullId} \
                                "Code Coverage Details: \$detail_url" true "Code Coverage Details:"
                            """
                        }
                    }
                }
            }
        }
    }
}