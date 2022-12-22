// REF: https://<your-jenkins-server>/plugin/job-dsl/api-viewer/index.html
pipelineJob('pingcap/tidb/merged_mysql_test') {
    disabled(true)
    logRotator {
        daysToKeep(30)
    }
    parameters {
        stringParam("GIT_MERGE_COMMIT")
        stringParam("GIT_BASE_BRANCH")
    }
    properties {
        // priority(0) // 0 fast than 1
        githubProjectUrl("https://github.com/pingcap/tidb")
    }
 
    definition {
        cpsScm {
            lightweight(true)
            scriptPath('staging/pipelines/pingcap/tidb/latest/merged_mysql_test.groovy')
            scm {
                github('PingCAP-QE/ci', 'main')
            }
        }
    }
}
