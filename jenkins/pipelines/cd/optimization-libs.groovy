def checkIfFileCacheExists(build_para, product, hash, binary) {
    def release_tag = build_para["RELEASE_TAG"]
    def platform = build_para["PLATFORM"]
    def os = build_para["OS"]
    def arch = build_para["ARCH"]
    def FILE_SERVER_URL = build_para["FILE_SERVER_URL"]

    if (!fileExists("gethash.py")) {
        sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"
    }

    def filepath = "builds/pingcap/${product}/optimization/${release_tag}/${hash}/${platform}/${binary}-${os}-${arch}.tar.gz"

    def result = sh(script: "curl -I ${FILE_SERVER_URL}/download/${filepath} -X \"HEAD\"|grep \"200 OK\"", returnStatus: true)
    // result equal 0 mean cache file exists
    if (result == 0) {
        echo "file ${FILE_SERVER_URL}/download/${filepath} found in cache server,skip build again"
        return true
    }
    return false
}

def inner_build_upload(build_para, product, hash, binary) {
    def release_tag = build_para["RELEASE_TAG"]
    def platform = build_para["PLATFORM"]
    def os = build_para["OS"]
    def arch = build_para["ARCH"]
    def FILE_SERVER_URL = build_para["FILE_SERVER_URL"]

    if (!build_para["FORCE_REBUILD"] && checkIfFileCacheExists(build_para,product, hash, binary)) {
        return
    }
    def repo = "git@github.com:pingcap/${product}.git"
    if (release_tag >= "v5.2.0" && product == "br") {
        repo = "git@github.com:pingcap/tidb.git"
    }
    if (release_tag >= "v5.3.0" && product == "dumpling") {
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
        def filepath = "builds/pingcap/${product}/optimization/${release_tag}/${hash}/${platform}/${binary}-${os}-${arch}.tar.gz"
        if (product == "br") {
            filepath = "builds/pingcap/${product}/optimization/${release_tag}/${hash}/${platform}/${binary}-${os}-${arch}.tar.gz"
        }
        def target = "${product}-${release_tag}-${os}-${arch}"
        if (product == "ticdc") {
            target = "${product}-${os}-${arch}"
            filepath = "builds/pingcap/${product}/optimization/${release_tag}/${hash}/${platform}/${product}-${os}-${arch}.tar.gz"
        }
        if (product == "dumpling") {
            filepath = "builds/pingcap/${product}/optimization/${release_tag}/${hash}/${platform}/${product}-${os}-${arch}.tar.gz"
        }
        if (product == "ng-monitoring") {
            filepath = "builds/pingcap/${product}/optimization/${release_tag}/${hash}/${platform}/${binary}-${os}-${arch}.tar.gz"
        }
        if (product == "tidb-ctl") {
            sh """
            export PATH=/usr/local/opt/binutils/bin:/usr/local/bin:/Users/pingcap/.cargo/bin:/opt/homebrew/bin:/opt/homebrew/sbin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:${GO_BIN_PATH}
            rm -rf ${target}
            mkdir -p ${target}/bin
            go build -o ${target}/bin/${product}         
            """
        }

        if (product in ["tidb", "tidb-binlog", "pd"]) {
            sh """
            for a in \$(git tag --contains ${hash}); do echo \$a && git tag -d \$a;done
            git tag -f ${release_tag} ${hash}
            git branch -D refs/tags/${release_tag} || true
            git checkout -b refs/tags/${release_tag}
            export PATH=/usr/local/opt/binutils/bin:/usr/local/bin:/Users/pingcap/.cargo/bin:/opt/homebrew/bin:/opt/homebrew/sbin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:${GO_BIN_PATH}
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
            cp bin/* ${target}/bin
            """
        }
        if (product in ["tidb-tools", "ticdc", "br"]) {
            sh """
            for a in \$(git tag --contains ${hash}); do echo \$a && git tag -d \$a;done
            git tag -f ${release_tag} ${hash}
            git branch -D refs/tags/${release_tag} || true
            git checkout -b refs/tags/${release_tag}
            export PATH=/usr/local/opt/binutils/bin:/usr/local/bin:/Users/pingcap/.cargo/bin:/opt/homebrew/bin:/opt/homebrew/sbin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:${GO_BIN_PATH}
            if [ ${product} = "tidb-tools" ]; then
                make clean;
            fi;  
            if [ $release_tag \\> "v5.2.0" ] || [ $release_tag == "v5.2.0" ] && [ $product == "br" ]; then
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
            git tag -f ${release_tag} ${hash}
            git branch -D refs/tags/${release_tag} || true
            git checkout -b refs/tags/${release_tag}
            export PATH=/usr/local/opt/binutils/bin:/usr/local/bin:/Users/pingcap/.cargo/bin:/opt/homebrew/bin:/opt/homebrew/sbin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:${GO_BIN_PATH}
            
            if [ $release_tag \\> "v5.3.0" ] || [ $release_tag == "v5.3.0" ]; then
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
            git tag -f ${release_tag} ${hash}
            git branch -D refs/tags/${release_tag} || true
            git checkout -b refs/tags/${release_tag}
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

def build_upload(build_para, product, hash, binary) {
    def container_label = build_para["CONTAINER_LABEL"]

    stage("Build ${product}") {
        node(build_para["NODE_LABEL"]) {
            if (container_label != "") {
                container(container_label){
                    inner_build_upload(build_para, product, hash, binary)
                }
            } else {
                inner_build_upload(build_para, product, hash, binary)
            }

        }
    }
}

def create_builds(build_para) {
    builds = [:]

    builds["Build tidb-ctl"] = {
        build_product(build_para, "tidb-ctl")
    }
    builds["Build tidb"] = {
        build_product(build_para, "tidb")
    }
    builds["Build tidb-binlog"] = {
        build_product(build_para, "tidb-binlog")
    }
    builds["Build tidb-tools"] = {
        build_product(build_para, "tidb-tools")
    }
    builds["Build pd"] = {
        build_product(build_para, "pd")
    }
    builds["Build ticdc"] = {
        build_product(build_para, "ticdc")
    }
    builds["Build br"] = {
        build_product(build_para, "br")
    }
    builds["Build dumpling"] = {
        build_product(build_para, "dumpling")
    }
    if (release_tag >= "v5.3.0") {
        builds["Build NGMonitoring"] = {
            build_product(build_para, "ng-monitoring")
        }
    }

    return builds
}

def build_product(build_para, product) {
    def arch = build_para["ARCH"]
    def os = build_para["OS"]
    def release_tag = build_para["RELEASE_TAG"]
    def sha1 = build_para[product]
    def git_pr = build_para["GIT_PR"]
    def force_rebuild = build_para["FORCE_REBUILD"]
    def repo = "git@github.com:pingcap/${product}.git"

    if (release_tag >= "v5.2.0" && product == "br") {
        repo = "git@github.com:pingcap/tidb.git"
    }
    if (release_tag >= "v5.3.0" && product == "dumpling") {
        repo = "git@github.com:pingcap/tidb.git"
    }
    if (product == "ticdc") {
        repo = "git@github.com:pingcap/tiflow.git"
    }

    def filepath = "builds/pingcap/${product}/optimization/${release_tag}/${hash}/${platform}/${binary}-${os}-${arch}.tar.gz"
    def paramsBuild = [
        string(name: "ARCH", value: arch),
        string(name: "OS", value: os),
        string(name: "EDITION", value: "community"),
        string(name: "OUTPUT_BINARY", value: filepath),
        string(name: "REPO", value: repo),
        string(name: "PRODUCT", value: product),
        string(name: "GIT_HASH", value: sha1),
        string(name: "RELEASE_TAG", value: release_tag),
        [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: force_rebuild],
    ]

    if (git_pr != "" && repo == "tikv") {
        paramsBuild.push([$class: 'StringParameterValue', name: 'GIT_PR', value: git_pr])
    }
    build job: "build-common",
            wait: true,
            parameters: paramsBuild
}

return this