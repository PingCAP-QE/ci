pipelineJob('devbuild') {
    definition {
        cpsScm {
            lightweight(true)
            scriptPath('jenkins/pipelines/cd/dev-build.groovy')
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
            name('Product')
            choices(['tidb', 'tikv', 'pd', 'tiflash', 'br', 'dumpling', 'tidb-lightning', 'ticdc', 'dm', 'tidb-binlog', 'tidb-tools'])
            description('the product to build, e.g., tidb/tikv/pd')
        }
        stringParam {
            name('GitRef')
            description('the git tag or commit or branch or pull/id of repo')
        }
        stringParam {
            name('Version')
            description('important, the version for cli --version and profile choosing, e.g., v6.5.0')
        }
        choiceParam {
            name('Edition')
            choices(['community', 'enterprise'])
        }
        stringParam {
            name('PluginGitRef')
            description('the git commit for enterprise plugin, only in enterprise tidb')
            defaultValue('master')
        }
        stringParam {
            name('GithubRepo')
            description('the GitHub repo, just ignore unless in a forked repo, e.g., pingcap/tidb')
            defaultValue('')
        }
        booleanParam {
            name('IsPushGCR')
            description('whether push GCR')
        }
        booleanParam {
            name('IsHotfix')
            description('is it a hotfix build')
            defaultValue(false)
        }
        stringParam {
            name('Features')
            description('comma-separated features to build with')
            defaultValue('')
        }
        stringParam {
            name('TiBuildID')
            description('the ID of TiBuild object, just leave empty if you do not know')
        }
    }
}
