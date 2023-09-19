pipelineJob('pre-release-check') {
    definition {
        cpsScm {
            lightweight(true)
            scriptPath('jenkins/pipelines/cd/pre-release-check.groovy')
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
            trim(true)
        }
        stringParam {
            name('TIDB_VERSION')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('TIKV_VERSION')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('PD_VERSION')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('TIFLASH_VERSION')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('BR_VERSION')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('BINLOG_VERSION')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('LIGHTNING_VERSION')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('TOOLS_VERSION')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('CDC_VERSION')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('DUMPLING_VERSION')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('DM_VERSION')
            defaultValue('')
            trim(true)
        }
    }
}
