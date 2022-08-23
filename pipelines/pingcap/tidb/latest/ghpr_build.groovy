// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
final K8S_COULD = "kubernetes-ksyun"
final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final ENV_GOPATH = "/home/jenkins/agent/workspace/go"
final ENV_GOCACHE = "${ENV_GOPATH}/.cache/go-build"
final POD_TEMPLATE = """
apiVersion: v1
kind: Pod
spec:
  containers:
    - name: golang
      image: "hub.pingcap.net/wangweizhen/tidb_image:20220823"
      tty: true
      resources:
        requests:
          memory: 8Gi
          cpu: 2
      command: [/bin/sh, -c]
      args: [cat]
      env:
        - name: GOPATH
          value: ${ENV_GOPATH}
        - name: GOCACHE
          value: ${ENV_GOCACHE}
      volumeMounts:
        - mountPath: /data/
          name: bazel
          readOnly: true
  volumes:
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
    }
    stages {
        stage('debug info') {
            steps {
                sh label: 'Debug info', script: """
                printenv
                echo "-------------------------"
                go env
                echo "-------------------------"
                echo "debug command: kubectl -n ${K8S_NAMESPACE} exec -ti ${NODE_NAME} bash"
                """
            }
        }
        stage('Checkout') {
            // FIXME(wuhuizuo): catch AbortException and set the job abort status
            // REF: https://github.com/jenkinsci/git-plugin/blob/master/src/main/java/hudson/plugins/git/GitSCM.java#L1161
            parallel {   
                stage("tidb") {
                    steps {
                        dir("tidb") {
                            // using plugin: https://github.com/j3t/jenkins-pipeline-cache-plugin
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
                                                [$class: 'CloneOption', timeout: 15],
                                            ],
                                            submoduleCfg: [],
                                            userRemoteConfigs: [[
                                                refspec: "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*", 
                                                url: "https://github.com/${GIT_FULL_REPO_NAME}.git",
                                            ]],
                                        ]
                                    )
                                }
                            }
                        }
                    }
                }
                stage("enterprise-plugin") {
                    steps {
                        dir("enterprise-plugin") {
                            script {
                                // examples: 
                                //  - release-6.2
                                //  - release-6.2-20220801
                                //  - 6.2.0-pitr-dev
                                def releaseOrHotfixBranchReg = /^(release\-)?(\d+\.\d+)(\.\d+\-.+)?/
                                def commentBodyReg = /\bplugin\s*=\s*([^\s\\]+)(\s|\\|$)/

                                def pluginBranch = ghprbTargetBranch
                                if (ghprbCommentBody =~ ghprbTargetBranch) {
                                    pluginBranch = (ghprbCommentBody =~ ghprbTargetBranch)[0][1]
                                } else if (ghprbTargetBranch =~ releaseOrHotfixBranchReg) {
                                    pluginBranch = String.format('release-%s', (ghprbTargetBranch =~ releaseOrHotfixBranchReg)[0][2])
                                }  

                                def pluginSpec = "+refs/heads/*:refs/remotes/origin/*"
                                // transfer plugin branch from pr/28 to origin/pr/28/head
                                if (pluginBranch.startsWith("pr/")) {
                                    pluginSpec = "+refs/pull/*:refs/remotes/origin/pr/*"
                                    pluginBranch = "origin/${pluginBranch}/head"
                                }

                                cache(path: "./", filter: '**/*', key: "git/pingcap/enterprise-plugin/rev-${ghprbActualCommit}", restoreKeys: ['git/pingcap/enterprise-plugin/rev-']) {
                                    checkout(
                                        changelog: false,
                                        poll: true,
                                        scm: [
                                            $class: 'GitSCM',
                                            branches: [[name: pluginBranch]],
                                            doGenerateSubmoduleConfigurations: false, 
                                            extensions: [
                                                [$class: 'PruneStaleBranch'], 
                                                [$class: 'CleanBeforeCheckout'], 
                                                [$class: 'CloneOption', timeout: 2],
                                            ], 
                                            submoduleCfg: [],
                                            userRemoteConfigs: [[
                                                credentialsId: GIT_CREDENTIALS_ID, 
                                                refspec: pluginSpec,
                                                url: 'git@github.com:pingcap/enterprise-plugin.git',
                                            ]]
                                        ]
                                    )
                                }
                            }
                        }
                    }                    
                }  
            }
        }
        stage("Build tidb-server and plugin"){
            failFast true
            parallel {
                stage("Build tidb-server") {
                    stages {
                        stage("Build"){
                            options {
                                timeout(time: 10, unit: 'MINUTES')
                            }
                            steps {
                                dir("tidb") {                                     
                                    sh "make bazel_build"                           
                                }
                            }
                            post {       
                                // TODO: statics and report logic should not put in pipelines.
                                // Instead should only send a cloud event to a external service.
                                always {
                                    dir("tidb") {
                                        archiveArtifacts(
                                            artifacts: 'importer.log,tidb-server-check.log',
                                            allowEmptyArchive: true,
                                        )
                                    }            
                                }
                            }
                        }
                        stage("Upload") {
                            options {
                                timeout(time: 10, unit: 'MINUTES')
                            }
                            steps {
                                dir("tidb") {
                                    sh label: "create tidb-server tarball", script: """
                                        rm -rf .git
                                        tar czvf tidb-server.tar.gz ./*
                                        echo "pr/${ghprbActualCommit}" > sha1
                                        echo "done" > done
                                        """
                                    sh label: 'upload to tidb dir', script: """
                                        filepath="builds/${GIT_FULL_REPO_NAME}/pr/${ghprbActualCommit}/centos7/tidb-server.tar.gz"
                                        donepath="builds/${GIT_FULL_REPO_NAME}/pr/${ghprbActualCommit}/centos7/done"
                                        refspath="refs/${GIT_FULL_REPO_NAME}/pr/${ghprbPullId}/sha1"
                                        curl -F \${filepath}=@tidb-server.tar.gz \${FILE_SERVER_URL}/upload
                                        curl -F \${donepath}=@done \${FILE_SERVER_URL}/upload
                                        curl -F \${refspath}=@sha1 \${FILE_SERVER_URL}/upload
                                        """
                                    sh label: 'upload to tidb-checker dir', script: """
                                        filepath="builds/pingcap/tidb-check/pr/${ghprbActualCommit}/centos7/tidb-server.tar.gz"
                                        donepath="builds/pingcap/tidb-check/pr/${ghprbActualCommit}/centos7/done"
                                        curl -F \${filepath}=@tidb-server.tar.gz \${FILE_SERVER_URL}/upload
                                        curl -F \${donepath}=@done \${FILE_SERVER_URL}/upload                                    
                                        """
                                }
                            }
                        }
                    }
                }
                stage("Build plugins") {
                    steps {
                        timeout(time: 20, unit: 'MINUTES') {
                            sh label: 'build pluginpkg tool', script: '''
                                cd tidb/cmd/pluginpkg
                                go build
                                '''
                        }
                        dir('enterprise-plugin/whitelist') {
                            sh label: 'build plugin whitelist', script: '''
                                GO111MODULE=on go mod tidy
                                ../../tidb/cmd/pluginpkg/pluginpkg -pkg-dir . -out-dir .
                                '''
                        }
                        dir('enterprise-plugin/audit') {
                            sh label: 'build plugin: audit', script: '''
                                GO111MODULE=on go mod tidy
                                ../../tidb/cmd/pluginpkg/pluginpkg -pkg-dir . -out-dir .
                                '''
                        }
                    }
                }
            }
        }
    }
}