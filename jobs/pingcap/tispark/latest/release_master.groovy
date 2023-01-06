// REF: https://<your-jenkins-server>/plugin/job-dsl/api-viewer/index.html
// For trunk and latest release branches.
pipelineJob('pingcap/tispark/release_master') {
    logRotator {
        daysToKeep(30)
    }
    parameters {
        stringParam("ghprbActualCommit")
        stringParam("ghprbPullId")
    }
    properties {
        // priority(0) // 0 fast than 1
        githubProjectUrl("https://github.com/pingcap/tispark")
    }

    definition {
        cpsScm {
            lightweight(true)
            scriptPath("pipelines/pingcap/tispark/latest/release_master.groovy")
            scm {
                github('PingCAP-QE/ci', 'main')
            }
        }
    }
}
