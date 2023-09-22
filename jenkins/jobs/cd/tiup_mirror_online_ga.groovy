// not able to use job dsl, it has password params, must refract the pipeline script
pipelineJob('tiup-mirror-online-ga') {
    definition {
        cpsScm {
            lightweight(true)
            scriptPath('jenkins/pipelines/cd/tiup/tiup-mirror-update-multi-products.groovy')
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
    properties {
        disableConcurrentBuilds{}
    }
    parameters {
        stringParam {
            name('RELEASE_TAG')
            defaultValue('nightly')
            trim(false)
        }
        stringParam {
            name('TIUP_MIRRORS')
            defaultValue('http://tiup.pingcap.net:8987')
            trim(false)
        }
        stringParam {
            name('TIDB_HASH')
            trim(false)
        }
        stringParam {
            name('TIKV_HASH')
            trim(false)
        }
        stringParam {
            name('PD_HASH')
            trim(false)
        }
        stringParam {
            name('BINLOG_HASH')
            trim(false)
        }
        stringParam {
            name('CDC_HASH')
            trim(false)
        }
        stringParam {
            name('DM_HASH')
            trim(false)
        }
        stringParam {
            name('BR_HASH')
            trim(false)
        }
        stringParam {
            name('DUMPLING_HASH')
            trim(false)
        }
        stringParam {
            name('TIFLASH_HASH')
            trim(false)
        }
        stringParam {
            name('TIDB_CTL_HASH')
            trim(false)
        }
        stringParam {
            name('RELEASE_BRANCH')
            trim(false)
        }
        stringParam {
            name('TIUP_ENV')
            description('prod or staging')
            defaultValue('prod')
            trim(false)
        }
        booleanParam {
            name('ARCH_ARM')
            defaultValue(true)
        }
        booleanParam {
            name('ARCH_X86')
            defaultValue(true)
        }
        booleanParam {
            name('ARCH_MAC')
            defaultValue(true)
        }
        booleanParam {
            name('ARCH_MAC_ARM')
            defaultValue(true)
        }
        booleanParam {
            name('DEBUG_MODE')
            defaultValue(false)
        }
    }
}
