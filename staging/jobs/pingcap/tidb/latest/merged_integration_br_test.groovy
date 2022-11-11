// REF: https://<your-jenkins-server>/plugin/job-dsl/api-viewer/index.html
pipelineJob('pingcap/tidb/merged_integration_br_test') {
    logRotator {
        daysToKeep(30)
    }
    parameters {
        stringParam("ghprbActualCommit")
        stringParam("ghprbTargetBranch")
    }
    properties {
        // priority(0) // 0 fast than 1
        githubProjectUrl("https://github.com/pingcap/tidb")
    }
 
    definition {
        cpsScm {
            lightweight(true)
            scriptPath('staging/pipelines/pingcap/tidb/latest/merged_integration_br_test.groovy')
            github('PingCAP-QE/ci', 'main')
        }
    }
}
