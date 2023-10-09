pipelineJob('dumpling-tiup-mirror-update') {
    definition {
        cpsScm {
            lightweight(true)
            scriptPath('jenkins/pipelines/cd/tiup/dumpling-tiup-mirror-update.groovy')
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
        stringParam('RELEASE_TAG', 'nightly', '')
        stringParam('TIUP_MIRRORS', '', '')
        stringParam('TIDB_VERSION', '', 'only need when version is nightly(master branch). example: v4.0.0-beta.2-nightly-20200603')
        stringParam('ORIGIN_TAG', '', '')
        booleanParam('ARCH_ARM', true, '')
        booleanParam('ARCH_X86', true, '')
        booleanParam('ARCH_MAC', true, '')
        booleanParam('ARCH_MAC_ARM', true, '')
    }
}
