pipelineJob('upload-pipeline-run-data-to-db') {
    definition {
        cpsScm {
            lightweight(true)
            scriptPath('jenkins/pipelines/cd/atom-jobs/upload-pipeline-run-data-to-db.groovy')
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
            name('PIPELINE_NAME')
            description('')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('PIPELINE_TYPE')
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
            name('JENKINS_BUILD_ID')
            description('')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('JENKINS_RUN_URL')
            description('')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('PIPELINE_REVOKER')
            description('')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('ERROR_CODE')
            description('')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('ERROR_SUMMARY')
            description('')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('PIPELINE_RUN_START_TIME')
            description('')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('PIPELINE_RUN_END_TIME')
            description('')
            defaultValue('')
            trim(true)
        }
    }
}
