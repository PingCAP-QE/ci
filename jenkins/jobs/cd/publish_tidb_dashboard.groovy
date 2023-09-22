pipelineJob('publish-tidb-dashboard') {
    parameters {
        stringParam('ReleaseTag', 'test', 'empty means the same with GitRef')
    }
    definition {
        cpsScm {
            lightweight(true)
            scriptPath('jenkins/pipelines/cd/publish-tidb-dashboard.groovy')
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
