// REF: https://<your-jenkins-server>/plugin/job-dsl/api-viewer/index.html
pipelineJob('tikv_ghpr_test') {
    logRotator {
        daysToKeep(90)
        numToKeep(1000)
    }
    parameters {
        stringParam{
            name('DEBUG_VALUE')
            trim(true)
        }
        stringParam{
            name('TIKV_TEST_MEMORY_DISK_MOUNT_POINT')
            defaultValue('/home/jenkins/agent/memvolume')
            trim(true)
        }
    }
    properties {
        // priority(0) // 0 fast than 1
        githubProjectUrl("https://github.com/tikv/tikv/")
        pipelineTriggers {
            triggers {
                ghprbTrigger {
                    cron('H/5 * * * *')
                    gitHubAuthId('a6f8c5ac-6082-4ad1-b84d-562cc1c37682')

                    triggerPhrase('.*/(run(-all-tests|-test)|test).*')
                    onlyTriggerPhrase(false)
                    skipBuildPhrase(".*skip-ci.*")
                    buildDescTemplate('PR #$pullId: $abbrTitle\n$url')
                    whitelist('')
                    orgslist('pingcap tikv')
                    whiteListTargetBranches {
                        ghprbBranch { branch('^release-6\\.1(\\.\\d+)?(\\-.*)?$') }
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
                            commitStatusContext('idc-jenkins-ci/test')
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
            scriptPath('jenkins/pipelines/ci/tikv/tikv_ghpr_test.groovy')
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
