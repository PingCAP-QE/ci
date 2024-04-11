// REF: https://<your-jenkins-server>/plugin/job-dsl/api-viewer/index.html
pipelineJob('qa-release-lightning-integration-test') {
    logRotator {
        daysToKeep(30)
    }
    parameters {
        stringParam {
            name('RELEASE_TAG')
            defaultValue('v8.1.0')
            description('release tag')
            trim(true)
        }
        stringParam {
            name('RELEASE_BRANCH')
            defaultValue('release-8.1')
            description('release branch')
            trim(true)
        }
    }
    definition {
        cpsScm {
            lightweight(true)
            scriptPath("jenkins/pipelines/qa/qa-release-lightning-integration-test.groovy")
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
