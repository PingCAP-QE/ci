// REF: https://<your-jenkins-server>/plugin/job-dsl/api-viewer/index.html
// For *6.2* branches.
pipelineJob('pingcap/tidb/release-6.2/ghpr_build') {
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
                    gitHubAuthId('') // using the default only one.

                    triggerPhrase('.*/(merge|run-(all-tests|build).*)')
                    onlyTriggerPhrase(false)
                    skipBuildPhrase(".*skip-ci.*")
                    buildDescTemplate('PR #$pullId: $abbrTitle\n$url')
                    whitelist("ming-relax LiangShang hsqlu yangwenmai qxhy123 mccxj dreamquster MyonKeminta colinback spongedu lzmhhh123 bb7133 dbjoa")
                    orgslist("pingcap")
                    whiteListTargetBranches {                        
                        ghprbBranch { branch('^(release-)?6\\.2(\\.\\d+)?(\\-.*)?$') }
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
                            commitStatusContext("idc-jenkins-ci-tidb/build")
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
            scriptPath("pipelines/pingcap/tidb/release-6.2/ghpr_build.groovy")
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