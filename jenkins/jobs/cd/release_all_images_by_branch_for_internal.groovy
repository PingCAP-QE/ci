pipelineJob('release-all-images-by-branch-for-internal') {
    disabled(true)
    definition {
        cpsScm {
            lightweight(true)
            scriptPath('jenkins/pipelines/cd/nightly/release-all-images-by-branch.groovy')
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
    properties {
        pipelineTriggers {
            triggers {
                parameterizedCron {
                    parameterizedSpecification('''
0  18 * * * % GIT_BRANCH=release-5.4;FORCE_REBUILD=false;NEED_MULTIARCH=false
10 18 * * * % GIT_BRANCH=release-6.1;FORCE_REBUILD=false;NEED_MULTIARCH=true
20 18 * * * % GIT_BRANCH=release-6.5;FORCE_REBUILD=false;NEED_MULTIARCH=true
30 18 * * * % GIT_BRANCH=release-7.1;FORCE_REBUILD=false;NEED_MULTIARCH=true
40 18 * * * % GIT_BRANCH=release-7.5;FORCE_REBUILD=false;NEED_MULTIARCH=true
50 18 * * * % GIT_BRANCH=release-8.1;FORCE_REBUILD=false;NEED_MULTIARCH=true
20 19 * * * % GIT_BRANCH=release-8.4;FORCE_REBUILD=false;NEED_MULTIARCH=true
H  19 * * * % GIT_BRANCH=master;FORCE_REBUILD=false;NEED_MULTIARCH=true
''')
                }
            }
        }
    }
    parameters {
        stringParam {
            name('GIT_BRANCH')
            defaultValue('master')
            trim(true)
        }
        booleanParam {
            name('FORCE_REBUILD')
            defaultValue(false)
        }
        booleanParam {
            name('NEED_MULTIARCH')
            defaultValue(false)
        }
        stringParam {
            name('PIPELINE_BUILD_ID')
            defaultValue('-1')
            trim(true)
        }
    }
}
