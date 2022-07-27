// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and release-6.2.x branches
final K8S_COULD = "kubernetes-ng"
final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
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
        memory: 8Gi
        cpu: 2
      command: [/bin/sh, -c]
      args: [cat]
      env:
        - name: GOPATH
          value: /go
      volumeMounts:
        - name: tmp
          mountPath: /tmp
        - name: cached-code
          mountPath: /home/jenkins/agent/ci-cached-code-daily
  volumes:
    - name: tmp
      emptyDir: {}      
    - name: cached-code
      nfs:
        server: "172.16.5.22"
        path: /mnt/ci.pingcap.net-nfs/git
        readOnly: false
'''

// TODO(flarezuo): cache git code with https://plugins.jenkins.io/jobcacher/ and S3 service.
pipeline {
    agent {
        kubernetes {
            label "tidb-ghpr-build-${BUILD_NUMBER}"
            cloud K8S_COULD
            namespace K8S_NAMESPACE
            defaultContainer "golang"
            yaml POD_TEMPLATE
        }
    }
    stages {
        stage("debug info") {
            steps {
                sh "printenv"
                println "debug command: kubectl -n ${K8S_NAMESPACE} exec -ti ${NODE_NAME} bash"
            }
        }        
        stage("Checkout") {
            steps {         
                dir("go/src/github.com/pingcap/tidb") {                         
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
        }
        stage("Build tidb-server and plugin"){
            failFast true
            parallel {            
                stage("Build and upload TiDB") {
                    stages {
                        stage("Build"){
                            options {
                                timeout(time: 10, unit: 'MINUTES')
                            }
                            steps {
                                dir("go/src/github.com/pingcap/tidb") {
                                    sh "make bazel_build"
                                }
                            }
                        }
                        stage("Upload") {
                            steps {                                        
                                dir("go/src/github.com/pingcap/tidb") {
                                    sh(label: "create tidb-server tarball", script: """
                                        rm -rf .git
                                        tar czvf tidb-server.tar.gz ./*
                                        echo "pr/${ghprbActualCommit}" > sha1
                                        echo "done" > done
                                    """)

                                    // upload to tidb dir
                                    timeout(10) {
                                        script {
                                            def filepath = "builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/tidb-server.tar.gz"
                                            def donepath = "builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/done"
                                            def refspath = "refs/pingcap/tidb/pr/${ghprbPullId}/sha1"
                                            // if (params.containsKey("triggered_by_upstream_ci")) {
                                            //     refspath = "refs/pingcap/tidb/pr/branch-${ghprbTargetBranch}/sha1"
                                            // }

                                            sh(label: 'upload to tidb dir', script: """
                                                curl -F ${filepath}=@tidb-server.tar.gz ${FILE_SERVER_URL}/upload
                                                curl -F ${donepath}=@done ${FILE_SERVER_URL}/upload
                                                curl -F ${refspath}=@sha1 ${FILE_SERVER_URL}/upload
                                            """)
                                        }                                
                                    }
                                
                                    // upload to tidb-checker dir
                                    timeout(10) {
                                        script {
                                            def filepath = "builds/pingcap/tidb-check/pr/${ghprbActualCommit}/centos7/tidb-server.tar.gz"
                                            def donepath = "builds/pingcap/tidb-check/pr/${ghprbActualCommit}/centos7/done"
                                            sh(label: 'upload to tidb-checker dir', script: """
                                                curl -F ${filepath}=@tidb-server.tar.gz ${FILE_SERVER_URL}/upload
                                                curl -F ${donepath}=@done ${FILE_SERVER_URL}/upload                                    
                                            """)
                                        }
                                    } 
                                }                               
                            }
                        }
                    }
                }
                stage ("Build plugins") {
                    steps {
                        // todo: may be no need to copy
                        //     timeout(time: 5, unit: 'MINUTES') {
                        //         sh """
                        //         mkdir -p ${env.WORKSPACE}/go/src/github.com/pingcap/tidb-build-plugin/
                        //         cp -R ./* ${env.WORKSPACE}/go/src/github.com/pingcap/tidb-build-plugin/
                        //         """
                        //     }
                        dir("go/src/github.com/pingcap/enterprise-plugin") {
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
                                    pluginBranch = (ghprbTargetBranch =~ releaseOrHotfixBranchReg)[0][2]
                                }  

                                def pluginSpec = "+refs/heads/*:refs/remotes/origin/*"
                                // transfer plugin branch from pr/28 to origin/pr/28/head
                                if (pluginBranch.startsWith("pr/")) {
                                    pluginSpec = "+refs/pull/*:refs/remotes/origin/pr/*"
                                    pluginBranch = "origin/${pluginBranch}/head"
                                }

                                checkout(
                                    changelog: false, 
                                    poll: true, 
                                    scm: [
                                        $class: 'GitSCM', 
                                        branches: [[name: "${pluginBranch}"]], 
                                        doGenerateSubmoduleConfigurations: false, 
                                        extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'], [$class: 'CloneOption', timeout: 2]], 
                                        submoduleCfg: [], 
                                        userRemoteConfigs: [[credentialsId: GIT_CREDENTIALS_ID, refspec: pluginSpec, url: 'git@github.com:pingcap/enterprise-plugin.git']]
                                    ]
                                )
                            }
                        }
                        // dir("go/src/github.com/pingcap/tidb-build-plugin") {
                        dir("go/src/github.com/pingcap/tidb") {
                            timeout(time: 20, unit: 'MINUTES') {
                                sh(label: 'build pluginpkg tool', script: """
                                    cd cmd/pluginpkg
                                    go build
                                """)
                            }
                        }
                        dir("go/src/github.com/pingcap/enterprise-plugin/whitelist") {
                            sh(label: 'build plugin whitelist', script: """
                                GO111MODULE=on go mod tidy
                                # ${env.WORKSPACE}/go/src/github.com/pingcap/tidb-build-plugin/cmd/pluginpkg/pluginpkg -pkg-dir . -out-dir .
                                ${env.WORKSPACE}/go/src/github.com/pingcap/tidb/cmd/pluginpkg/pluginpkg -pkg-dir . -out-dir .
                            """)
                        }
                        dir("go/src/github.com/pingcap/enterprise-plugin/audit") {
                            sh(label: 'build plugin: audit', script: """
                                GO111MODULE=on go mod tidy
                                # ${env.WORKSPACE}/go/src/github.com/pingcap/tidb-build-plugin/cmd/pluginpkg/pluginpkg -pkg-dir . -out-dir .
                                ${env.WORKSPACE}/go/src/github.com/pingcap/tidb/cmd/pluginpkg/pluginpkg -pkg-dir . -out-dir .
                            """)
                        }
                    }
                }
            }
        }
    }

    // TODO: statics and report logic should not put in pipelines.
    // Instead should only send a cloud event to a external service.
    post {       
        always {
            dir("go/src/github.com/pingcap/tidb") {
                archiveArtifacts(
                    artifacts: 'importer.log,tidb-server-check.log',
                    allowEmptyArchive: true,
                )
            }
            script {
                echo "Send slack here ..."
                def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
                def slackmsg = """  [#${ghprbPullId}: ${ghprbPullTitle}]
                                    ${ghprbPullLink}
                                    ${ghprbPullDescription}
                                    Build Result: `${currentBuild.result}`
                                    Elapsed Time: `${duration} mins`
                                    ${env.RUN_DISPLAY_URL}
                    """.stripIndent()

                if (currentBuild.result != "SUCCESS") {
                    slackSend(
                        channel: '#jenkins-ci', 
                        color: 'danger', // red color
                        teamDomain: 'pingcap', 
                        tokenCredentialId: SLACK_TOKEN_CREDENTIAL_ID, 
                        message: slackmsg
                    )
                } else if (duration >= 3 && ghprbTargetBranch == GIT_TRUNK_BRANCH) {
                    slackSend(
                        channel: '#jenkins-ci-3-minutes', 
                        color: 'warning', // yellow color
                        teamDomain: 'pingcap', 
                        tokenCredentialId: SLACK_TOKEN_CREDENTIAL_ID, 
                        message: slackmsg
                    )
                }
            }
        }       
    }
}
