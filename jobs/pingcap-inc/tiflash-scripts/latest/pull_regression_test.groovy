// REF: https://<your-jenkins-server>/plugin/job-dsl/api-viewer/index.html
// Triggered by Prow as a manual presubmit.
pipelineJob('pingcap-inc/tiflash-scripts/pull_regression_test') {
    logRotator {
        daysToKeep(30)
    }
    parameters {
        // Ref: https://docs.prow.k8s.io/docs/jobs/#job-environment-variables
        stringParam("BUILD_ID")
        stringParam("PROW_JOB_ID")
        stringParam("JOB_SPEC")
    }
    properties {
        githubProjectUrl("https://github.com/pingcap-inc/tiflash-scripts")
    }

    definition {
        cpsScm {
            lightweight(true)
            scriptPath("pipelines/pingcap-inc/tiflash-scripts/latest/pull_regression_test/pipeline.groovy")
            scm {
                git{
                    remote {
                        url('https://github.com/PingCAP-QE/ci.git')
                    }
                    branch('main')
                    extensions {
                        cloneOptions {
                            depth(1)
                            shallow(true)
                            timeout(5)
                        }
                    }
                }
            }
        }
    }
}
