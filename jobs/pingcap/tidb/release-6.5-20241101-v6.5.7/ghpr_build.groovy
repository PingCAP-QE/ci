// REF: https://<your-jenkins-server>/plugin/job-dsl/api-viewer/index.html
final folder = 'pingcap/tidb/release-6.5-20241101-v6.5.7'
final jobName = 'ghpr_build'

pipelineJob("${folder}/${jobName}") {
    logRotator {
        daysToKeep(30)
    }
    parameters {
        stringParam("BUILD_ID")
        stringParam("PROW_JOB_ID")
        stringParam("JOB_SPEC", "", "Prow job spec struct data")
    }
    properties {
        // priority(0) // 0 fast than 1
        githubProjectUrl("https://github.com/pingcap/tidb")
    }

    definition {
        cpsScm {
            lightweight(true)
            scriptPath("pipelines/${folder}/${jobName}.groovy")
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
