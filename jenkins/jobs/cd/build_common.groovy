pipelineJob('build-common') {
    definition {
        cpsScm {
            lightweight(true)
            scriptPath('jenkins/pipelines/cd/atom-jobs/build-common.groovy')
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
        choiceParam {
            name('EDITION')
            choices(['community', 'enterprise'])
        }
        stringParam {
            name('OUTPUT_BINARY')
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
            name('GIT_HASH')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('GIT_PR')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('RELEASE_TAG')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('TARGET_BRANCH')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('TIDB_HASH')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('GITHUB_REPO')
            defaultValue('')
            trim(true)
        }
        booleanParam {
            name('FORCE_REBUILD')
            defaultValue(true)
        }
        booleanParam {
            name('FAILPOINT')
            defaultValue(false)
        }
        booleanParam {
            name('NEED_SOURCE_CODE')
            defaultValue(false)
        }
        stringParam {
            name('USE_TIFLASH_RUST_CACHE')
            defaultValue('')
            trim(true)
        }
        stringParam {
            name('BUILD_ENV')
            defaultValue('')
        }
        stringParam {
            name('BUILDER_IMG')
            defaultValue('')
        }
        booleanParam {
            name('TIFLASH_DEBUG')
            defaultValue(false)
        }
    }
}
