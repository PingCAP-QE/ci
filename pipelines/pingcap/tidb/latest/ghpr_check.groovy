// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and release-6.2.x branches
// 
// Pod will mount a empty dir volume to all containers at `/home/jenkins/agent`, but 
// user(`jenkins(id:1000)`) only can create dir under `/home/jenkins/agent/workspace`
//
final K8S_COULD = "kubernetes-ksyun"
final K8S_NAMESPACE = "jenkins-tidb"
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
          memory: 12Gi # 8
          cpu: 6000m # 4
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
    options {
        timeout(time: 20, unit: 'MINUTES')
    }
    stages {
        stage('Debug info') {
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
            steps {
                // restore git repo from cached items.
                container('deno') {sh label: 'restore cache', script: '''deno run --allow-all scripts/plugins/s3-cache.ts \
                    --op restore \
                    --path tidb \
                    --key "git/pingcap/tidb/rev-${ghprbActualCommit}" \
                    --key-prefix 'git/pingcap/tidb/rev-'
                '''}

                dir('tidb') {
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
        // can not parallel, it will make `parser/parser.go` regenerating.
        // cache restoring and saving should not put in parallel with same pod.
        stage("test_part_parser") {
            steps {
                cache(path: "${ENV_GOPATH}/pkg/mod", key: "gomodcache/rev-${ghprbActualCommit}", restoreKeys: ['gomodcache/rev-']) {
                    dir('tidb') {sh 'make test_part_parser' }
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
                stage("gogenerate") {
                    steps { dir('tidb') {sh 'make gogenerate' } }
                }
            }
        }
    }
}