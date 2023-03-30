// REF: https://<your-jenkins-server>/plugin/job-dsl/api-viewer/index.html
pipelineJob('dm_ghpr_compatibility_test') {
    logRotator {
        daysToKeep(90)
    }
    parameters {
        stringParam("ghprbActualCommit")
        stringParam("ghprbPullId")
    }
    properties {
        // priority(0) // 0 fast than 1
        githubProjectUrl("https://github.com/pingcap/tiflow/")
        pipelineTriggers {
            triggers {
                ghprbTrigger {
                    cron('H/5 * * * *')
                    gitHubAuthId('37c47302-ce04-4cae-a76f-b75f439c1464')

                    triggerPhrase('(.*/run(-all-tests|-dm-compatibility-tests?).*)|(/merge)')
                    onlyTriggerPhrase(true)
                    skipBuildPhrase(".*skip-ci.*")
                    buildDescTemplate('PR #$pullId: $abbrTitle\n$url')
                    whitelist("")
                    orgslist("pingcap")
                    whiteListTargetBranches {
                        ghprbBranch { branch('^(release-)?[5]\\.[3-4](\\.\\d+)?(\\-.*)?$') }
                        ghprbBranch { branch('^(release-)?[6]\\.[0-9](\\.\\d+)?(\\-.*)?$') }
                        ghprbBranch { branch('^(release-)?[7]\\.[0-9](\\.\\d+)?(\\-.*)?$') }
                    }
                    blackListTargetBranches {
                        ghprbBranch { branch('master') }
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
                            commitStatusContext("idc-jenkins-ci-ticdc/dm-compatibility-test")
                            statusUrl('${RUN_DISPLAY_URL}')
                            startedStatus("Jenkins job is running.")
                            triggeredStatus("Jenkins job triggered.")
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
            scriptPath("jenkins/pipelines/ci/ticdc/dm_ghpr_compatibility_test.groovy")
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
