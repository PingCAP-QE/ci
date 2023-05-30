// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/release-6.5/pod-ghpr_unit_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'golang'
        }
    }
    environment {
        FILE_SERVER_URL = 'http://fileserver.pingcap.net'
    }
    options {
        timeout(time: 90, unit: 'MINUTES')
    }
    stages {
        stage('Debug info') {
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
            steps {
                dir('tidb') {
                    cache(path: "./", filter: '**/*', key: "git/pingcap/tidb/rev-${REFS.pulls[0].sha}", restoreKeys: ['git/pingcap/tidb/rev-']) {
                        retry(2) {
                            script {
                                prow.checkoutRefs(REFS)
                            }
                        }
                    }
                    sh label: "check git workspace clean", script: """
                        git status
                        git clean -d -f -f
                        if [[ -z \$(git status -s) ]]
                        then
                            echo "tree is clean"
                        else
                            echo "tree is dirty, please contact ee team to clean up"
                            exit
                        fi
                    """
                }
            }
        }
        stage('Test') {
            environment { TIDB_CODECOV_TOKEN = credentials('codecov-token-tidb') }
            steps {
                dir('tidb') {
                    sh '''#! /usr/bin/env bash
                        set -o pipefail

                        ./build/jenkins_unit_test.sh 2>&1 | tee bazel-test.log
                        '''
                }
            }
            post {
                 success {
                    dir("tidb") {
                        sh label: "upload coverage to codecov", script: """
                        mv coverage.dat test_coverage/coverage.dat
                        wget -q -O codecov ${FILE_SERVER_URL}/download/cicd/tools/codecov-v0.5.0
                        chmod +x codecov
                        ./codecov --flags unit --dir test_coverage/ --token ${TIDB_CODECOV_TOKEN} --pr ${REFS.pulls[0].number} --sha ${REFS.pulls[0].sha} --branch origin/pr/${REFS.pulls[0].number}
                        """
                    }
                }
                failure {
                    sh label: "Parse flaky test case results", script: './scripts/plugins/analyze-go-test-from-bazel-output.sh tidb/bazel-test.log || true'
                    container('deno') {
                        sh label: "Report flaky test case results", script: """
                            deno run --allow-all http://fileserver.pingcap.net/download/ci/scripts/plugins/report-flaky-cases.ts \
                                --repo=${REFS.org}/${REFS.repo} \
                                --branch=${REFS.base_ref} \
                                --caseDataFile=bazel-go-test-problem-cases.json || true
                        """
                    }
                    archiveArtifacts(artifacts: 'bazel-*.log, bazel-*.json', fingerprint: false, allowEmptyArchive: true)
                }
                always {
                    dir('tidb') {
                        // archive test report to Jenkins.
                        junit(testResults: "**/bazel.xml", allowEmptyResults: true)
                    }
                }
            }
        }
    }
    post {
        // TODO(wuhuizuo): put into container lifecyle preStop hook.
        always {
            container('report') {
                sh """
                    junitUrl="\${FILE_SERVER_URL}/download/tipipeline/test/report/\${JOB_NAME}/\${BUILD_NUMBER}/${REFS.pulls[0].sha}/report.xml"
                    bash scripts/plugins/report_job_result.sh ${currentBuild.result} result.json "\${junitUrl}" || true
                """
            }
            archiveArtifacts(artifacts: 'result.json', fingerprint: true, allowEmptyArchive: true)
        }
    }
}
