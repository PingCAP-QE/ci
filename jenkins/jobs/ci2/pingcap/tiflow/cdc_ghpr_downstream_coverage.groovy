// REF: https://<your-jenkins-server>/plugin/job-dsl/api-viewer/index.html
pipelineJob('cdc_ghpr_downstream_coverage') {
    logRotator {
        daysToKeep(30)
    }
    parameters {
        stringParam{
            name('COVERAGE_FILE')
            trim(true)
        }
        stringParam{
            name('CI_NAME')
            defaultValue('jenkins-ci')
            trim(true)
        }
        stringParam{
            name('CI_BUILD_NUMBER')
            trim(true)
        }
        stringParam{
            name('CI_BUILD_URL')
            trim(true)
        }
        stringParam{
            name('CI_BRANCH')
            trim(true)
        }
        stringParam{
            name('CI_PULL_REQUEST')
            trim(true)
        }
    }
    properties {
        // priority(0) // 0 fast than 1
        githubProjectUrl("https://github.com/pingcap/tiflow/")
    }

    definition {
        cpsScm {
            lightweight(true)
            scriptPath("jenkins/pipelines/ci/ticdc/cdc_ghpr_downstream_coverage.groovy")
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
