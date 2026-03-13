// REF: https://<your-jenkins-server>/plugin/job-dsl/api-viewer/index.html
// Triggered by Prow as a manual presubmit.
pipelineJob('pingcap/tiflash/pull_schrodinger_test') {
    logRotator {
        daysToKeep(30)
    }
    parameters {
        // Ref: https://docs.prow.k8s.io/docs/jobs/#job-environment-variables
        stringParam("BUILD_ID")
        stringParam("PROW_JOB_ID")
        stringParam("JOB_SPEC")

        // Legacy pipeline parameters.
        stringParam("desc", "TiFlash schrodinger test")
        stringParam("branch", "master")
        stringParam("version", "latest")
        stringParam("testcase", "")
        stringParam("maxRunTime", "120")
        stringParam("notify", "false")
        stringParam("idleMinutes", "5")
        stringParam("tidb_commit_hash", "")
        stringParam("tikv_commit_hash", "")
        stringParam("pd_commit_hash", "")
        stringParam("tiflash_commit_hash", "")
        stringParam("FILE_SERVER_URL", "https://fileserver.pingcap.net")
    }
    properties {
        githubProjectUrl("https://github.com/pingcap/tiflash")
    }

    definition {
        cpsScm {
            lightweight(true)
            scriptPath("pipelines/pingcap/tiflash/latest/pull_schrodinger_test.groovy")
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
