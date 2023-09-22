// not able to use job dsl, it has password params, must refract the pipeline script
pipelineJob('tiup-mirror-online-rc') {
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
        stringParam('RELEASE_TAG', 'nightly', '')
        stringParam ('TIUP_MIRRORS','http://172.16.5.139:8988', '')
        stringParam('TIDB_HASH', '', '')
        stringParam('TIKV_HASH', '', '')
        stringParam('PD_HASH', '', '')
        stringParam('BINLOG_HASH', '', '')
        stringParam('CDC_HASH', '', '')
        stringParam('DM_HASH', '', '')
        stringParam('BR_HASH', '', '')
        stringParam('DUMPLING_HASH', '', '')
        stringParam('TIFLASH_HASH', '', '')
        stringParam('TIDB_CTL_HASH', '', '')
        stringParam('RELEASE_BRANCH', '', '')
        stringParam ('TIUP_ENV', 'staging', 'prod or staging')
        booleanParam('ARCH_ARM', true, '')
        booleanParam('ARCH_X86', true, '')
        booleanParam('ARCH_MAC', true, '')
        booleanParam('ARCH_MAC_ARM', true, '')
        booleanParam('DEBUG_MODE', false, '')
    }
}
