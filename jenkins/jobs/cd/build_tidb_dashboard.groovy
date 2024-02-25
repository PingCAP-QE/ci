pipelineJob('build-tidb-dashboard') {
    parameters {
        stringParam('GitRef', 'master', 'branch or commit hash')
        stringParam('ReleaseTag', 'test', 'empty means the same with GitRef')
        booleanParam('IsDevbuild', false, 'if it is a devbuild')
        stringParam('BinaryPrefix', '', 'optional Binary path prefix in fileserver')
        stringParam('DockerImg', '', 'optional given Docker Imag Path')
        stringParam('BuildEnv', '', 'optional build enviroment var')
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
