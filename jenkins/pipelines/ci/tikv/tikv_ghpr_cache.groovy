env.DOCKER_HOST = "tcp://localhost:2375"
env.DOCKER_REGISTRY = "docker.io"

stage("Build") {
    node("delivery") {
        println "[Debug Info] Debug command: kubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"

        def checkAndBuild = { ghBranch, content, tag, args, allowStaleCache ->
            container("delivery"){

                    // 2 means no image, 1 means stale, 0 means uptodate.
                    def cacheState = 2
                    if (!params.skip_sha_check) {
                        cacheState = sh(label: "Check if ${ghBranch}:${tag} can be skipped", returnStatus: true, script: """
                        docker pull hub.pingcap.net/jenkins/tikv-cached-${ghBranch}:${tag} || exit 2
                        docker run -i --rm hub.pingcap.net/jenkins/tikv-cached-${ghBranch}:${tag} <<EOF
cd tikv-src
last_hash=\\`git rev-parse origin/${ghBranch}\\`
git fetch origin ${ghBranch}:refs/remotes/origin/${ghBranch}
[[ \\`git rev-parse origin/${ghBranch}\\` == "\\\${last_hash}" ]]
EOF""")
                    }
                    // If there is no such image, always rebuild. Otherwise rebuild if we want latest caches.
                    if (cacheState == 0 || (cacheState == 1 && allowStaleCache)) {
                        println "Skip build with cacheState ${cacheState}"
                        return
                    }
                    dir("docker_build") {
                        deleteDir()
                        sh """
                        cat >Dockerfile <<EOF
${content}
EOF
    """
                        sh"""
                        docker build -t hub.pingcap.net/jenkins/tikv-cached-${ghBranch}:${tag} ${args}  .
                        """
                    }

                docker.withRegistry("https://hub.pingcap.net", "harbor-pingcap") {
                    sh """
                    docker push hub.pingcap.net/jenkins/tikv-cached-${ghBranch}:${tag}
                    """
                }
            }
        }
        def build_branch = { ghBranch ->
            def now = new Date()
            def args = "--build-arg BUILD_DATE=${now.format("yyMMdd")}"
            // Only generate base image at the beginning of every month.
            checkAndBuild(ghBranch, """
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

RUN rustup update stable && rustup default stable \
    && cargo install cargo-nextest \
    && cargo install cargo-sort

RUN cd tikv-src \
    && git fetch origin ${ghBranch}:refs/remotes/origin/${ghBranch} \
    && git checkout origin/${ghBranch} \
    && rustup component add rustfmt clippy llvm-tools-preview \
    && cargo fetch

ENV CARGO_INCREMENTAL=0

ARG BUILD_DATE

# Cache for daily build
RUN cd tikv-src \
    && git fetch origin ${ghBranch}:refs/remotes/origin/${ghBranch} \
    && git checkout origin/${ghBranch} \
    && echo using gcc 8 \
    && source /opt/rh/devtoolset-8/enable \
    && env EXTRA_CARGO_ARGS="--no-run" RUSTFLAGS=-Dwarnings FAIL_POINT=1 ROCKSDB_SYS_SSE=1 RUST_BACKTRACE=1 make dev
""", "base", args, !params.force_base && now.getDate() != 1)

            checkAndBuild(ghBranch, """
FROM hub.pingcap.net/jenkins/tikv-cached-${ghBranch}:base

MAINTAINER Jay Lee <jay@pingcap.com>

ARG BUILD_DATE

RUN cd tikv-src \
    && git fetch origin ${ghBranch}:refs/remotes/origin/${ghBranch} \
    && git checkout origin/${ghBranch} \
    && echo using gcc 8 \
    && source /opt/rh/devtoolset-8/enable \
    && env EXTRA_CARGO_ARGS="--no-run" RUSTFLAGS=-Dwarnings FAIL_POINT=1 ROCKSDB_SYS_SSE=1 RUST_BACKTRACE=1 make dev
""", "latest", args, false)
        }

        build_branch("release-8.1")
        build_branch("release-7.5")
        build_branch("release-7.1")
        build_branch("release-6.5")
        build_branch("release-6.1")
        build_branch("release-5.4")
    }
}
