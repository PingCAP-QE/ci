// REF: https://<your-jenkins-server>/plugin/job-dsl/api-viewer/index.html
pipelineJob('pingcap/tidb-test/release-6.0/ghpr_integration_mysql_test') {
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
        githubProjectUrl("https://github.com/pingcap/tidb-test")
    }
 
    definition {
        cpsScm {
            lightweight(true)
            scriptPath("pipelines/pingcap/tidb-test/release-6.0/ghpr_integration_mysql_test.groovy")
            scm {
                git{
                    remote {
                        url('https://github.com/PingCAP-QE/ci.git')
                    }
                    branch('main')
                }
            }
        }
    }
}
