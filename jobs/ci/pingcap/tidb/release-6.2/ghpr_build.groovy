// REF: https://<you-jenkins-server>/plugin/job-dsl/api-viewer/index.html
pipelineJob('wip_tidb_ghpr_build') {
    disabled(true)
    logRotator {
        daysToKeep(180)
        numToKeep(2000)
    }
    parameters {
        stringParam("ghprbActualCommit")
        stringParam("ghprbPullId")
    }
    properties {
        // priority(0) // 0 fast than 1
        githubProjectUrl("https://github.com/pingcap/tidb")
        pipelineTriggers {
            triggers {
                ghprbTrigger {
                    cron('H/5 * * * *')
                    gitHubAuthId("a6f8c5ac-6082-4ad1-b84d-562cc1c37682") // sre-bot.
                    triggerPhrase(".*/(merge|run(-all-tests|-build).*)")
                    skipBuildPhrase(".*skip-ci.*")
                    buildDescTemplate('PR #$pullId: $abbrTitle\n$url')
                    whitelist("ming-relax LiangShang hsqlu yangwenmai qxhy123 mccxj dreamquster MyonKeminta colinback spongedu lzmhhh123 bb7133 dbjoa")
                    orgslist("pingcap")
                    blackListLabels("")
                    whiteListLabels("")
                    adminlist("")
                    blackListCommitAuthor("")
                    includedRegions("")
                    excludedRegions("")
                    commentFilePath("")

                    allowMembersOfWhitelistedOrgsAsAdmin(true)
                    permitAll(true)
                    useGitHubHooks(true)
                    onlyTriggerPhrase(false)
                    displayBuildErrorsOnDownstreamBuilds(false)
                    autoCloseFailedPullRequests(false)

                    // useless, but can not delete.
                    commitStatusContext("--none--")
                    msgSuccess("--none--")
                    msgFailure("--none--")

                    extensions {
                        ghprbCancelBuildsOnUpdate { overrideGlobal(true) }
                        ghprbNoCommitStatus() // debug: disable status update
                        ghprbSimpleStatus {
                            commitStatusContext("gray-build") // debug: no block the pr.
                            commitStatusContext("") // using default
                            statusUrl("--none--") // debug: disappear build url
                            startedStatus("")
                            triggeredStatus("")
                            addTestResults(false)
                            showMatrixStatus(false)
                        }
                    }
                }
            }
        }
    }
 
    definition {
        cpsScm {
            scriptPath("pipelines/pingcap/tidb/release-6.2/ghpr_build.groovy")
            scm {
                git{
                    remote {
                        url("https://github.com/PingCAP-QE/ci.git")
                    }
                    branch("feature/refactor-tidb-verify-ci-tidb-ghpr-build")
                }
            }
        }
    }
}