// REF: https://<your-jenkins-server>/plugin/job-dsl/api-viewer/index.html
pipelineJob('pingcap/tidb/release-6.1/ghpr_check2') {
    logRotator {
        daysToKeep(30)
    }
    parameters {
        stringParam("BUILD_ID")
        stringParam("PROW_JOB_ID")
        stringParam("PROW_DECK_URL", "https://prow.tidb.net")
    }
    properties {
        // priority(0) // 0 fast than 1
        githubProjectUrl("https://github.com/pingcap/tidb")
    }
 
    definition {
        cpsScm {
            lightweight(true)
            scriptPath("pipelines/pingcap/tidb/release-6.1/ghpr_check2.groovy")
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
