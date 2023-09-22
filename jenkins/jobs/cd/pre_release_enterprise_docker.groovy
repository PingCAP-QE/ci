pipelineJob('pre-release-enterprise-docker') {
    definition {
        cpsScm {
            lightweight(true)
            scriptPath('jenkins/pipelines/cd/pre-release-enterprise-docker.groovy')
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
            defaultValue('')
            description('')
            trim(true)
        }
        stringParam {
            name('RELEASE_BRANCH')
            defaultValue('')
            description('')
            trim(true)
        }
        stringParam {
            name('TIDB_HASH')
            defaultValue('')
            description('')
            trim(true)
        }
        stringParam {
            name('TIKV_HASH')
            defaultValue('')
            description('')
            trim(true)
        }
        stringParam {
            name('PD_HASH')
            defaultValue('')
            description('')
            trim(true)
        }
        stringParam {
            name('TIFLASH_HASH')
            defaultValue('')
            description('')
            trim(true)
        }
        stringParam {
            name('PLUGIN_HASH')
            defaultValue('')
            description('')
            trim(true)
        }
        booleanParam {
            name('FORCE_REBUILD')
            defaultValue(true)
            description('')
        }
        booleanParam {
            name('DEBUG_MODE')
            defaultValue(false)
        }
    }
}
