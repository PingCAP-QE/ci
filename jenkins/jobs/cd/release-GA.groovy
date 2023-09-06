pipelineJob('release-GA') {
    definition {
        cpsScm {
            lightweight(true)
            scriptPath('jenkins/pipelines/cd/release-GA.groovy')
            scm {
                git{
                    remote {
                        url('https://github.com/pingcap-qe/ci.git')
                    }
                    branch('main')
                }
            }
        }
    }
}
