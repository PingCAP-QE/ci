// REF: https://<your-jenkins-server>/plugin/job-dsl/api-viewer/index.html
// For trunk and latest release branches.
pipelineJob('pingcap/tiflow/pull_cdc_integration_kafka_test') {
    logRotator {
        daysToKeep(30)
    }
    parameters {
        stringParam("ghprbActualCommit")
        stringParam("ghprbPullId")
        stringParam("ghprbTargetBranch")
    }
    properties {
        buildFailureAnalyzer(false) // disable failure analyze
        githubProjectUrl("https://github.com/pingcap/tiflow")
        pipelineTriggers {
            triggers {
                ghprbTrigger {
                    cron('H/5 * * * *')
                    gitHubAuthId('') // using the default only one.

                    triggerPhrase('.*/(debug-cdc-integration-kafka-test.*)')
                    onlyTriggerPhrase(true)
                    skipBuildPhrase(".*skip-ci.*")
                    buildDescTemplate('PR #$pullId: $abbrTitle\n$url')
                    whitelist('')
                    orgslist('')
                    whiteListTargetBranches {
                        ghprbBranch { branch('master') }
                    }
                    // ignore when only those file changed.(
                    //   multi line regex
                    // excludedRegions('.*\\.md')
                    excludedRegions('') // current the context is required in github branch protection.

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
                            commitStatusContext("debug-ci/cdc-integration-kafka-test")
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
            scriptPath("pipelines/pingcap/tiflow/latest/pull_cdc_integration_kafka_test.groovy")
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
}
