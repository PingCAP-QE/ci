// REF: https://<your-jenkins-server>/plugin/job-dsl/api-viewer/index.html
final fullRepoName = 'pingcap_enterprise/tidb-enterprise-manager-ui'
final jobName = 'pr-verify'

pipelineJob("${fullRepoName}/${jobName}") {
    logRotator {
        daysToKeep(30)
    }
    properties {
        giteeConnection {
            giteeConnection('gitee.com')
        }
        pipelineTriggers {
            triggers {
                gitee {
                    triggerOnPush(false)
                    triggerOnCommitComment(false)
                    // pull requests
                    noteRegex("^/test\\s+${jobName}\$")
                    buildInstructionFilterType('CI_SKIP')
                    skipWorkInProgressPullRequest(true)
                    triggerOnOpenPullRequest(true)
                    // 0: None, 1: source branch updated, 2: target branch updated, 3: both source and target branch updated.
                    triggerOnUpdatePullRequest('3')
                    cancelIncompleteBuildOnSamePullRequest(true)
                    secretToken('gitee-webhook-secret')
                }
            }
        }
        definition {
            cpsScm {
                lightweight(true)
                scriptPath("gitee/pipelines/${fullRepoName}/pr-verify.groovy")
                scm {
                    git {
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
    }
}
