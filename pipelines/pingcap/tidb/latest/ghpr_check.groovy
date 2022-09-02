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

pipeline {
    agent {
        kubernetes {
            cloud K8S_COULD
            namespace K8S_NAMESPACE
            defaultContainer 'golang'
            yamlFile 'pipelines/pingcap/tidb/latest/pod-ghpr_check.yaml'
        }
    }
    options {
        timeout(time: 20, unit: 'MINUTES')
    }
    environment {
        CACHE_KEEP_COUNT = '10'
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
            // FIXME(wuhuizuo): catch AbortException and set the job abort status
            // REF: https://github.com/jenkinsci/git-plugin/blob/master/src/main/java/hudson/plugins/git/GitSCM.java#L1161
            steps {
                // restore git repo from cached items.
                container('deno') {
                    sh label: 'restore cache', script: '''deno run --allow-all scripts/plugins/s3-cache.ts \
                        --op restore \
                        --path tidb \
                        --key "git/pingcap/tidb/rev-${ghprbActualCommit}" \
                        --key-prefix 'git/pingcap/tidb/rev-'
                    '''
                }
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
            }
            post {
                success { 
                    container('deno') {
                        // cache it if it's new
                        sh label: 'cache it', script: '''deno run --allow-all scripts/plugins/s3-cache.ts \
                            --op backup \
                            --path tidb \
                            --key "git/pingcap/tidb/rev-${ghprbActualCommit}" \
                            --key-prefix 'git/pingcap/tidb/rev-' \
                            --keep-count ${CACHE_KEEP_COUNT}
                        '''
                    }
                }
            }
        }
        // can not parallel, it will make `parser/parser.go` regenerating.
        // cache restoring and saving should not put in parallel with same pod.
        stage("test_part_parser") {
            steps {
                dir('tidb') {sh 'make test_part_parser' }
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