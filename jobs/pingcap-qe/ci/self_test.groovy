// REF: https://<you-jenkins-server>/plugin/job-dsl/api-viewer/index.html
pipelineJob('pingcap-qe/ci/self_test') {
    logRotator {
        daysToKeep(7)
        numToKeep(100)
    }
    parameters {
        stringParam("BUILD_ID")
        stringParam("PROW_JOB_ID")
        stringParam("JOB_SPEC", "", "Prow job spec struct data")
    }
    properties {
        githubProjectUrl("https://github.com/PingCAP-QE/ci")
    }
 
    definition {
        cpsScm {
            lightweight(true)
            scriptPath("pipelines/pingcap-qe/ci/self_test.groovy")
            scm {
                git{
                    remote {
                        url('https://github.com/PingCAP-QE/ci.git')
                    }
                    branch('main')
                }
            }
        }
    }
}
