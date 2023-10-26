pipelineJob('community-docker-multi-products') {
    definition {
        cpsScm {
            lightweight(true)
            scriptPath('jenkins/pipelines/cd/pre-release-community-docker-rocky.groovy')
            scm {
                git{
                    remote {
                        url('https://github.com/PingCAP-QE/ci.git')
                    }
                    branch('feat/tiup_image')
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
        stringParam('POSTFIX',  '-rocky-pre', '')
        stringParam('HUB_PROJECT', 'qa', '')
    }
}
