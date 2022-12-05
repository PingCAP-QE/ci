// REF: https://<your-jenkins-server>/plugin/job-dsl/api-viewer/index.html
pipelineJob('pingcap/tidb/tidb_merged_integration_cdc_test') {
    logRotator {
        daysToKeep(30)
    }
    parameters {
        stringParam("ACTION")
        stringParam("PULL_NUMBER")
        stringParam("MERGED")
        stringParam("GIT_BRANCH")
        stringParam("GIT_COMMIT")
    }
    properties {
        // priority(0) // 0 fast than 1
        githubProjectUrl("https://github.com/pingcap/tidb")
        pipelineTriggers {
            triggers {
                githubPush()
            }
        }
        triggers {
            genericTrigger {
                genericVariables {
                    genericVariable {
                        key("ACTION")
                        value("\$.action")
                        expressionType("JSONPath") //Optional, defaults to JSONPath
                        regexpFilter("") //Optional, defaults to empty string
                        defaultValue("") //Optional, defaults to empty string
                    },
                    genericVariable {
                        key("PULL_NUMBER")
                        value("\$.number")
                        expressionType("JSONPath") 
                        regexpFilter("") 
                        defaultValue("") 
                    },
                    genericVariable {
                        key("GIT_BRANCH")
                        value("\$.pull_request.base.ref")
                        expressionType("JSONPath") 
                        regexpFilter("") 
                        defaultValue("")
                    },
                    genericVariable {
                        key("GIT_COMMIT")
                        value("\$.pull_request.merge_commit_sha")
                        expressionType("JSONPath") 
                        regexpFilter("")
                        defaultValue("") 
                    },
                    genericVariable {
                        key("MERGED")
                        value("\$.pull_request.merged")
                        expressionType("JSONPath") 
                        regexpFilter("") 
                        defaultValue("") 
                    }
                }
                causeString('$ACTION  $MERGED $PULL_NUMBER  $GIT_BRANCH $GIT_COMMIT')
                token()
                tokenCredentialId()
                printContributedVariables(true)
                printPostContent(true)
                silentResponse(false)
                shouldNotFlattern(false)
                regexpFilterText("\${ACTION}_\${MERGED}_\${GIT_BRANCH}_\${GIT_COMMIT}")
                regexpFilterExpression("^closed_true_master_[a-z0-9]{40}\$")
            }
        }
    }
 
    definition {
        cpsScm {
            lightweight(true)
            scriptPath('staging/pipelines/pingcap/tidb/latest/merged_integration_cdc_test.groovy')
            scm {
                github('PingCAP-QE/ci', 'main')
            }
        }
    }
}
