// REF: https://<your-jenkins-server>/plugin/job-dsl/api-viewer/index.html
pipelineJob('tidb_ghpr_integration_campatibility_test') {
    logRotator {
        daysToKeep(90)
    }
    parameters {
        stringParam("ghprbActualCommit")
        stringParam("ghprbPullId")
        stringParam("ghprbTargetBranch")
    }
    properties {
        // priority(0) // 0 fast than 1
        githubProjectUrl("https://github.com/pingcap/tidb")
        pipelineTriggers {
            triggers {
                ghprbTrigger {
                    cron('H/5 * * * *')
                    gitHubAuthId('8b25795b-a680-4dce-9904-89ef40d73159')

                    triggerPhrase('.*\/(run(-integration-tests|-integration-compatibility-test).*)')
                    onlyTriggerPhrase(true)
                    skipBuildPhrase(".*skip-ci.*")
                    buildDescTemplate('PR #$pullId: $abbrTitle\n$url')
                    whitelist('')
                    orgslist('pingcap')
                    whiteListTargetBranches {
                        ghprbBranch { branch('master') }
                        ghprbBranch { branch('^(release-)?5\\.[0-4]\\d*(\\.\\d+)?(\\-.*)?$') }
                        ghprbBranch { branch('^(release-)?6\\.[2-9]\\d*(\\.\\d+)?(\\-.*)?$') }
                    }
                    // ignore when only those file changed.(
                    //   multi line regex
                    // excludedRegions('.*\\.md')
                    excludedRegions('') // current the context is required in github branch protection.

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
                            commitStatusContext('idc-jenkins-ci-tidb/integration-compatibility-test')
                            statusUrl('${RUN_DISPLAY_URL}')
                            startedStatus('Jenkins job is running.')
                            triggeredStatus('Jenkins job triggered.')
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
            scriptPath('jenkins/pipelines/ci/tidb/tidb_ghpr_integration_campatibility_test.groovy')
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