// REF: https://<your-jenkins-server>/plugin/job-dsl/api-viewer/index.html
pipelineJob('pingcap/tidb/tidb_merged_integration_cdc_test') {
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
        pipelineTriggers {
            triggers {
                gigthubPush()
            }
        }
    }
 
    definition {
        cpsScm {
            lightweight(true)
            scriptPath('staging/pipelines/pingcap/tidb/latest/tidb_merged_integration_cdc_test.groovy')
            scm {
                git{
                    remote {
                        url('git@github.com:PingCAP-QE/ci.git')
                        credentials('github-sre-bot-ssh')
                    }
                    branch('main')
                }
            }
        }
    }
}