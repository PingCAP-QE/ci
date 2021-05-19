def BUILD_URL = 'git@github.com:tikv/tikv.git'
def slackcolor = 'good'
def githash

try {
    stage('test_coverage') {
        node("test_rust") {
            container("rust") {
                def ws = pwd()
                deleteDir()
                dir("/home/jenkins/agent/git/tikv") {
                    git credentialsId: 'github-sre-bot-ssh', url: "${BUILD_URL}", branch: "master"
                    githash = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
                }
                timeout(180) {
                    dir("tikv") {
                        sh """
                        sudo yum install -y openssl
                        sudo yum install -y openssl-devel
                        sudo yum install -y curl
                        sudo yum install -y zip
                        
                        rustup override unset
                        cd /home/jenkins
                        cd ${ws}/tikv
                        export CI_BUILD_REF=${githash}
                        export CI_BRANCH=master
                        sudo sysctl -w net.ipv4.ip_local_port_range='20000 30000'
                        sudo rm -rf ./*
                        cp -R /home/jenkins/agent/git/tikv/. ./
                        make clean

                        grpcio_ver=`grep -A 1 'name = "grpcio"' Cargo.lock | tail -n 1 | cut -d '"' -f 2`
                        if [[ ! "0.8.0" > "\$grpcio_ver" ]]; then
                            echo using gcc 8
                            source /opt/rh/devtoolset-8/enable
                        fi

                        cargo install grcov
                        
                        export CARGO_INCREMENTAL=0
                        export RUSTFLAGS="-Zprofile -Ccodegen-units=1 -Cinline-threshold=0 -Clink-dead-code -Coverflow-checks=off"
                        export RUST_TEST_THREADS=1
                        EXTRA_CARGO_ARGS="--verbose --no-fail-fast" make test || true
                        
                        zip -0 ccov.zip \$(find . \\( -name "*.gc*" \\) -print)
                        """

                        withCredentials([string(credentialsId: 'codecov-token-tikv', variable: 'CODECOV_TOKEN')]) {
                            sh """
                            set +x
                             export CODECOV_TOKEN=${CODECOV_TOKEN}
                            set -x
                             grcov ccov.zip -s . -t lcov --llvm  --ignore-not-existing --ignore "target/*" --ignore "/*" -o lcov.info
                             curl -s https://codecov.io/bash | bash -s -- -f lcov.info
                            """
                        }

                    }
                }
            }
            deleteDir()
        }
    }

    currentBuild.result = "SUCCESS"
} catch (Exception e) {
    currentBuild.result = "FAILURE"
    slackcolor = 'danger'
    echo "${e}"
}

stage('Summary') {
    echo "Send slack here ..."
    //slackSend channel: "", color: "${slackcolor}", teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
}
