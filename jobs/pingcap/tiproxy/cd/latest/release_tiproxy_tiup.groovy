pipelineJob('release-tiproxy-tiup') {
    definition {
        cpsScm {
            lightweight(true)
            scriptPath('pipelines/pingcap/tiproxy/cd/latest/release-tiproxy-tiup.groovy')
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
        stringParam('GitRef', '', 'tiproxy repo git reference')
        stringParam('Version',  '', 'tiup package verion')
        booleanParam('TiupStaging', false, 'whether pubsh to tiup staging')
        booleanParam('TiupProduct', false, 'whether pubsh to tiup product')
    }
}
