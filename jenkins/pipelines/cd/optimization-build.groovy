def build_upload = { product, hash, binary ->
    stage("Build ${product}") {
        node("mac") {
            if (checkIfFileCacheExists(product, hash, binary)) {
                return
            }
            def repo = "git@github.com:pingcap/${product}.git"
            if (RELEASE_TAG >= "v5.2.0" && product == "br") {
                repo = "git@github.com:pingcap/tidb.git"
            }
            if (RELEASE_TAG >= "v5.3.0" && product == "dumpling") {
                repo = "git@github.com:pingcap/tidb.git"
            }
            if (product == "ticdc") {
                repo = "git@github.com:pingcap/tiflow.git"
            }
            def workspace = WORKSPACE
            dir("${workspace}/go/src/github.com/pingcap/${product}") {
                deleteDir()
                try {
                    checkout changelog: false, poll: true,
                            scm: [$class: 'GitSCM', branches: [[name: "${hash}"]], doGenerateSubmoduleConfigurations: false,
                                  extensions: [[$class: 'CheckoutOption', timeout: 30],
                                               [$class: 'CloneOption', timeout: 600],
                                               [$class: 'PruneStaleBranch'],
                                               [$class: 'CleanBeforeCheckout']], submoduleCfg: [],
                                  userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh',
                                                       refspec      : '+refs/heads/*:refs/remotes/origin/*',
                                                       url          : "${repo}"]]]
                } catch (info) {
                    retry(10) {
                        echo "checkout failed, retry..."
                        sleep 5
                        if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                            deleteDir()
                        }
                        checkout changelog: false, poll: true,
                                scm: [$class: 'GitSCM', branches: [[name: "${hash}"]], doGenerateSubmoduleConfigurations: false,
                                      extensions: [[$class: 'CheckoutOption', timeout: 30],
                                                   [$class: 'CloneOption', timeout: 60],
                                                   [$class: 'PruneStaleBranch'],
                                                   [$class: 'CleanBeforeCheckout']], submoduleCfg: [],
                                      userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh',
                                                           refspec      : '+refs/heads/*:refs/remotes/origin/*',
                                                           url          : "${repo}"]]]
                    }
                }
                if (product == "tidb-ctl") {
                    hash = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
                }
                def filepath = "builds/pingcap/${product}/optimization/${RELEASE_TAG}/${hash}/darwin/${binary}.tar.gz"
                if (product == "br") {
                    filepath = "builds/pingcap/${product}/optimization/${RELEASE_TAG}/${hash}/darwin/${binary}.tar.gz"
                }
                def target = "${product}-${RELEASE_TAG}-${os}-${arch}"
                if (product == "ticdc") {
                    target = "${product}-${os}-${arch}"
                    filepath = "builds/pingcap/${product}/optimization/${RELEASE_TAG}/${hash}/darwin/${product}-${os}-${arch}.tar.gz"
                }
                if (product == "dumpling") {
                    filepath = "builds/pingcap/${product}/optimization/${RELEASE_TAG}/${hash}/darwin/${product}-${os}-${arch}.tar.gz"
                }
                if (product == "ng-monitoring") {
                    filepath = "builds/pingcap/${product}/optimization/${RELEASE_TAG}/${hash}/${platform}/${binary}-${os}-${arch}.tar.gz"
                }
                if (product == "tidb-ctl") {
                    sh """
                    export GOPATH=/Users/pingcap/gopkg
                    export PATH=/Users/pingcap/.cargo/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/Users/pingcap/.cargo/bin:${GO_BIN_PATH}
                    go build -o /Users/pingcap/binarys/${product}
                    rm -rf ${target}
                    mkdir -p ${target}/bin
                    cp /Users/pingcap/binarys/${product} ${target}/bin/            
                    """
                }

                if (product in ["tidb", "tidb-binlog", "pd"]) {
                    sh """
                    for a in \$(git tag --contains ${hash}); do echo \$a && git tag -d \$a;done
                    git tag -f ${RELEASE_TAG} ${hash}
                    git branch -D refs/tags/${RELEASE_TAG} || true
                    git checkout -b refs/tags/${RELEASE_TAG}
                    export GOPATH=/Users/pingcap/gopkg
                    export PATH=/Users/pingcap/.cargo/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/Users/pingcap/.cargo/bin:${GO_BIN_PATH}
                    if [ ${product} != "pd" ]; then
                        make clean
                    fi;
                    git checkout .
                    make
                    if [ ${product} = "pd" ]; then
                        make tools;
                    fi;
                    rm -rf ${target}
                    mkdir -p ${target}/bin
                    if [ ${product} = "tidb" ]; then
                        cp /Users/pingcap/binarys/tidb-ctl ${target}/bin/
                    fi;
                    cp bin/* ${target}/bin
                    """
                }
                if (product in ["tidb-tools","ticdc","br"]) {
                    sh """
                    for a in \$(git tag --contains ${hash}); do echo \$a && git tag -d \$a;done
                    git tag -f ${RELEASE_TAG} ${hash}
                    git branch -D refs/tags/${RELEASE_TAG} || true
                    git checkout -b refs/tags/${RELEASE_TAG}
                    export GOPATH=/Users/pingcap/gopkg
                    export PATH=/Users/pingcap/.cargo/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/Users/pingcap/.cargo/bin:${GO_BIN_PATH}
                    if [[ ${product} = "tidb-tools" ]]; then
                        make clean;
                    fi;  
                    if [ $RELEASE_TAG \\> "v5.2.0" ] || [ $RELEASE_TAG == "v5.2.0" ] && [ $product == "br" ]; then
                        make build_tools
                    else
                        make build
                    fi;
                    rm -rf ${target}
                    mkdir -p ${target}/bin
                    mv bin/* ${target}/bin/
                    """
                }

                if (product in ["dumpling"]) {
                    sh """
                    for a in \$(git tag --contains ${hash}); do echo \$a && git tag -d \$a;done
                    git tag -f ${RELEASE_TAG} ${hash}
                    git branch -D refs/tags/${RELEASE_TAG} || true
                    git checkout -b refs/tags/${RELEASE_TAG}
                    export GOPATH=/Users/pingcap/gopkg
                    export PATH=/Users/pingcap/.cargo/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/Users/pingcap/.cargo/bin:${GO_BIN_PATH}

                    if [ $RELEASE_TAG \\> "v5.3.0" ] || [ $RELEASE_TAG == "v5.3.0" ] ; then
                        make build_dumpling
                    else
                        make build
                    fi;
                    rm -rf ${target}
                    mkdir -p ${target}/bin
                    mv bin/* ${target}/bin/
                    """
                }
                if (product in ["ng-monitoring"]) {
                    sh """
                    for a in \$(git tag --contains ${hash}); do echo \$a && git tag -d \$a;done
                    git tag -f ${RELEASE_TAG} ${hash}
                    git branch -D refs/tags/${RELEASE_TAG} || true
                    git checkout -b refs/tags/${RELEASE_TAG}
                    export GOPATH=/Users/pingcap/gopkg
                    export PATH=/Users/pingcap/.cargo/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/Users/pingcap/.cargo/bin:${GO_BIN_PATH}
                    make
                    rm -rf ${target}
                    mkdir -p ${target}/bin
                    mv bin/* ${target}/bin/
                    """
                }
                sh """
                    tar czvf ${target}.tar.gz ${target}
                    curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
                """
            }
        }
    }
}