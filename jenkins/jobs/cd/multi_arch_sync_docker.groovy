pipelineJob('multi-arch-sync-docker') {
    definition {
        cpsScm {
            lightweight(true)
            scriptPath('jenkins/pipelines/cd/multi-arch-sync-docker.groovy')
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
            name('RELEASE_TAG')
            description('')
            defaultValue('')
            trim(true)
        }
        booleanParam {
            name('IF_ENTERPRISE')
            description('')
            defaultValue(true)
        }
        stringParam {
            name('RELEASE_BRANCH')
            description('')
            defaultValue('')
            trim(true)
        }
        booleanParam {
            name('DEBUG_MODE')
            description('')
            defaultValue(false)
        }
    }
}
