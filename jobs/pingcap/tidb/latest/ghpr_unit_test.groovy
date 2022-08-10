// REF: https://<your-jenkins-server>/plugin/job-dsl/api-viewer/index.html
// For trunk and latest release branches.
pipelineJob('pingcap/tidb/ghpr_unit_test') {
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
                    gitHubAuthId('8b25795b-a680-4dce-9904-89ef40d73159') // tidb-ci-bot.
                    
                    // triggerPhrase('.*/(merge|run-(all-tests|unit-test).*)')
                    // onlyTriggerPhrase(false)

                    // ### debug
                    triggerPhrase('/gray-debug')
                    onlyTriggerPhrase(true)

                    skipBuildPhrase(".*skip-ci.*")
                    buildDescTemplate('PR #$pullId: $abbrTitle\n$url')
                    whitelist('')
                    orgslist('')
                    whiteListTargetBranches {
                        // - master
                        // - release-6.2
                        // - release-6.2.0
                        // - release-6.2-20221212
                        // - release-6.2.0-20221314                       
                        // - 6.2-*
                        // - 6.2.0-*
                        ghprbBranch { branch('^master|(release-)?6\\.[2-9]\\d*(\\.\\d+)?(\\-.*)?$') }
                    }
                    // ignore when only those file changed.(
                    //   multi line regex
                    excludedRegions('.*\\.md')

                    blackListLabels("")
                    whiteListLabels("")
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
                            commitStatusContext("IGNORE-gray-unit-test") // debug: no block the pr.
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
            scriptPath("pipelines/pingcap/tidb/latest/ghpr_unit_test.groovy")
            scm {
                git{
                    remote {
                        url("https://github.com/PingCAP-QE/ci.git")
                    }
                    branch("main")
                }
            }
        }
    }
}