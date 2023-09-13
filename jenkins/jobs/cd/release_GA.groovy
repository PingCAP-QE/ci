pipelineJob('release-GA') {
    definition {
        cpsScm {
            lightweight(true)
            scriptPath('jenkins/pipelines/cd/release-GA.groovy')
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
            defaultValue('v6.1.0')
            trim(false)
        }
        stringParam {
            name('RELEASE_BRANCH')
            description('发版的分支')
            defaultValue('release-6.1')
            trim(false)
        }
        booleanParam {
            name('NEED_MULTIARCH')
            description('')
            defaultValue(true)
        }
        booleanParam {
            name('DEBUG_MODE')
            description('')
            defaultValue(false)
        }
        stringParam {
            name('PIPELINE_BUILD_ID')
            description('')
            defaultValue('-1')
            trim(false)
        }
    }
}
