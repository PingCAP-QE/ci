// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest releases branches
@Library('tipipeline') _

final GIT_CREDENTIALS_ID = 'gitee-bot-ssh'
final GITHUB_CREDENTIALS_ID = 'github-bot-ssh'
final POD_TEMPLATE_FILE = 'gitee/pipelines/pingcap_enterprise/tidb-enterprise-utilities/pod-pr-verify.yaml'
final REFS = gitee.composeRefFromEventPayload(env.jsonBody)

pipeline {
    agent {
        kubernetes {
            yamlFile POD_TEMPLATE_FILE
        }
    }
    options {
        timeout(time: 30, unit: 'MINUTES')
        parallelsAlwaysFailFast()
    }
    stages {
        stage('Checkout') {
            steps {
                addGiteeMRComment(comment: "-  :point_right:  ${JOB_NAME} [started](${BUILD_URL})")
                dir("tidb-test") {
                    cache(path: "./", filter: '**/*', key: "git/pingcap/tidb-test/rev-${REFS.pulls[0].sha}", restoreKeys: ['git/pingcap/tidb-test/rev-']) {
                        retry(2) {
                            script {
                                component.checkout('git@github.com:pingcap/tidb-test.git', 'tidb-test', REFS.base_ref, REFS.pulls[0].title, GITHUB_CREDENTIALS_ID)
                            }
                        }
                    }
                }                
                dir(REFS.repo) {
                    script {
                        cache(path: "./", filter: '**/*', key: prow.getCacheKey('gitee', REFS), restoreKeys: prow.getRestoreKeys('gitee', REFS)) {
                            retry(2) {
                                prow.checkoutPrivateRefs(REFS, GIT_CREDENTIALS_ID, timeout = 5, gitSshHost = 'gitee.com')
                            }
                        }
                    }
                }
                dir("tidb-test") {
                    cache(path: "./", filter: '**/*', key: "git/pingcap/tidb-test/rev-${REFS.pulls[0].sha}", restoreKeys: ['git/pingcap/tidb-test/rev-']) {
                        retry(2) {
                            script {
                                component.checkout('git@github.com:pingcap/tidb-test.git', 'tidb-test', REFS.base_ref, REFS.pulls[0].title, GITHUB_CREDENTIALS_ID)
                            }
                        }
                    }
                }
                // the make task will using git cli.
                container('golang') {
                    script {
                        git.setSshKey(GIT_CREDENTIALS_ID, 'gitee.com')
                    }
                }
            }
        }
        stage("Static-Checks") {
            steps {
                container('golang') {
                    dir(REFS.repo) {
                        echo "WIP"
                    }
                }
            }
        }
        stage("Unit-Test") {
            steps {
                container('golang') {
                    dir(REFS.repo) {
                        echo "WIP"
                    }
                }
            }
            post {
                always {
                    dir(REFS.repo) {
                        // archive test report to Jenkins.
                        junit(testResults: "test.xml", allowEmptyResults: true)
                    }
                }
            }
        }
        stage("Build") {
            steps {
                container('golang') {
                    dir(REFS.repo) {
                        sh script: './build_ci_tidb.sh'
                    }
                }
            }
            post {
                success {
                    dir(REFS.repo) {
                        archiveArtifacts(artifacts: 'bin/*', fingerprint: true, allowEmptyArchive: true)
                    }
                }
            }
        }
        stage("Integration-Test") {
            stages {
                stage('MySQL Tests') {
                    matrix {
                        axes {
                            axis {
                                name 'PART'
                                values '1', '2', '3', '4'
                            }
                        }
                        stages {
                            stage("Test") {
                                options {
                                    lock('mysql-test')
                                    timeout(time: 30, unit: 'MINUTES')
                                }
                                steps {
                                    container('golang') {
                                        dir(REFS.repo) {
                                            sh label: 'tidb-server', script: 'ls bin/tidb-server && chmod +x bin/tidb-server'
                                        }
                                        dir('tidb-test/mysql_test') {
                                            sh label: "part ${PART}", script: "TIDB_SERVER_PATH=${WORKSPACE}/${REFS.repo}/bin/tidb-server ./test.sh -backlist=1 -part=${PART}"
                                        }
                                    }
                                }
                                post {
                                    always {
                                        junit(testResults: "**/result.xml")
                                    }
                                    failure {
                                        archiveArtifacts(artifacts: 'mysql-test.out*', allowEmptyArchive: true)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    post {
        always {
            addGiteeMRComment(comment: "- ${JOB_NAME} [${currentBuild.result}](${BUILD_URL})")
        }
    }
}
