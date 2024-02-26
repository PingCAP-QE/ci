pipelineJob('grafana-tiup-mirror-update') {
    definition {
        cpsScm {
            lightweight(true)
            scriptPath('jenkins/pipelines/cd/tiup/grafana-tiup-mirror-update.groovy')
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
        stringParam('VERSION', '7.5.11', 'the grafana version')
        stringParam('RELEASE_TAG', 'nightly', 'the tidb version')
        stringParam('RELEASE_BRANCH', 'master', 'target branch')
        stringParam('TIDB_VERSION', '', 'tiup pkg version')
        stringParam('TIUP_MIRRORS', 'http://172.16.5.134:8987', 'tiup mirror')
    }
}
