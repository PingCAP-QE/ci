pipelineJob('optimization-build-tidb') {
    disabled(true)
    definition {
        cpsScm {
            lightweight(true)
            scriptPath('jenkins/pipelines/cd/optimization-build-tidb.groovy')
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
            name('TIDB_HASH')
            description('')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('TIKV_HASH')
            description('')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('PD_HASH')
            description('')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('BINLOG_HASH')
            description('')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('LIGHTNING_HASH')
            description('')
            defaultValue('')
            trim(false)
        }
        stringParam {
            name('IMPORTER_HASH')
            description('')
            defaultValue('')
            trim(false)
        }
        stringParam {
            name('TOOLS_HASH')
            description('')
            defaultValue('')
            trim(false)
        }
        stringParam {
            name('CDC_HASH')
            description('')
            defaultValue('')
            trim(false)
        }
        stringParam {
            name('BR_HASH')
            description('')
            defaultValue('')
            trim(false)
        }
        stringParam {
            name('DM_HASH')
            description('')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('TIFLASH_HASH')
            description('')
            defaultValue('')
            trim(false)
        }
        stringParam {
            name('DUMPLING_HASH')
            description('')
            defaultValue('')
            trim(false)
        }
        stringParam {
            name('RELEASE_TAG')
            description('')
            defaultValue('')
            trim(false)
        }
        booleanParam {
            name('PRE_RELEASE')
            description('')
            defaultValue(false)
        }
        booleanParam {
            name('SKIP_TIFLASH')
            description('是否跳过编译 tiflash，勾选则跳过编译')
            defaultValue(false)
        }
        booleanParam {
            name('BUILD_TIKV_IMPORTER')
            description('只编译 tikv 和 importer')
            defaultValue(false)
        }
        booleanParam {
            name('FORCE_REBUILD')
            description('如果文件服务器上存在相同 commit build 记录，是否rebuild')
            defaultValue(true)
        }
        stringParam {
            name('RELEASE_BRANCH')
            description('发版的分支')
            defaultValue('release-4.0')
            trim(false)
        }
        stringParam {
            name('TIKV_PRID')
            description('tikv bump version pr id, if not set  will not fetch pr')
            defaultValue('')
            trim(false)
        }
        stringParam {
            name('NGMonitoring_HASH')
            description('NGMonitoring_HASH')
            defaultValue('')
            trim(false)
        }
        stringParam {
            name('TIDB_CTL_HASH')
            description('')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('OS')
            description('')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('ARCH')
            description('')
            defaultValue('')
            trim(false)
        }
        stringParam {
            name('PLATFORM')
            description('')
            defaultValue('')
            trim(true)
        }
    }
}
