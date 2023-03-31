// REF: https://<your-jenkins-server>/plugin/job-dsl/api-viewer/index.html
pipelineJob('utf-post-ci') {
    logRotator {
        daysToKeep(30)
    }
    parameters {
        stringParam('REF')
        stringParam('BEFORE')
        stringParam('AFTER')
    
    }
    properties {
        githubProjectUrl("https://github.com/pingcap/automated-tests/")
        pipelineTriggers {
            triggers {
                GenericTrigger {
                    genericVariables {
                        genericVariable {
                            key('REF')
                            value('$.ref')
                            expressionType('JSONPath')
                        }
                        genericVariable {
                            key('BEFORE')
                            value('$.before')
                            expressionType('JSONPath')
                        }
                        genericVariable {
                            key('AFTER')
                            value('$.after')
                            expressionType('JSONPath')
                        }
                    }
                    causeString('Triggered on $REF $BEFORE $AFTER')
                    printContributedVariables(false)
                    printPostContent(true)
                    silentResponse(false)
                    regexpFilterText('$REF')
                    regexpFilterExpression('^(refs/heads/master)$')
                }
            }
        }
    }
    definition {
        cpsScm {
            lightweight(true)
            scriptPath("jenkins/pipelines/qa/utf/utf-post-ci.groovy")
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
