// REF: https://<your-jenkins-server>/plugin/job-dsl/api-viewer/index.html
final ciGroovyPath = "jenkins/jobs/pingcap/tidb/latest/merged_integration_python_orm_test/Jenkinsfile"
pipelineJob('pingcap/tidb/merged_integration_python_orm_test') {
    logRotator {
        daysToKeep(30)
    }
    parameters {
        stringParam("BUILD_ID")
        stringParam("PROW_JOB_ID")
        stringParam("JOB_SPEC", "", "Prow job spec struct data")
    }

    definition {
        cpsScm {
            lightweight(true)
            scriptPath(ciGroovyPath)
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
