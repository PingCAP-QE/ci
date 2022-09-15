/*
 Example usage:

    pipeline {
        agent any
        stages {
            stage('Init') {
                agent { label 'master' }
                steps {
                    script {
                        cancelPreviousBuilds()
                    }
                }  
            }
            stage('Test') {
                agent { label 'agent' }
                steps {
                //...
                }
            }
        }
    }

  Ref: https://gist.github.com/sturman/ce445c13a741fb3a0d4c0bf9bb940580
*/
@NonCPS
def cancelPreviousBuilds() {
    def jobName = env.JOB_NAME
    def buildNumber = env.BUILD_NUMBER.toInteger()
    def ghPrId = env.ghprbPullId.toInteger()

    // Get job name.
    def currentJob = Jenkins.instance.getItemByFullName(jobName)

    // Iterating over the builds for specific job.
    for (def build : currentJob.builds) {
        def listener = build.getListener()
        def ghprbPullId = build.getEnvironment(listener).get('ghprbPullId').toInteger()

        // If there is a build that is currently running and it's not current build.
        if (ghprbPullId == ghPrId && build.isBuilding() && build.number.toInteger() < buildNumber) {
            def exec = build.getExecutor()
            if (exec != null) {
                // Then stop it.
                exec.interrupt(
                        Result.ABORTED,
                        new CauseOfInterruption.UserInterruption("Aborted by #${currentBuild.number}")
                    )
                println("Aborted previously running build #${build.number}")
            }
        }
    }
}
