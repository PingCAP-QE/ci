pipelineJob('build-tidb-dashboard') {
    parameters {
        stringParam('GitRef', 'master', 'branch or commit hash')
        stringParam('ReleaseTag', 'test', 'empty means the same with GitRef')
    }
    definition {
        cpsScm {
            lightweight(true)
            scriptPath('jenkins/pipelines/cd/build-tidb-dashboard.groovy')
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
