// REF: https://<your-jenkins-server>/plugin/job-dsl/api-viewer/index.html
pipelineJob('image-build') {
    logRotator {
        daysToKeep(30)
    }
    parameters {
        stringParam("CONTEXT_URL")
        stringParam("CONTEXT_ARTIFACT")
        stringParam("DOCKERFILE_PATH", defaultValue="Dockerfile")
        stringParam("DOCKERFILE_CONTENT")
        stringParam("DESTINATION")
    }

    definition {
        cpsScm {
            lightweight(true)
            scriptPath("jenkins/pipelines/qa/utf/image-build.groovy")
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
