pipelineJob('pre-release-docker-rocky-sync') {
    disabled(true)
    definition {
        cpsScm {
            lightweight(true)
            scriptPath('jenkins/pipelines/cd/pre-release-docker-rocky-sync.groovy')
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
    parameters {
        stringParam {
            name('Version')
            description('RC release tag')
            trim(true)
        }
    }
}
