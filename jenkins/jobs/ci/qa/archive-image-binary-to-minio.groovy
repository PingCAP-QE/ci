// REF: https://<your-jenkins-server>/plugin/job-dsl/api-viewer/index.html
pipelineJob('archive-image-binary-to-minio') {
    logRotator {
        daysToKeep(30)
    }
    parameters {
        stringParam {
            name('TIDB_TAG')
            defaultValue('qa/tidb:master')
            description('image tidb tag')
            trim(true)
        }
        stringParam {
            name('TIKV_TAG')
            defaultValue('qa/tikv:master')
            description('image tikv tag')
            trim(true)
        }
        stringParam {
            name('PD_TAG')
            defaultValue('qa/pd:master')
            description('image pd tag')
            trim(true)
        }
        stringParam {
            name('TARBALL_NAME')
            defaultValue('vx.y.z-component-pr')
            description('')
            trim(true)
        }
        stringParam {
            name('TIDB_FAILPOINT_TAG')
            defaultValue('qa/tidb:master-failpoint')
            description('failpoint image tidb tag')
            trim(true)
        }
        stringParam {
            name('TIKV_FAILPOINT_TAG')
            defaultValue('qa/tikv:master-failpoint')
            description('failpoint image tikv tag')
            trim(true)
        }
        stringParam {
            name('PD_FAILPOINT_TAG')
            defaultValue('qa/pd:master-failpoint')
            description('failpoint image pd tag')
            trim(true)
        }
        stringParam {
            name('FAILPOINT_TARBALL_NAME')
            defaultValue('vx.y.z-component-pr[failpoint]')
            description('')
            trim(true)
        }
    }
    definition {
        cpsScm {
            lightweight(true)
            scriptPath("jenkins/pipelines/ci/qa/archive-image-binary-to-minio.groovy")
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
}
