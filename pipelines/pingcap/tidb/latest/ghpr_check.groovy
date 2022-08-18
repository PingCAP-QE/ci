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
      image: "hub.pingcap.net/wangweizhen/tidb_image:20220816"
      tty: true
      resources:
        requests:
          memory: 12Gi # 8
          cpu: 6000m # 4
      command: [/bin/sh, -c]
      args: [cat]
      env:
      env:
        - name: GOPATH
          value: ${ENV_GOPATH}
        - name: GOCACHE
          value: ${ENV_GOCACHE}
      volumeMounts:
        - mountPath: /data/bazel
          name: name
          readOnly: true
  volumes:
    - name: bazel
      secret:
        name: bazel
        secretName: bazel             
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
        stage("Checks") {
            parallel {
                stage('check') {
                    steps { dir('tidb') { sh 'make check' } }
                }
                stage("checklist") {
                    steps{ dir('tidb') {sh 'make checklist' } }
                }
                stage('explaintest') {
                    steps{ dir('tidb') {sh 'make explaintest' } }
                }
                stage("test_part_parser") {
                    steps { dir('tidb') {sh 'make test_part_parser' } }
                }
                stage("gogenerate") {
                    steps { dir('tidb') {sh 'make gogenerate' } }
                }
            }
        }
    }
}