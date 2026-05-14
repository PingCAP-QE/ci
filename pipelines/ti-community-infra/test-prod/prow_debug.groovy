// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
@Library('tipipeline') _
final POD_TEMPLATE_FILE = 'pipelines/ti-community-infra/test-prod/pod-prow_debug.yaml'

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
                        steps {
                            script {
                                def refs = params.JOB_SPEC ? readJSON(text: params.JOB_SPEC).refs : [org: 'ti-community-infra', repo: 'ci', base_sha: 'local']
                                def stageName = 'matrix-cache-debug'

                                def skipWithoutAxisParams = matrixCache.shouldSkip(refs, stageName)
                                def skipWithAxisParams = matrixCache.shouldSkip(refs, stageName, [
                                    axis_os  : env.AXIS_OS,
                                    axis_arch: env.AXIS_ARCH,
                                ])

                                echo "matrixCache debug axis=${env.AXIS_OS}/${env.AXIS_ARCH}"
                                echo "matrixCache key-mode default(no extraParams): skip=${skipWithoutAxisParams}"
                                echo "matrixCache key-mode explicit(extraParams axis_os/axis_arch): skip=${skipWithAxisParams}"
                            }
                        }
                        post {
                            success {
                                script {
                                    def refs = params.JOB_SPEC ? readJSON(text: params.JOB_SPEC).refs : [org: 'ti-community-infra', repo: 'ci', base_sha: 'local']
                                    matrixCache.markDone(refs, 'matrix-cache-debug', [
                                        axis_os  : env.AXIS_OS,
                                        axis_arch: env.AXIS_ARCH,
                                    ])
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
