// REF: https://<your-jenkins-server>/plugin/job-dsl/api-viewer/index.html
pipelineJob('utf-py-build') {
    logRotator {
        daysToKeep(30)
    }
    parameters {
        stringParam("FORK", defaultValue="PingCAP-QE")
        stringParam("BRANCH", defaultValue="master")
        stringParam("REFSPEC", defaultValue="+refs/heads/*:refs/remotes/origin/* +refs/pull/*/head:refs/remotes/origin/pr/*")
        stringParam("TAG")
    }
    properties {
        pipelineTriggers {
            triggers {
                cron("0 23 * * *")
            }
        }
    }
    definition {
        cpsScm {
            lightweight(true)
            scriptPath("jenkins/pipelines/qa/utf/utf-py-build.groovy")
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