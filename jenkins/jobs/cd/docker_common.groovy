pipelineJob('docker-common') {
    definition {
        cpsScm {
            lightweight(true)
            scriptPath('jenkins/pipelines/cd/atom-jobs/docker-common.groovy')
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
        choiceParam {
            name('ARCH')
            choices(['arm64', 'amd64'])
        }
        choiceParam {
            name('OS')
            choices(['linux', 'darwin'])
        }
        stringParam {
            name('INPUT_BINARYS')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('REPO')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('PRODUCT')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('RELEASE_TAG')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('DOCKERFILE')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('RELEASE_DOCKER_IMAGES')
            defaultValue('')
            trim(true)
        }
    }
}
