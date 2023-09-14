pipelineJob('release-tidb-master-tiup-nightly') {
    definition {
        cpsScm {
            lightweight(true)
            scriptPath('jenkins/pipelines/cd/nightly/tiup-release-tidb-master-nightly.groovy')
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
                cron{spec('H 22 * * *')}
            }
        }
    }
    parameters {
        stringParam {
            name('RELEASE_TAG')
            defaultValue('nightly')
        }
        stringParam {
            name('TIUP_MIRRORS')
            defaultValue('http://tiup.pingcap.net:8987')
        }
        stringParam {
            name('PIPELINE_BUILD_ID')
            defaultValue('-1')
        }
    }
}
