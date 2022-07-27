// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and release-6.2.x branches

final K8S_COULD = "kubernetes-ng"
final K8S_NAMESPACE = "jenkins-tidb"
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

// TODO(flarezuo): cache git code by k8s PVC

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
                sh "whoami && go version"            
                // update code
                dir("/home/jenkins/agent/code-archive") {
                    // delete to clean workspace in case of agent pod reused lead to conflict.
                    deleteDir()
                    // copy code from nfs cache
                    script {
                        if(fileExists("/home/jenkins/agent/ci-cached-code-daily/src-tidb.tar.gz")){
                            timeout(5) {                            
                                sh """
                                cp -R /home/jenkins/agent/ci-cached-code-daily/src-tidb.tar.gz*  ./
                                mkdir -p ${env.WORKSPACE}/go/src/github.com/pingcap/tidb
                                tar -xzf src-tidb.tar.gz -C ${env.WORKSPACE}/go/src/github.com/pingcap/tidb --strip-components=1
                                """
                            }
                        }
                    }
                    dir("${env.WORKSPACE}/go/src/github.com/pingcap/tidb") {
                        script {
                            if (sh(returnStatus: true, script: ' [ -f Makefile ] && [ -d .git ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                                echo "Not a valid git folder: ${env.WORKSPACE}/go/src/github.com/pingcap/tidb"
                                echo "Clean dir then get tidb src code from fileserver"
                                deleteDir()
                            }
                            if(!fileExists("Makefile")) {
                                sh """
                                rm -rf /home/jenkins/agent/code-archive/tidb.tar.gz
                                rm -rf /home/jenkins/agent/code-archive/tidb
                                wget -O /home/jenkins/agent/code-archive/tidb.tar.gz  ${FILE_SERVER_URL}/download/cicd/daily-cache-code/tidb.tar.gz -q --show-progress
                                tar -xzf /home/jenkins/agent/code-archive/tidb.tar.gz -C ./ --strip-components=1
                                """
                            }
                        }                       
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
                                        userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:pingcap/tidb.git']],
                                    ]
                                )

                                    // if checkout failed, fallback to fetch all the PR data
                                if (ret) {
                                    echo "checkout failed, retry.."
                                    sleep 5
                                    if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                                        deleteDir()
                                    }
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
                stage("Build and upload TiDB") {
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
                                // create tidb-server tarball
                                sh """
                                rm -rf .git
                                tar czvf tidb-server.tar.gz ./*
                                echo "pr/${ghprbActualCommit}" > sha1
                                echo "done" > done
                                """

                                // upload to tidb dir
                                timeout(10) {
                                    script {
                                        def filepath = "builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/tidb-server.tar.gz"
                                        def donepath = "builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/done"
                                        def refspath = "refs/pingcap/tidb/pr/${ghprbPullId}/sha1"
                                        // if (params.containsKey("triggered_by_upstream_ci")) {
                                        //     refspath = "refs/pingcap/tidb/pr/branch-${ghprbTargetBranch}/sha1"
                                        // }

                                        sh """
                                        curl -F ${filepath}=@tidb-server.tar.gz ${FILE_SERVER_URL}/upload
                                        curl -F ${donepath}=@done ${FILE_SERVER_URL}/upload
                                        curl -F ${refspath}=@sha1 ${FILE_SERVER_URL}/upload
                                        """
                                    }                                
                                }
                            
                                // upload to tidb-checker dir
                                timeout(10) {
                                    script {
                                        def filepath = "builds/pingcap/tidb-check/pr/${ghprbActualCommit}/centos7/tidb-server.tar.gz"
                                        def donepath = "builds/pingcap/tidb-check/pr/${ghprbActualCommit}/centos7/done"
                                        sh """
                                        curl -F ${filepath}=@tidb-server.tar.gz ${FILE_SERVER_URL}/upload
                                        curl -F ${donepath}=@done ${FILE_SERVER_URL}/upload                                    
                                        """
                                    }
                                }                                
                            }
                        }
                    }
                }
                stage ("Build plugins") {
                    steps {
                        // todo: may be no need to copy
                        // dir("${env.WORKSPACE}/go/src/github.com/pingcap/tidb") {
                        //     timeout(time: 5, unit: 'MINUTES') {
                        //         sh """
                        //         mkdir -p ${env.WORKSPACE}/go/src/github.com/pingcap/tidb-build-plugin/
                        //         cp -R ./* ${env.WORKSPACE}/go/src/github.com/pingcap/tidb-build-plugin/
                        //         """
                        //     }
                        // }
                        dir("go/src/github.com/pingcap/enterprise-plugin") {
                            script {
                                def PLUGIN_BRANCH = ghprbTargetBranch

                                // example hotfix branch  release-4.0-20210724 | example release-5.1-hotfix-tiflash-patch1
                                // remove suffix "-20210724", only use "release-4.0"
                                if (PLUGIN_BRANCH.startsWith("release-") && PLUGIN_BRANCH.split("-").size() >= 3 ) {
                                    def k = PLUGIN_BRANCH.indexOf("-", PLUGIN_BRANCH.indexOf("-") + 1)
                                    PLUGIN_BRANCH = PLUGIN_BRANCH.substring(0, k)
                                    println "tidb hotfix branch: ${ghprbTargetBranch}"
                                    println "plugin branch use ${PLUGIN_BRANCH}"
                                }
                                if (ghprbTargetBranch == "6.1.0-pitr-dev") {
                                    PLUGIN_BRANCH = "release-6.1"
                                }

                                // parse enterprise-plugin branch
                                def m1 = ghprbCommentBody =~ /plugin\s*=\s*([^\s\\]+)(\s|\\|$)/
                                if (m1) {
                                    PLUGIN_BRANCH = "${m1[0][1]}"
                                }
                                pluginSpec = "+refs/heads/*:refs/remotes/origin/*"
                                // transfer plugin branch from pr/28 to origin/pr/28/head
                                if (PLUGIN_BRANCH.startsWith("pr/")) {
                                    pluginSpec = "+refs/pull/*:refs/remotes/origin/pr/*"
                                    PLUGIN_BRANCH = "origin/${PLUGIN_BRANCH}/head"
                                }

                                m1 = null
                                println "ENTERPRISE_PLUGIN_BRANCH=${PLUGIN_BRANCH}"                          

                                def isBuildCheck = ghprbCommentBody && ghprbCommentBody.contains("/run-all-tests")
                                echo isBuildCheck
                            }

                            checkout(
                                changelog: false, 
                                poll: true, 
                                scm: [
                                    $class: 'GitSCM', 
                                    branches: [[name: "${PLUGIN_BRANCH}"]], 
                                    doGenerateSubmoduleConfigurations: false, 
                                    extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'], [$class: 'CloneOption', timeout: 2]], 
                                    submoduleCfg: [], 
                                    userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: pluginSpec, url: 'git@github.com:pingcap/enterprise-plugin.git']]
                                ]
                            )
                        }
                        // dir("go/src/github.com/pingcap/tidb-build-plugin") {
                        dir("go/src/github.com/pingcap/tidb") {
                            timeout(time: 20, unit: 'MINUTES') {
                                sh """
                                cd cmd/pluginpkg
                                go build
                                """
                            }
                        }                        
                        dir("go/src/github.com/pingcap/enterprise-plugin/whitelist") {
                            sh """
                            GO111MODULE=on go mod tidy
                            # ${env.WORKSPACE}/go/src/github.com/pingcap/tidb-build-plugin/cmd/pluginpkg/pluginpkg -pkg-dir . -out-dir .
                            ${env.WORKSPACE}/go/src/github.com/pingcap/tidb/cmd/pluginpkg/pluginpkg -pkg-dir . -out-dir .
                            """
                        }
                        dir("go/src/github.com/pingcap/enterprise-plugin/audit") {
                            sh """
                            GO111MODULE=on go mod tidy
                            # ${env.WORKSPACE}/go/src/github.com/pingcap/tidb-build-plugin/cmd/pluginpkg/pluginpkg -pkg-dir . -out-dir .
                            ${env.WORKSPACE}/go/src/github.com/pingcap/tidb/cmd/pluginpkg/pluginpkg -pkg-dir . -out-dir .
                            """
                        }
                    }
                }
            }
        }
    }
    post {
        success {
            echo "passed ........"
        }
        failure {
            echo "failed~~~~~"
        }
        always {
            dir("go/src/github.com/pingcap/tidb") {
                archiveArtifacts(artifacts: 'importer.log,tidb-server-check.log')
            }
            script {
                echo "Send slack here ..."
                def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
                def slackmsg = "[#${ghprbPullId}: ${ghprbPullTitle}]" + "\n" +
                        "${ghprbPullLink}" + "\n" +
                        "${ghprbPullDescription}" + "\n" +
                        "Build Result: `${currentBuild.result}`" + "\n" +
                        "Elapsed Time: `${duration} mins` " + "\n" +
                        "${env.RUN_DISPLAY_URL}"

                if (duration >= 3 && ghprbTargetBranch == "master" && currentBuild.result == "SUCCESS") {
                    slackSend channel: '#jenkins-ci-3-minutes', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
                }

                if (currentBuild.result != "SUCCESS") {
                    slackSend channel: '#jenkins-ci', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
                }
            }
        }
    }
}


