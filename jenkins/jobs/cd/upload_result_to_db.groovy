pipelineJob('upload_result_to_db') {
    definition {
        cpsScm {
            lightweight(true)
            scriptPath('jenkins/pipelines/cd/upload-result-to-db.groovy')
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
            name('PIPELINE_BUILD_ID')
            description('')
            defaultValue('-1')
            trim(true)
        }
        stringParam {
            name('PIPELINE_ID')
            description('')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('PIPELINE_NAME')
            description('')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('STATUS')
            description('')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('BUILD_NUMBER')
            description('')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('JOB_NAME')
            description('')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('ARTIFACT_META')
            description('')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('BEGIN_TIME')
            description('')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('END_TIME')
            description('')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('TRIGGERED_BY')
            description('')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('COMPONENT')
            description('')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('ARCH')
            description('')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('ARTIFACT_TYPE')
            description('')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('BRANCH')
            description('')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('VERSION')
            description('')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('BUILD_TYPE')
            description('')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('PUSH_GCR')
            description('')
            defaultValue('')
            trim(true)
        }
    }
}
