// REF: https://<your-jenkins-server>/plugin/job-dsl/api-viewer/index.html
pipelineJob('utf-affect-update') {
    logRotator {
        daysToKeep(30)
    }
    parameters {
        stringParam("FORK", defaultValue="pingcap")
        stringParam("BRANCH", defaultValue="main")
        stringParam("REFSPEC", defaultValue="+refs/heads/*:refs/remotes/origin/* +refs/pull/*/head:refs/remotes/origin/pr/*")
        stringParam("TRIGGERTYPE", defaultValue="version", description="version (tibug affect version) or branch (issue affect branch )")
        stringParam("SYNCTYPE", defaultValue="lark", description="lark or tibug or github")
        stringParam("TRIGGERID")
    
    }
    properties {
        pipelineTriggers {
            triggers {
                parameterizedCron {
                    // follow convention of cron, schedule with name=value pairs at the end of each line.
                    parameterizedSpecification("0 23 * * 7 %SYNCTYPE=lark;FORK=pingcap;BRANCH=main;REFSPEC=+refs/heads/*:refs/remotes/origin/* +refs/pull/*/head:refs/remotes/origin/pr/*;TRIGGERTYPE=version")
                }
            }
        }
    }
    definition {
        cpsScm {
            lightweight(true)
            scriptPath("jenkins/pipelines/qa/utf/utf-affect-update.groovy")
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