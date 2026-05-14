// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
@Library('tipipeline') _
final POD_TEMPLATE_FILE = 'pipelines/ti-community-infra/test-prod/pod-prow_debug.yaml'
final REFS = params.JOB_SPEC ? readJSON(text: params.JOB_SPEC).refs : [org: 'ti-community-infra', repo: 'ci', base_sha: 'local']

pipeline {
    agent {
        kubernetes {
            yamlFile POD_TEMPLATE_FILE
        }
    }
    options { skipDefaultCheckout() }
    stages {
        stage('Checkout') {
            when { expression { return params.JOB_SPEC } }
            steps {
                dir('test') {
                    script {
                        prow.checkoutRefs(readJSON(text: params.JOB_SPEC).refs)
                    }
                    sh "pwd && ls -l"
                }
                sh "pwd && ls -l"
            }
        }

        stage('Matrix Cache Debug') {
            matrix {
                axes {
                    axis {
                        name 'AXIS_OS'
                        values 'linux', 'darwin'
                    }
                    axis {
                        name 'AXIS_ARCH'
                        values 'amd64', 'arm64'
                    }
                }
                stages {
                    stage('Verify Matrix Cache Key Inputs') {
                        when {
                            expression {
                                return matrixCache.shouldSkip(REFS, env.STAGE_NAME)
                            }
                        }
                        steps {
                            echo 'matrixCache debug axis=${AXIS_OS}/${AXIS_ARCH}'
                        }
                        post {
                            success {
                                matrixCache.markDone(REFS, env.STAGE_NAME)
                            }
                        }
                    }
                }
            }
        }
    }
}
