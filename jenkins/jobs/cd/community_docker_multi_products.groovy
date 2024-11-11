pipelineJob('community-docker-multi-products') {
    disabled(true)
    definition {
        cpsScm {
            lightweight(true)
            scriptPath('jenkins/pipelines/cd/pre-release-community-docker-rocky.groovy')
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
        stringParam ('RELEASE_BRANCH','','')
        stringParam ('RELEASE_TAG','','')
        booleanParam ('FORCE_REBUILD', true, '')
        booleanParam ('NEED_DEBUG_IMAGE', true, '')
        booleanParam ('DEBUG_MODE', false, '')
        stringParam('TIDB_HASH', '', '')
        stringParam('TIKV_HASH', '', '')
        stringParam('PD_HASH', '', '')
        stringParam('TIFLASH_HASH','','')
        stringParam('NG_MONITORING_HASH','','')
        stringParam('TIDB_BINLOG_HASH','','')
        stringParam('TICDC_HASH','','')
        stringParam('IMAGE_TAG',  '', 'default RELEASE_TAG-rocky-pre')
        stringParam('HUB_PROJECT', 'qa', '')
        booleanParam('NEED_FAILPOINT', true, '')
    }
}
