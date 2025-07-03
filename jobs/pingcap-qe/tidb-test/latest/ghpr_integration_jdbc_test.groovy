// REF: https://<your-jenkins-server>/plugin/job-dsl/api-viewer/index.html
final fullRepo = 'pingcap-qe/tidb-test'
final branchAlias = 'latest' // For trunk and latest release branches.
final jobName = 'ghpr_integration_jdbc_test'

pipelineJob("${fullRepo}/${jobName}") {
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
        // priority(0) // 0 fast than 1
        githubProjectUrl("https://github.com/${fullRepo}")
    }

    definition {
        cpsScm {
            lightweight(true)
            scriptPath("pipelines/${fullRepo}/${branchAlias}/${jobName}/pipeline.groovy")
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
