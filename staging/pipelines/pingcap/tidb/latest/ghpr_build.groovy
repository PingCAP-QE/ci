// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final POD_TEMPLATE_FILE = 'staging/pipelines/pingcap/tidb/latest/pod-ghpr_build.yaml'
final debugYaml = '''
apiVersion: v1
kind: Pod
spec:
  securityContext:
    fsGroup: 1000
  containers:
    - name: golang
      image: "hub.pingcap.net/wangweizhen/tidb_image:go11920221101"
      securityContext:
        privileged: true
      tty: true
      resources:
        requests:
          memory: 8Gi
          cpu: "4"
        limits:
          memory: 24Gi
          cpu: "8"
      env:
        - name: GOPATH
          value: /share/.go
        - name: GOCACHE
          value: /share/.cache/go-build
      volumeMounts:
        - mountPath: /home/jenkins/.tidb/tmp
          name: bazel-out-merged
        - name: bazel-out-lower
          subPath: tidb/go1.19.2
          mountPath: /bazel-out-lower
        - mountPath: /bazel-out-overlay
          name: bazel-out-overlay
        - name: bazel-repository-cache
          mountPath: /share/.cache/bazel-repository-cache
        - name: gocache
          mountPath: /share/.cache/go-build
        - name: gopathcache
          mountPath: /share/.go
        - name: bazel-rc
          mountPath: /data/
          readOnly: true
        - name: containerinfo
          mountPath: /etc/containerinfo
      lifecycle:
        postStart:
          exec:
            command:
              - /bin/sh
              - /data/bazel-prepare-in-container.sh
    - name: net-tool
      image: wbitt/network-multitool
      tty: true
      resources:
        limits:
          memory: 128Mi
          cpu: 100m
    - name: report
      image: hub.pingcap.net/jenkins/python3-requests:latest
      tty: true
      resources:
        limits:
          memory: 256Mi
          cpu: 100m
  volumes:
    - name: gopathcache
      persistentVolumeClaim:
        claimName: gopathcache
    - name: gocache
      persistentVolumeClaim:
        claimName: gocache
    - name: bazel-out-lower
      persistentVolumeClaim:
        claimName: bazel-out-data
    - name: bazel-repository-cache
      persistentVolumeClaim:
        claimName: bazel-repository-cache
    - name: bazel-out-overlay
      emptyDir: {}
    - name: bazel-out-merged
      emptyDir: {}
    - name: bazel-rc
      secret:
        secretName: bazel
    - name: containerinfo
      downwardAPI:
        items:
          - path: cpu_limit
            resourceFieldRef:
              containerName: golang
              resource: limits.cpu
          - path: cpu_request
            resourceFieldRef:
              containerName: golang
              resource: requests.cpu
          - path: mem_limit
            resourceFieldRef:
              containerName: golang
              resource: limits.memory
          - path: mem_request
            resourceFieldRef:
              containerName: golang
              resource: requests.memory
'''
pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            // yamlFile POD_TEMPLATE_FILE
            yaml debugYaml
            defaultContainer 'golang'
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
        stage('Debug info') {
            // options { }  Valid option types: [cache, catchError, checkoutToSubdirectory, podTemplate, retry, script, skipDefaultCheckout, timeout, waitUntil, warnError, withChecks, withContext, withCredentials, withEnv, wrap, ws]
            steps {
                sh label: 'Debug info', script: """
                    printenv
                    echo "-------------------------"
                    go env
                    echo "-------------------------"
                    ls -l /dev/null
                    echo "debug command: kubectl -n ${K8S_NAMESPACE} exec -ti ${NODE_NAME} bash"
                """
                container(name: 'net-tool') {
                    sh 'dig github.com'
                }
            }
        }
        stage('Checkout') {
            // FIXME(wuhuizuo): catch AbortException and set the job abort status
            // REF: https://github.com/jenkinsci/git-plugin/blob/master/src/main/java/hudson/plugins/git/GitSCM.java#L1161
            parallel {   
                stage('tidb') {
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
                            cache(path: "./", filter: '**/*', key: "git/pingcap/enterprise-plugin/rev-${ghprbActualCommit}", restoreKeys: ['git/pingcap/enterprise-plugin/rev-']) {
                                retry(2) {
                                    script {
                                        component.checkout('git@github.com:pingcap/enterprise-plugin.git', 'plugin', ghprbTargetBranch, ghprbCommentBody, GIT_CREDENTIALS_ID)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        stage("Build tidb-server and plugin"){
            parallel {
                stage("Build tidb-server") {
                    stages {
                        stage("Build"){
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
                                timeout(time: 5, unit: 'MINUTES')
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
                        timeout(time: 15, unit: 'MINUTES') {
                            sh label: 'build pluginpkg tool', script: 'cd tidb/cmd/pluginpkg && go build'
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
    post {
        // TODO(wuhuizuo): put into container lifecyle preStop hook.
        always {
            container('report') {
                sh "bash scripts/plugins/report_job_result.sh ${currentBuild.result} result.json || true"
            }
            archiveArtifacts(artifacts: 'result.json', fingerprint: true, allowEmptyArchive: true)
        }
    }
}