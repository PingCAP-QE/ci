pipelineJob('pre-release-docker') {
    disabled(true)
    definition {
        cpsScm {
            lightweight(true)
            scriptPath('jenkins/pipelines/cd/pre-release-community-docker.groovy')
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
            name('RELEASE_BRANCH')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('RELEASE_TAG')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('TIKV_BUMPVERION_HASH')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('TIKV_BUMPVERSION_PRID')
            defaultValue('')
            trim(true)
        }
        booleanParam {
            name('FORCE_REBUILD')
            defaultValue(true)
        }
        booleanParam {
            name('NEED_DEBUG_IMAGE')
            defaultValue(true)
        }
        booleanParam {
            name('DEBUG_MODE')
            defaultValue(false)
        }
        stringParam {
            name('TIDB_HASH')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('TIKV_HASH')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('PD_HASH')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('TIFLASH_HASH')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('NG_MONITORING_HASH')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('TIDB_BINLOG_HASH')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('TICDC_HASH')
            defaultValue('')
            trim(true)
        }
    }
}
