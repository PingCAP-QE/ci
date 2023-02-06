// REF: https://<your-jenkins-server>/plugin/job-dsl/api-viewer/index.html
// For trunk and latest release branches.
pipelineJob('prow_debug') {
    logRotator {
        daysToKeep(30)
    }
    parameters {
        stringParam("BUILD_ID")
        stringParam("PROW_JOB_ID")
        stringParam("JOB_SPEC", "", "Prow job spec struct data")
    }
    properties {
        // priority(0) // 0 fast than 1
        githubProjectUrl("https://github.com/pingcap-qe/ee-ops")
    }
 
    definition {
        cpsScm {
            lightweight(true)
            scriptPath("staging/pipelines/pingcap-qe/ee-ops/prow_debug.groovy")
            scm {
                github('PingCAP-QE/ci', 'main')
            }
        }
    }
}
