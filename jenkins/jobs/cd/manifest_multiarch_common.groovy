pipelineJob('manifest-multiarch-common') {
    definition {
        cpsScm {
            lightweight(true)
            scriptPath('jenkins/pipelines/cd/atom-jobs/manifest-multiarch-common.groovy')
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
            name('AMD64_IMAGE')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('ARM64_IMAGE')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('MULTI_ARCH_IMAGE')
            defaultValue('')
            trim(true)
        }
    }
}
