pipelineJob('tiup-mirror-test') {
    disabled(true)
    definition {
        cpsScm {
            lightweight(true)
            scriptPath('jenkins/pipelines/cd/tiup/tiup-mirror-test.groovy')
            scm {
                git{
                    remote {
                        url('https://github.com/PingCAP-QE/ci.git')
                    }
                    // use fix_tiup because tiup will publish soon, so not merge into main
                    branch('fix_tiup')
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
            name('TIUP_MIRRORS')
            defaultValue('https://tiup-mirrors.pingcap.com')
        }
    }
}
