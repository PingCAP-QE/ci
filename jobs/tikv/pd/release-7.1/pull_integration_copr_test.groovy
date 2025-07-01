// REF: https://<your-jenkins-server>/plugin/job-dsl/api-viewer/index.html
// For release branches.
pipelineJob('tikv/pd/release-7.1/pull_integration_copr_test') {
    logRotator {
        daysToKeep(30)
    }
    parameters {
        // Ref: https://docs.prow.k8s.io/docs/jobs/#job-environment-variables
        stringParam("BUILD_ID")
        stringParam("PROW_JOB_ID")
        stringParam("JOB_SPEC", "", "Prow job spec struct data")
    }
    properties {
        buildFailureAnalyzer(false) // disable failure analyze
        githubProjectUrl("https://github.com/tikv/pd")
    }

    definition {
        cpsScm {
            lightweight(true)
            scriptPath("pipelines/tikv/pd/release-7.1/pull_integration_copr_test.groovy")
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
