// REF: https://<your-jenkins-server>/plugin/job-dsl/api-viewer/index.html
// Triggered by Prow as a manual presubmit.
pipelineJob('pingcap-inc/tiflash-scripts/pull_regression_test') {
    logRotator {
        daysToKeep(30)
    }
    parameters {
        // Ref: https://docs.prow.k8s.io/docs/jobs/#job-environment-variables
        stringParam("BUILD_ID")
        stringParam("PROW_JOB_ID")
        stringParam("JOB_SPEC")

        // Legacy pipeline parameters.
        stringParam("desc", "TiFlash regression daily")
        stringParam("branch", "master")
        stringParam("version", "latest")
        stringParam("tidb_commit_hash", "")
        stringParam("tikv_commit_hash", "")
        stringParam("pd_commit_hash", "")
        stringParam("tiflash_commit_hash", "")
        stringParam("notify", "false")
        stringParam("idleMinutes", "5")
        stringParam("pipeline", "")
        stringParam("FILE_SERVER_URL", "https://fileserver.pingcap.net")
        stringParam("ghprbTargetBranch", "")
        stringParam("ghprbCommentBody", "")
        stringParam("ghprbActualCommit", "")
        stringParam("ghprbPullId", "0")
    }
    properties {
        githubProjectUrl("https://github.com/pingcap-inc/tiflash-scripts")
    }

    definition {
        cpsScm {
            lightweight(true)
            scriptPath("pipelines/pingcap-inc/tiflash-scripts/latest/pull_regression_test.groovy")
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
