job('tiflow-operator-docker') {
    definition {
        cpsScm {
            lightweight(true)
            scriptPath('jenkins/pipelines/cd/tiflow-operator-docker.groovy')
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
    triggers {
        cron('@daily')
    }
    parameters {
        stringParam {
            name('Revision')
            defaultValue('master')
            description('branch or commit hash')
        }
    }
}
