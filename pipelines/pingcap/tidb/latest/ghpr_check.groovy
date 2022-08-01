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
      image: "hub.pingcap.net/jenkins/centos7_golang-1.18:latest"
      tty: true
      resources:
        requests:
          memory: 12Gi # 8
          cpu: 6000m # 4

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
        stage("Checks") {
            parallel {
                stage('check') {
                    steps { sh 'make check' }
                }
                stage("checklist") {
                    steps{ sh 'make checklist' }
                }
                stage('explaintest') {
                    steps{ sh 'make explaintest' }                        
                }
                stage("test_part_parser") {
                    steps { sh 'make test_part_parser' }                        
                }
                stage("gogenerate") {
                    steps { sh 'make gogenerate' }
                }
            }
        }
    }
}