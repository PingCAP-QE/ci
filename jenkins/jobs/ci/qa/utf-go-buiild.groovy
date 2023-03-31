// REF: https://<your-jenkins-server>/plugin/job-dsl/api-viewer/index.html
pipelineJob('utf-go-build') {
    logRotator {
        daysToKeep(30)
    }
    parameters {
        stringParam('FORK', 'PingCAP-QE')
        stringParam('BRANCH', 'master')
        stringParam('REFSPEC', '+refs/heads/*:refs/remotes/origin/* +refs/pull/*/head:refs/remotes/origin/pr/*')
        stringParam('TAG')
        choiceParam(
            parameterName: 'SUITE',
            options: [
                'ticdc',
                'transaction',
                'gcworker',
                'readpool',
                'rowformat',
                'clustered_index',
                'regression',
                'upgrade',
                'temporary_table',
                'mysqltest',
                'spm',
                'charset_gbk',
                'column_type_change',
                'ext-stats',
                'planner_oncall_test',
                'table-partition'
            ],
            description: 'Select a test suite to run'
        )
    }
    properties {
        pipelineTriggers {
            triggers {
                parameterizedCron {
                    // follow convention of cron, schedule with name=value pairs at the end of each line.
                    parameterizedSpecification("0 22 * * * %SUITE=charset_gbk")
                    parameterizedSpecification("5 22 * * * %SUITE=mysqltest")
                    parameterizedSpecification("10 22 * * * %SUITE=column_type_change")
                    parameterizedSpecification("15 22 * * * %SUITE=ext-stats")
                    parameterizedSpecification("20 22 * * * %SUITE=planner_oncall_test")
                    parameterizedSpecification("25 22 * * * %SUITE=table-partition")
                    parameterizedSpecification("30 22 * * * %SUITE=regression")
                }
            }
        }
    }
    definition {
        cpsScm {
            lightweight(true)
            scriptPath("jenkins/pipelines/qa/utf/utf-go-build.groovy")
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
