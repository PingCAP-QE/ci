pipelineJob('release-tiproxy-tiup') {
    definition {
        cpsScm {
            lightweight(true)
            scriptPath('jenkins/pipelines/cd/release-tiproxy-tiup.groovy')
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
        parameters{
            stringParam('GitRef', 'v0.1.1', 'tiproxy repo git reference')
            stringParam('Version',  'nightly', 'tiup package verion')
            booleanParam('TiupStaging', false, 'whether pubsh to tiup staging')
            booleanParam('TiupProduct', false, 'whether pubsh to tiup product')
        }
    }
}
