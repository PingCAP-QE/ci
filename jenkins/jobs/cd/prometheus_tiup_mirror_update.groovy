pipelineJob('prometheus-tiup-mirror-update') {
    disabled(true)
    definition {
        cpsScm {
            lightweight(true)
            scriptPath('jenkins/pipelines/cd/tiup/prometheus-tiup-mirror-update.groovy')
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
        stringParam('VERSION', '2.8.1', 'The Prometheus version')
        stringParam('RELEASE_TAG', 'nightly', 'The TiDB version')
        stringParam('TIUP_MIRRORS', '', '')
        stringParam('TIDB_VERSION', '', 'tag when version is nightly, version format is: v4.0.0-beta.2-nightly-20200603')
        stringParam('RELEASE_BRANCH', '', '')
        booleanParam('ARCH_ARM', true, '')
        booleanParam('ARCH_X86', true, '')
        booleanParam('ARCH_MAC', true, '')
        booleanParam('ARCH_MAC_ARM', true, '')
    }
}
