pipelineJob('release-tidb-master-image-nightly') {
    definition {
        cpsScm {
            lightweight(true)
            scriptPath('jenkins/pipelines/cd/nightly/release_tidb_master-nightly.groovy')
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
        pipelineTriggers {
            triggers {
                cron{spec('H 23 * * *')}
            }
        }
    }
    parameters {
        stringParam {
            name('TIDB_VERSION')
            description('branch or tag')
            defaultValue('master')
            trim(false)
        }
        stringParam {
            name('TIKV_VERSION')
            description('branch or tag')
            defaultValue('master')
            trim(false)
        }
        stringParam {
            name('PD_VERSION')
            description('branch or tag')
            defaultValue('master')
            trim(false)
        }
        stringParam {
            name('TIDB_LIGHTNING_VERSION')
            description('branch or tag')
            defaultValue('master')
            trim(false)
        }
        stringParam {
            name('TIDB_BINLOG_VERSION')
            description('branch or tag')
            defaultValue('master')
            trim(false)
        }
        stringParam {
            name('TIDB_TOOLS_VERSION')
            description('branch or tag')
            defaultValue('master')
            trim(false)
        }
        stringParam {
            name('TIDB_BR_VERSION')
            defaultValue('master')
            trim(false)
        }
        stringParam {
            name('DUMPLING_VERSION')
            defaultValue('master')
            trim(false)
        }
        stringParam {
            name('RELEASE_TAG')
            defaultValue('nightly')
            trim(false)
        }
        stringParam {
            name('PIPELINE_BUILD_ID')
            defaultValue('-1')
            trim(false)
        }
    }
}
