// REF: https://<your-jenkins-server>/plugin/job-dsl/api-viewer/index.html
pipelineJob('pingcap/tidb/merged_common_test') {
    disabled(true)
    logRotator {
        daysToKeep(30)
    }
    parameters {
        stringParam("ACTION")
        stringParam("PULL_NUMBER")
        stringParam("MERGED")
        stringParam("GIT_BASE_BRANCH")
        stringParam("GIT_MERGE_COMMIT")
    }
    properties {
        // priority(0) // 0 fast than 1
        githubProjectUrl("https://github.com/pingcap/tidb")
        triggers {
            genericTrigger {
                genericVariables {
                    genericVariable {
                        key("ACTION")
                        value("\$.action")
                        expressionType("JSONPath") //Optional, defaults to JSONPath
                        regexpFilter("") //Optional, defaults to empty string
                        defaultValue("") //Optional, defaults to empty string
                    }
                    genericVariable {
                        key("PULL_NUMBER")
                        value("\$.number")
                        expressionType("JSONPath") 
                        regexpFilter("") 
                        defaultValue("") 
                    }
                    genericVariable {
                        key("GIT_BASE_BRANCH")
                        value("\$.pull_request.base.ref")
                        expressionType("JSONPath") 
                        regexpFilter("") 
                        defaultValue("")
                    }
                    genericVariable {
                        key("GIT_MERGE_COMMIT")
                        value("\$.pull_request.merge_commit_sha")
                        expressionType("JSONPath") 
                        regexpFilter("")
                        defaultValue("") 
                    }
                    genericVariable {
                        key("MERGED")
                        value("\$.pull_request.merged")
                        expressionType("JSONPath") 
                        regexpFilter("") 
                        defaultValue("") 
                    }
                }
                causeString('$ACTION  $MERGED $PULL_NUMBER  $GIT_BASE_BRANCH $GIT_MERGE_COMMIT')
                // token()
                // tokenCredentialId()
                printContributedVariables(true)
                printPostContent(true)
                silentResponse(false)
                shouldNotFlattern(false)
                regexpFilterText("\${ACTION}_\${MERGED}_\${GIT_BASE_BRANCH}_\${GIT_MERGE_COMMIT}")
                regexpFilterExpression("^closed_true_master_[a-z0-9]{40}\$")
            }
        }
    }
 
    definition {
        cpsScm {
            lightweight(true)
            scriptPath('pipelines/pingcap/tidb/latest/merged_common_test.groovy')
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
