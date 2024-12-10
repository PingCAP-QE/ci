pipelineJob('tiup-package-offline-mirror-dm') {
    disabled(true)
    definition {
        cpsScm {
            lightweight(true)
            scriptPath('jenkins/pipelines/cd/tiup-package-offline-mirror-dm.groovy')
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
            name('VERSION')
            description('The version of dm')
            defaultValue('v2.0.5')
            trim(false)
        }
        stringParam {
            name('TIUP_VERSION')
            description('The version of tiup itself')
            defaultValue('v1.9.3')
            trim(false)
        }
        stringParam {
            name('TIUP_MIRRORS')
            description('')
            defaultValue('https://tiup-mirrors.pingcap.com')
            trim(false)
        }
        stringParam {
            name('FILE_SERVER_URL')
            description('')
            defaultValue('http://fileserver.pingcap.net')
            trim(false)
        }
        booleanParam {
            name('DEBUG_MODE')
            description('')
            defaultValue(false)
        }
    }
}
