// REF: https://<your-jenkins-server>/plugin/job-dsl/api-viewer/index.html
final ciGroovyPath = "jenkins/jobs/qa/qa_release_br_integration_test/Jenkinsfile"
pipelineJob('qa/qa-release-br-integration-test') {
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
            scriptPath(ciGroovyPath)
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
