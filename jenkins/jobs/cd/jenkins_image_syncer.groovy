pipelineJob('jenkins-image-syncer') {
    definition {
        cpsScm {
            lightweight(true)
            scriptPath('jenkins/pipelines/cd/atom-jobs/jenkins-image-syncer.groovy')
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
            name('SOURCE_IMAGE')
            description('source image name, harbor')
        }
        stringParam {
            name('TARGET_IMAGE')
            description('target image name, gcr or dockerhub')
        }
    }
}
