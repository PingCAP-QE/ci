// REF: https://<you-jenkins-server>/plugin/job-dsl/api-viewer/index.html
pipelineJob('pingcap-qe/ci/self_test') {
    logRotator {
        daysToKeep(7)
        numToKeep(100)
    }
    properties {
        githubProjectUrl("https://github.com/PingCAP-QE/ci")
        pipelineTriggers {
            triggers {
                ghprbTrigger {
                    cron('H/5 * * * *')
                    gitHubAuthId("a6f8c5ac-6082-4ad1-b84d-562cc1c37682")
                    triggerPhrase("/self-test")
                    skipBuildPhrase(".*skip-ci.*")
                    buildDescTemplate('PR #$pullId: $abbrTitle\n$url')
                    whitelist("")
                    orgslist("PingCAP-QE")
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


                    commitStatusContext("ci/ghpr_build")
                    msgSuccess("Jenkins job succeeded.")
                    msgFailure("Jenkins job failed.")
                    extensions {        
                        ghprbCancelBuildsOnUpdate { overrideGlobal(true) }
                        ghprbSimpleStatus {
                            commitStatusContext("self_test")
                            statusUrl('')
                            startedStatus('')
                            triggeredStatus('')
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
            scriptPath("pipelines/pingcap-qe/ci/ghpr_build.groovy")
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