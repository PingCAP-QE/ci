pipelineJob('pre-release-tiup') {
    disabled(true)
    definition {
        cpsScm {
            lightweight(true)
            scriptPath('jenkins/pipelines/cd/tiup/pre-release-tiup.groovy')
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
            name('RELEASE_TAG')
            description('预发布tag,可以不存在')
            defaultValue('v5.0.0')
            trim(true)
        }
        stringParam {
            name('RELEASE_BRANCH')
            description('预发布分支，所有构建代码基于这个分支拉取')
            defaultValue('release-5.0')
            trim(true)
        }
        stringParam {
            name('TIDB_PRM_ISSUE')
            description('tidb_prm issue id\nhttps://github.com/pingcap/tidb-prm/issues\n默认为空，当填写了 issue id 的时候，sre-bot 会自动更新各组件 hash 到 issue 上')
            trim(true)
        }
        stringParam {
            name('TIUP_MIRRORS')
            description('tiup 对应环境，预发布默认发布到tiup staging 环境，非特殊情况不要修改默认值')
            defaultValue('http://tiup.pingcap.net:8988')
            trim(true)
        }
        stringParam {
            name('TIKV_BUMPVERION_HASH')
            description('tikv 的version 升级需要修改代码，所以我们通过一个pr提前bump version ，这里填写pr commitid\npr示例:https://github.com/tikv/tikv/pull/10406\n如果暂时没有pr则不填，使用RELEASE_BRANCH 对应的代码')
            trim(true)
        }
        stringParam {
            name('TIKV_BUMPVERSION_PRID')
            description('tikv 的version 升级需要修改代码，所以我们通过一个pr提前bump version ，这里填写pr ID\npr示例:https://github.com/tikv/tikv/pull/10406\n如果暂时没有pr则不填，使用RELEASE_BRANCH 对应的代码')
            trim(true)
        }
        booleanParam {
            name('ARCH_ARM')
            description('是否发布arm')
        }
        booleanParam {
            name('ARCH_X86')
            description('是否发布x86')
        }
        booleanParam {
            name('ARCH_MAC')
            description('是否发布mac')
        }
        booleanParam {
            name('FORCE_REBUILD')
            description('是否需要强制重新构建（false 则按照hash检查文件服务器上是否存在对应二进制，存在则不构建）')
        }
        booleanParam {
            name('ARCH_MAC_ARM')
            description('是否发布mac m1版本')
        }
        stringParam {
            name('PIPELINE_BUILD_ID')
            trim(true)
        }
    }
}
