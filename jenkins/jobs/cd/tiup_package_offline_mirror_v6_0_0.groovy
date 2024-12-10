pipelineJob('tiup-package-offline-mirror-v6.0.0') {
    disabled(true)
    definition {
        cpsScm {
            lightweight(true)
            scriptPath('jenkins/pipelines/cd/tiup/tiup-package-offline-mirror-6.0.0.groovy')
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
            description('The version to deal with')
            defaultValue('v6.1.0')
            trim(false)
        }
        stringParam {
            name('TIUP_MIRROR')
            defaultValue('http://tiup.pingcap.net:8988')
            trim(false)
        }
        stringParam {
            name('FILE_SERVER_URL')
            defaultValue('http://fileserver.pingcap.net')
            trim(false)
        }
        booleanParam {
            name('DEBUG_MODE')
            defaultValue(false)
        }
        stringParam {
            name('RELEASE_BRANCH')
            defaultValue('release-6.1')
            trim(true)
        }
    }
}
