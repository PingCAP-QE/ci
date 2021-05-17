def branch = (env.TAG_NAME==null) ? "${env.BRANCH_NAME}" : "refs/tags/${env.TAG_NAME}"


task = "build_dbaas_image"
def build_path = 'go/src/github.com/pingcap/dbaas'

podTemplate(name: task, label: task, instanceCap: 5, idleMinutes: 1440, containers: [
        containerTemplate(name: 'docker', image: 'hub.pingcap.net/pingcap/tidb-builder:awscli-cached-v7', privileged: true,
            envVars: [
                envVar(key: 'DOCKER_HOST', value: 'tcp://localhost:2375'),
        ], ttyEnabled: true, command: 'dockerd --host=unix:///var/run/docker.sock --host=tcp://0.0.0.0:2375'),
]){
    try{
        node(task){
            def ws = pwd()
            stage("checkout"){
                println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} -c docker bash\nworkspace path:\n ${ws}"
                // workaround, since jenkins can't delete output/bin and  .make

                container("docker"){
                sh """
                sudo rm -rf ${build_path}
                """
                dir(build_path) {
                    deleteDir()
                    // 如果不是 TAG，直接传 branch 给下面的 checkout 语句； 否则就应该 checkout 到 refs/tags 下 .
                    // 值得注意的是，即使传入的是 TAG，环境变量里的 BRANCH_NAME 和 TAG_NAME 同时会是 TAG 名，如 v3.0.0
                    println branch
                    retry(3) {
                        checkout scm: [$class: 'GitSCM', 
                            branches: [[name: branch]],  
                            extensions: [[$class: 'LocalBranch'],[$class: 'CloneOption', depth: 1, noTags: false, reference: '', shallow: true]],
                            userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', url: 'git@github.com:pingcap/dbaas.git']]]
                    }
                }
            }
            }
            stage("build and push to ECR"){
                container("docker"){
                    dir(build_path) {
                    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'dbass-staging-aws-key', usernameVariable: 'AWS_ACCESS_KEY_ID', passwordVariable: 'AWS_SECRET_ACCESS_KEY']]) {

                        sh"""
                        mkdir -p \$GOPATH/pkg/mod && mkdir -p ${ws}/go/pkg && ln -sf \$GOPATH/pkg/mod ${ws}/go/pkg/mod
                        export AWS_DEFAULT_REGION=us-west-2
                        set -e
                        SKIP_GCR=y GOPATH=${ws}/go project=trial region=us-west-2 ./build.sh 
                        """

                    } 
                    }
               }
            }
        }
    } catch (Exception e) {
        currentBuild.result = "FAILURE"
        slackcolor = 'danger'
        echo "${e}"
    }
}