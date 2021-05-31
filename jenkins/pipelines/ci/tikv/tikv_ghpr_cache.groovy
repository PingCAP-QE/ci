stage("Build") {
    node("cache_tikv") {
        println "[Debug Info] Debug command: kubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"

        sh label: 'Prepare workspace', script: """
            curl https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/centos7_golang-1.13_rust_cached_v2 > Dockerfile
            sha=`curl https://api.github.com/repos/tikv/tikv/branches/${ghBranch} | jq -r ".commit.sha"`
            sudo docker build -t rust-cached-${ghBranch}:latest --build-arg SHA=\$sha .
            sudo docker push rust-cached-${ghBranch}:latest
        """
    }
}
