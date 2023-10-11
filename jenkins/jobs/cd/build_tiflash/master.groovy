pipelineJob('build-tiflash-master') {
    definition {
        cpsScm {
            lightweight(true)
            scriptPath('jenkins/pipelines/cd/atom-jobs/products/tiflash/master.groovy')
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
        stringParam('GitHash', '', 'the git tag or commit')
        stringParam('Version', 'v7.3.0', 'important, the Version for cli --Version and profile choosing, eg. v6.5.0')
        choiceParam('Edition', ["community", "enterprise"])
        stringParam('PathForLinuxAmd64', '', 'build path linux amd64')
        stringParam('PathForLinuxArm64', '', 'build path linux arm64')
        stringParam('PathForDarwinAmd64', '', 'build path darwin amd64')
        stringParam('PathForDarwinArm64', '', 'build path darwin arm64')
        booleanParam('CleanBuild', false, 'disable all caches')
        stringParam('DockerImage', '', 'docker image path')
    }
}
