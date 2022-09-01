// REF: https://<your-jenkins-server>/plugin/job-dsl/api-viewer/index.html
// For trunk and latest release branches.
pipelineJob('pingcap/tidb/ghpr_check2') {
    // disabled(true)
    logRotator {
        daysToKeep(180)
        numToKeep(1500)
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
                    gitHubAuthId('') // using the default only one.
                    
                    triggerPhrase('.*/(merge|run-(all-tests|check[-_]dev[-_]?2))')
                    onlyTriggerPhrase(false)
                    // ### debug
                    // triggerPhrase('/gray-debug')
                    // onlyTriggerPhrase(true)

                    skipBuildPhrase(".*skip-ci.*")
                    buildDescTemplate('PR #$pullId: $abbrTitle\n$url')
                    whitelist('ming-relax LiangShang hsqlu yangwenmai qxhy123 mccxj dreamquster MyonKeminta colinback spongedu lzmhhh123 bb7133 dbjoa')
                    orgslist('pingcap')
                    whiteListTargetBranches {
                        // - master
                        // - release-6.2
                        // - release-6.2.0
                        // - release-6.2-20221212
                        // - release-6.2.0-20221314                       
                        // - 6.2-*
                        // - 6.2.0-*
                        ghprbBranch { branch('master') }
                    }
                    // ignore when only those file changed.(
                    //   multi line regex
                    excludedRegions('.*\\.md')
                    
                    blackListLabels("") // list of GitHub labels for which the build should not be triggered.
                    whiteListLabels("") // list of GitHub labels for which the build should only be triggered.
                    adminlist("")
                    blackListCommitAuthor("")
                    includedRegions("")
                    commentFilePath("")

                    allowMembersOfWhitelistedOrgsAsAdmin(true)
                    permitAll(true)
                    useGitHubHooks(true)
                    displayBuildErrorsOnDownstreamBuilds(false)
                    autoCloseFailedPullRequests(false)

                    // useless, but can not delete.
                    commitStatusContext("--none--")
                    msgSuccess("--none--")
                    msgFailure("--none--")

                    extensions {
                        ghprbCancelBuildsOnUpdate { overrideGlobal(true) }
                        ghprbSimpleStatus {
                            commitStatusContext("IGNORE-gray-check_dev_2") // debug: no block the pr.
                            statusUrl('${RUN_DISPLAY_URL}')
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
            lightweight(true)
            scriptPath("pipelines/pingcap/tidb/latest/ghpr_check2.groovy")
            scm {
                git{
                    remote {
                        url('git@github.com:PingCAP-QE/ci.git')
                        credentials('github-sre-bot-ssh')
                    }
                    branch('main')
                }
            }
        }
    }
}