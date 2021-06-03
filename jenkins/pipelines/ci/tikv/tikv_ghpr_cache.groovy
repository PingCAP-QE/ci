stage("Build") {
    node("cache_tikv") {
        println "[Debug Info] Debug command: kubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"

        deleteDir()
        checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'https://github.com/PingCAP-QE/ci.git']]]

        def build_image = { name, content, tag, args ->
            withDockerServer([uri: "${env.DOCKER_HOST}"]) {
                dir("docker_build") {
                    deleteDir()
                    sh """
cat >Dockerfile <<EOF
${content}
EOF
"""
                    docker.build("${name}:${tag}", "${args} .").push()
                }
            }
        }
        def build_branch = { ghBranch ->
            def now = new Date()
            def args = "--build-arg BUILD_DATE=${now.format("yyMMdd")}"
            if (params.force_base || now.day() == 0) {
                def args = "--build-arg BRANCH=${ghBranch} ${args}"
                build_image("tikv-cached-${ghBranch}", """
FROM hub.pingcap.net/jenkins/centos7_golang-1.13_rust

MAINTAINER Jay Lee <jay@pingcap.com>

RUN mkdir tikv-target tikv-git

ENV PATH=\\\$PATH:\\\$HOME/.cargo/bin

RUN mkdir tikv-src && ln -s \\\$HOME/tikv-target \\\$HOME/tikv-src/target && ln -s \\\$HOME/tikv-git \\\$HOME/tikv-src/.git

RUN cd tikv-src \
        && git init \
        && git remote add origin https://github.com/tikv/tikv.git \
        && git fetch origin

# TODO: This should be configured by base image.
RUN rustup set profile minimal

RUN cd tikv-src \
        && git fetch origin ${ghBranch} \
        && git checkout origin/${ghBranch} \
        && rustup component add rustfmt clippy \
        && cargo fetch

ENV CARGO_INCREMENTAL=0

ARG BUILD_DATE

# Cache for daily build
RUN cd tikv-src \
        && git fetch origin ${ghBranch} \
        && git checkout origin/${ghBranch} \
        && grpcio_ver=`grep -A 1 'name = "grpcio"' Cargo.lock | tail -n 1 | cut -d '"' -f 2` \
        && if [[ ! "0.8.0" > "\\\$grpcio_ver" ]]; then echo using gcc 8; source /opt/rh/devtoolset-8/enable; fi \
        && env EXTRA_CARGO_ARGS="--no-run" RUSTFLAGS=-Dwarnings FAIL_POINT=1 ROCKSDB_SYS_SSE=1 RUST_BACKTRACE=1 make dev
""", "base", args)
            }
            
            build_image("tikv-cached-${ghBranch}", """
FROM hub.pingcap.net/jenkins/tikv-cached-${ghBranch}:base

MAINTAINER Jay Lee <jay@pingcap.com>

ARG BUILD_DATE

RUN cd tikv-src \
        && git fetch origin ${ghBranch} \
        && git checkout origin/${ghBranch} \
        && grpcio_ver=`grep -A 1 'name = "grpcio"' Cargo.lock | tail -n 1 | cut -d '"' -f 2` \
        && if [[ ! "0.8.0" > "\\\$grpcio_ver" ]]; then echo using gcc 8; source /opt/rh/devtoolset-8/enable; fi \
        && env EXTRA_CARGO_ARGS="--no-run" RUSTFLAGS=-Dwarnings FAIL_POINT=1 ROCKSDB_SYS_SSE=1 RUST_BACKTRACE=1 make dev
""", "latest", args)
        }

        build_branch("master")
        build_branch("release-5.0")
        build_branch("release-4.0")
        build_branch("release-3.0")
    }
}
