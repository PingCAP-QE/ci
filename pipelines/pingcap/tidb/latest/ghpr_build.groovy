// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// 
// Pod will mount a empty dir volume to all containers at `/home/jenkins/agent`, but 
// user(`jenkins(id:1000)`) only can create dir under `/home/jenkins/agent/workspace`
//
final K8S_COULD = "kubernetes-ksyun"
final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final ENV_DENO_DIR = "/home/jenkins/agent/workspace/.deno" // cache deno deps.
final ENV_GOPATH = "/home/jenkins/agent/workspace/.go"
final ENV_GOCACHE = "/home/jenkins/agent/workspace/.cache/go-build"
final CACHE_SECRET = 'ci-pipeline-cache' // read access-id, access-secret
final CACHE_CM = 'ci-pipeline-cache' // read endpoint, bucket name ...
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
          cpu: 2
        limits:
          memory: 16Gi
          cpu: 4
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
    - name: deno
      image: "denoland/deno:1.25.1"
      tty: true
      command: [sh]
      env:
        - name: DENO_DIR
          value: ${ENV_DENO_DIR}
      envFrom:
        - secretRef:
            name: ${CACHE_SECRET}
        - configMapRef:
            name: ${CACHE_CM}
      resources:
        requires:
          memory: "128Mi"
          cpu: "100m"
        limits:
          memory: "2Gi"
          cpu: "500m"
      securityContext:
        runAsUser: 1000
        runAsGroup: 1000
    - name: net-tool
      image: wbitt/network-multitool
      tty: true
      resources:
        limits:
          memory: "128Mi"
          cpu: "500m"
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
    }
    stages {
        stage('Debug info') {
            // options { }  Valid option types: [cache, catchError, checkoutToSubdirectory, podTemplate, retry, script, skipDefaultCheckout, timeout, waitUntil, warnError, withChecks, withContext, withCredentials, withEnv, wrap, ws]
            steps {
                sh label: 'Debug info', script: """
                    printenv
                    echo "-------------------------"
                    go env
                    echo "-------------------------"
                    echo "debug command: kubectl -n ${K8S_NAMESPACE} exec -ti ${NODE_NAME} bash"
                """
                container(name: 'net-tool') {
                    sh 'dig github.com'
                }
            }
        }
        stage('Checkout') {
            environment {
                CACHE_KEEP_COUNT = '10'
            }
            // FIXME(wuhuizuo): catch AbortException and set the job abort status
            // REF: https://github.com/jenkinsci/git-plugin/blob/master/src/main/java/hudson/plugins/git/GitSCM.java#L1161
            parallel {   
                stage("tidb") {
                    steps {
                        // restore git repo from cached items.
                        container('deno') {sh label: 'restore cache', script: '''deno run --allow-all scripts/plugins/s3-cache.ts \
                            --op restore \
                            --path tidb \
                            --key "git/pingcap/tidb/rev-${ghprbActualCommit}" \
                            --key-prefix 'git/pingcap/tidb/rev-'
                        '''}

                        dir("tidb") {
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

                        // cache it if it's new
                        container('deno') {sh label: 'cache it', script: '''deno run --allow-all scripts/plugins/s3-cache.ts \
                            --op backup \
                            --path tidb \
                            --key "git/pingcap/tidb/rev-${ghprbActualCommit}" \
                            --key-prefix 'git/pingcap/tidb/rev-' \
                            --keep-count ${CACHE_KEEP_COUNT}
                        '''}
                    }
                }
                stage("enterprise-plugin") {
                    steps {
                        container('deno') {
                            sh label: 'restore cache', script: '''deno run --allow-all scripts/plugins/s3-cache.ts \
                                --op restore \
                                --path "enterprise-plugin" \
                                --key "git/pingcap/enterprise-plugin/rev-${ghprbActualCommit}" \
                                --key-prefix 'git/pingcap/enterprise-plugin/rev-'
                            '''
                        }
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

                            dir("enterprise-plugin") {
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

                        // cache it if it's new
                        container('deno') {sh label: 'cache it', script: '''deno run --allow-all scripts/plugins/s3-cache.ts \
                            --op backup \
                            --path "enterprise-plugin" \
                            --key "git/pingcap/enterprise-plugin/rev-${ghprbActualCommit}" \
                            --key-prefix 'git/pingcap/enterprise-plugin/rev-' \
                            --keep-count ${CACHE_KEEP_COUNT}
                        '''}
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