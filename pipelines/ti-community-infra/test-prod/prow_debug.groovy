// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
@Library('tipipeline') _
final REFS = params.JOB_SPEC ? readJSON(text: params.JOB_SPEC).refs : [org: 'ti-community-infra', repo: 'ci', base_sha: 'local']

pipeline {
    agent {
        kubernetes {}
    }
    options { skipDefaultCheckout() }
    stages {
        stage('Checkout') {
            steps {
                dir('test') {
                    script {
                        prow.checkoutRefs(readJSON(text: params.JOB_SPEC).refs)
                    }
                }
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
                    stage('Test Cache Able') {
                        when { expression { return !matrixCache.shouldSkip(REFS, 'Test Cache Able', [axis_os: env.AXIS_OS, axis_arch: env.AXIS_ARCH]) } }
                        steps {
                            echo "STAGE_NAME=${env.STAGE_NAME}"
                            echo "matrixCache debug axis=${env.AXIS_OS}/${env.AXIS_ARCH}"
                        }
                        post {
                            success {
                                script { matrixCache.markDone(REFS, 'Test Cache Able', [axis_os: env.AXIS_OS, axis_arch: env.AXIS_ARCH]) }
                            }
                        }
                    }
                }
            }
        }
    }
}
