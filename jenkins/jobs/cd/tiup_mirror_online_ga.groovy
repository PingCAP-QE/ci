// not able to use job dsl, it has password params, must refract the pipeline script
/*pipelineJob('tiup-mirror-online-ga') {
    definition {
        cpsScm {
            lightweight(true)
            scriptPath('jenkins/pipelines/cd/tiup/tiup-mirror-update-test-new.groovy')
            scm {
                git{
                    remote {
                        url('https://github.com/PingCAP-QE/ci.git')
                    }
                    // use fix_tiup because tiup will publish soon, so not merge into main
                    branch('fix_tiup')
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
    properties {
        disableConcurrentBuilds{}
    }
}
*/
