// REF: https://<your-jenkins-server>/plugin/job-dsl/api-viewer/index.html
pipelineJob('pingcap/tidb/periodics_integration_test') {
    logRotator {
        daysToKeep(7)
    }
    parameters {
        stringParam("TARGET_BRANCH", "master", "Target branch to verify")
    }
    properties {
        // priority(0) // 0 fast than 1
        githubProjectUrl("https://github.com/pingcap/tidb")
        pipelineTriggers {
            triggers {
                cron{ spec('0 * * * *') }
            }
        }
    }

    definition {
        cpsScm {
            lightweight(true)
            scriptPath('pipelines/pingcap/tidb/latest/periodics_integration_test.groovy')
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
