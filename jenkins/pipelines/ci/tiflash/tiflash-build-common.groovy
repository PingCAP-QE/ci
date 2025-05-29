properties([
        copyArtifactPermission('tiflash-*,tics-*'),
        parameters([
            booleanParam(
                        name: 'BUILD_TIFLASH',
                        defaultValue: true
            ),
            booleanParam(
                        name: 'BUILD_TESTS',
                        defaultValue: true
            ),
            booleanParam(
                        name: 'BUILD_PAGE_TOOLS',
                        defaultValue: false
            ),
            booleanParam(
                        name: 'ENABLE_FAILPOINTS',
                        defaultValue: true
            ),
            booleanParam(
                        name: 'ENABLE_CCACHE',
                        defaultValue: true
            ),
            booleanParam(
                        name: 'ENABLE_PROXY_CACHE',
                        defaultValue: true
            ),
            booleanParam(
                        name: 'UPDATE_CCACHE',
                        defaultValue: true
            ),
            booleanParam(
                        name: 'UPDATE_PROXY_CACHE',
                        defaultValue: true
            ),
            booleanParam(
                        name: 'ENABLE_STATIC_ANALYSIS',
                        defaultValue: true
            ),
            booleanParam(
                        name: 'ENABLE_FORMAT_CHECK',
                        defaultValue: true
            ),
            booleanParam(
                        name: 'ENABLE_COVERAGE',
                        defaultValue: false
            ),
            booleanParam(
                        name: 'PUSH_MESSAGE',
                        defaultValue: false
            ),
            booleanParam(
                        name: 'DEBUG_WITHOUT_DEBUG_INFO',
                        defaultValue: false
            ),
            booleanParam(
                        name: 'ARCHIVE_ARTIFACTS',
                        defaultValue: true
            ),
            booleanParam(
                        name: 'ARCHIVE_BUILD_DATA',
                        defaultValue: false
            ),
            choice(
                        choices: ['Debug', 'RelWithDebInfo', 'Release', 'ASan', 'TSan', 'UBSan'],
                        name: 'CMAKE_BUILD_TYPE'
            ),
            string(
                        defaultValue: 'master',
                        name: 'TARGET_BRANCH',
                        trim: true,
            ),
            string(
                        defaultValue: '',
                        name: 'TARGET_PULL_REQUEST',
                        trim: true,
            ),
            string(
                        defaultValue: '',
                        name: 'TARGET_COMMIT_HASH',
                        trim: true,
            ),
            string(
                        defaultValue: '',
                        name: 'EXTRA_SUFFIX',
                        trim: true,
            ),
            choice(
                        choices: ['arm64', 'amd64'],
                        name: 'ARCH'
            ),
            choice(
                        choices: ['linux', 'darwin'],
                        name: 'OS'
            ),
    ]),
])

def getCMakeBuildType() {
    return params.CMAKE_BUILD_TYPE.toLowerCase()
}

def getToolchain(repo_path) {
    def toolchain = 'llvm'
    dir(repo_path) {
        if (!fileExists("release-centos7-llvm/Makefile")) {
            toolchain = 'legacy'
        }
    }
    return toolchain
}

def getBuildIdentifier(repo_path) {
    def ccache_flag = "-update-ccache"
    def proxy_flag = "-update-proxy"
    if (!params.UPDATE_CCACHE) {
        ccache_flag = ""
    }
    if (!params.UPDATE_PROXY_CACHE) {
        proxy_flag = ""
    }
    return "tiflash-build-${BUILD_NUMBER}-${getToolchain(repo_path)}-${params.ARCH}-${params.OS}${ccache_flag}${proxy_flag}${params.EXTRA_SUFFIX}"
}

def getBuildTarget() {
    def targets = ""

    if (params.BUILD_TIFLASH) {
        targets = "tiflash ${targets}"
    }

    if (params.BUILD_TESTS) {
        targets = "gtests_dbms gtests_libcommon gtests_libdaemon ${targets}"
    }

    if (params.BUILD_PAGE_TOOLS) {
        targets = "page_stress_testing ${targets}"
    }

    if (!targets) {
        error "no target found for current build"
    }

    return targets
}

def getBuildSuffix(repo_path) {
    def failpoints = "-failpoints"
    if (!params.ENABLE_FAILPOINTS) {
        failpoints = ""
    }
    def dbg = "-dbg"
    if (params.DEBUG_WITHOUT_DEBUG_INFO) {
        dbg = ""
    }
    def coverage = ""
    if (params.ENABLE_COVERAGE) {
        coverage = "-cov"
    }
    def artifacts = ""
    if (params.BUILD_TIFLASH) {
        artifacts = "tiflash-${artifacts}"
    }
    if (params.BUILD_TESTS) {
        artifacts = "tests-${artifacts}"
    }
    if (params.BUILD_PAGE_TOOLS) {
        artifacts = "pagetools-${artifacts}"
    }
    def branch = "master"
    if (params.TARGET_BRANCH) {
        branch = params.TARGET_BRANCH
    }
    if (!artifacts) {
        error "no artifact found for current build"
    }
    return "${artifacts}${params.ARCH}-${params.OS}-${getToolchain(repo_path)}-${getCMakeBuildType()}-${branch}${coverage}${dbg}${failpoints}"
}

def getProxySuffix(repo_path) {
    return "${params.ARCH}-${params.OS}-${getToolchain(repo_path)}"
}

def getCheckoutTarget() {
    if (params.TARGET_COMMIT_HASH) {
        return params.TARGET_COMMIT_HASH;
    }
    if (params.TARGET_BRANCH) {
        sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"
        return sh(returnStdout: true, script: "python gethash.py -repo=tiflash -source=github -version=${params.TARGET_BRANCH} -s=${FILE_SERVER_URL}").trim()
    }
    error "no checkout target found, please provide branch or commit hash"
}

def checkoutTiFlash(target, full) {
    def refspec = "+refs/heads/*:refs/remotes/origin/*"

    if (params.TARGET_PULL_REQUEST) {
        if (full) {
            refspec += " +refs/pull/${params.TARGET_PULL_REQUEST}/*:refs/remotes/origin/pr/${params.TARGET_PULL_REQUEST}/*"
        } else {
            refspec = " +refs/pull/${params.TARGET_PULL_REQUEST}/*:refs/remotes/origin/pr/${params.TARGET_PULL_REQUEST}/*"
        }
    }

    checkout(changelog: false, poll: false, scm: [
            $class                           : "GitSCM",
            branches                         : [
                    [name: target],
            ],
            userRemoteConfigs                : [
                    [
                            url          : "git@github.com:pingcap/tiflash.git",
                            refspec      : refspec,
                            credentialsId: "github-sre-bot-ssh",
                    ]
            ],
            extensions                       : [
                    [$class             : 'SubmoduleOption',
                     disableSubmodules  : !full,
                     parentCredentials  : true,
                     recursiveSubmodules: true,
                     trackingSubmodules : false,
                     reference          : ''],
                    [$class: 'PruneStaleBranch'],
                    [$class: 'CleanBeforeCheckout'],
            ],
            doGenerateSubmoduleConfigurations: false,
    ])
}

def runBuilderClosure(label, image, Closure body) {
    podTemplate(name: label, label: label, instanceCap: 15, cloud: "kubernetes-ksyun", namespace: "jenkins-tiflash", idleMinutes: 0,nodeSelector: "kubernetes.io/arch=amd64",
        containers: [
            containerTemplate(name: 'builder', image: image,
                    alwaysPullImage: true, ttyEnabled: true, command: 'cat',
                    resourceRequestCpu: '10000m', resourceRequestMemory: '32Gi',
                    resourceLimitCpu: '20000m', resourceLimitMemory: '64Gi'),
    ],
    volumes: [
            // TODO use s3 cache instead of nfs
            nfsVolume(mountPath: '/home/jenkins/agent/ci-cached-code-daily', serverAddress: "${NFS_SERVER_ADDRESS}",
                    serverPath: '/data/nvme1n1/nfs/git', readOnly: true),
            nfsVolume(mountPath: '/home/jenkins/agent/proxy-cache', serverAddress: "${NFS_SERVER_ADDRESS}",
                    serverPath: '/data/nvme1n1/nfs/tiflash/proxy-cache', readOnly: !params.UPDATE_PROXY_CACHE),
            nfsVolume(mountPath: '/home/jenkins/agent/ccache', serverAddress: "${NFS_SERVER_ADDRESS}",
                    serverPath: '/data/nvme1n1/nfs/tiflash/ccache', readOnly: !params.UPDATE_CCACHE),
            nfsVolume(mountPath: '/home/jenkins/agent/dependency', serverAddress: "${NFS_SERVER_ADDRESS}",
                    serverPath: '/data/nvme1n1/nfs/tiflash/dependency', readOnly: true),
            nfsVolume(mountPath: '/home/jenkins/agent/rust', serverAddress: "${NFS_SERVER_ADDRESS}",
                    serverPath: '/data/nvme1n1/nfs/tiflash/rust', readOnly: false),
    ],
    hostNetwork: true
    ) {
        node(label) {
            container('builder') {
                body()
            }
        }
    }
}

def checkoutStage(repo_path, checkout_target) {
    stage("Checkout") {
        dir(repo_path) {
            def cache_path = "/home/jenkins/agent/ci-cached-code-daily/src-tics.tar.gz"
            if (fileExists(cache_path)) {
                sh label: "Get code from nfs to reduce clone time", script: """
                pwd && ls -alh
                cp -R ${cache_path}  ./
                tar -xzf ${cache_path} --strip-components=1
                rm -f src-tics.tar.gz
                chown -R 1000:1000 ./
                """
            }
            checkoutTiFlash(checkout_target, true)
            sh """
            git version
            git config --global --add safe.directory /home/jenkins/agent/workspace/tiflash-build-common/tiflash/contrib/tiflash-proxy
            git config --global --add safe.directory /home/jenkins/agent/workspace/tiflash-build-common/tiflash
            """
        }
        sh label: "Print build information", script: """
        set +x
        echo "Target Branch: ${params.TARGET_BRANCH}"
        echo "Target Pull Request: ${params.TARGET_PULL_REQUEST}"
        echo "Commit Hash: ${params.TARGET_COMMIT_HASH}"
        echo "Checkout: ${checkout_target}"
        echo "OS: ${params.OS}"
        echo "Arch: ${params.ARCH}"
        echo "Targets: ${getBuildTarget()}"
        echo "Build Suffix: ${getBuildSuffix(repo_path)}"
        echo "CCache: ${params.ENABLE_CCACHE}"
        echo "CCache Refresh: ${params.UPDATE_CCACHE}"
        echo "Proxy Cache: ${params.ENABLE_PROXY_CACHE}"
        echo "Proxy Cache Refresh: ${params.UPDATE_PROXY_CACHE}"
        echo "Format Check: ${params.ENABLE_FORMAT_CHECK}"
        echo "Static Analysis: ${params.ENABLE_STATIC_ANALYSIS}"
        set -x
        """
    }
}

def getFileSuffix() {
    if (params.OS == 'darwin') {
        return 'dylib'
    } else {
        return 'so'
    }
}

def fetchTiFlashProxy(repo_path, target_dir) {
    if (!params.ENABLE_PROXY_CACHE) {
        return false
    }
    def proxy_suffix = getProxySuffix(repo_path)
    // TODO: next-gen proxy cache?
    def proxy_commit_hash = null
    def status = true;
    dir("${repo_path}/contrib/tiflash-proxy") {
        proxy_commit_hash = sh(returnStdout: true, script: 'git log -1 --format="%H"').trim()
    }
    def cache_source = "/home/jenkins/agent/proxy-cache/${proxy_commit_hash}-${proxy_suffix}"
    def suffix = getFileSuffix()
    if (fileExists(cache_source)) {
        echo "proxy cache found"
        dir(target_dir) {
            sh """
            cp ${cache_source} libtiflash_proxy.${suffix}
            chmod +x libtiflash_proxy.${suffix}
            """
        }
    } else {
        echo "proxy cache not found"
        status = false
    }

    return status
}

def fetchCCache(repo_path, target_dir) {
    if (!params.ENABLE_CCACHE) {
        return
    }
    def ccache_tag = getBuildSuffix(repo_path)
    def ccache_source = "/home/jenkins/agent/ccache/${ccache_tag}.tar"
    dir(target_dir) {
        if (fileExists(ccache_source)) {
            echo "ccache found"
            sh """
            cd /tmp
            cp ${ccache_source} ccache.tar
            tar -xf ccache.tar
            cd -
            """
        } else {
            echo "ccache not found"
        }
        sh """
        ccache -o cache_dir="/tmp/.ccache"
        ccache -o max_size=2G
        ccache -o limit_multiple=0.99
        ccache -o hash_dir=false
        ccache -o compression=true
        ccache -o compression_level=6
        ccache -o read_only=${!params.UPDATE_CCACHE}
        ccache -z
        """
    }
}

def resolveDependency(dep_name) {
    def dependency_dir = "/home/jenkins/agent/dependency"
    if (dep_name == 'cmake') {
        def version = sh(returnStdout: true, script: "cmake --version | head -1 | awk -e '{printf \$3;}'").trim()
        def (major, minor, patch) = version.tokenize('.')
        if (major.toInteger() < 3 || (major.toInteger() == 3 && minor.toInteger() < 22)) {
            def arch_id = null
            if (params.ARCH == 'arm64') {
                arch_id = "aarch64"
            } else if (params.ARCH == 'amd64') {
                arch_id = "x86_64"
            }
            if (params.OS == 'linux') {
                echo "trying to install cmake"
                sh """
                sh ${dependency_dir}/cmake-3.22.3-linux-${arch_id}.sh --prefix=/usr --skip-license --exclude-subdir
                """
            } else {
                error "cmake is too old, but package is not available"
            }
        }
    }
    else if (sh(returnStatus: true, script: "which ${dep_name}") == 0) {
        echo "${dep_name} is already installed"
    }
    else if (params.OS == 'linux' && params.ARCH == 'amd64') {
        echo "try installing ${dep_name}"
        if (dep_name == 'clang-tidy') {
            sh """
            cp '${dependency_dir}/clang-tidy-12' '/usr/local/bin/clang-tidy'
            chmod +x '/usr/local/bin/clang-tidy'
            cp '${dependency_dir}/lib64-clang-12-include.tar.gz' '/tmp/lib64-clang-12-include.tar.gz'
            cd /tmp && tar zxf lib64-clang-12-include.tar.gz
            """
        }
        else if (dep_name == 'ccache') {
            sh """
            rpm -Uvh '${dependency_dir}/ccache.x86_64.rpm'
            """
        }
        else if (dep_name == 'clang-format') {
            sh """
            cp '${dependency_dir}/clang-format-12' '/usr/local/bin/clang-format'
            chmod +x '/usr/local/bin/clang-format'
            """
        }
        else if (dep_name == 'clang-format-15') {
            sh """
            cp '${dependency_dir}/clang-format-15' '/usr/local/bin/clang-format-15'
            chmod +x '/usr/local/bin/clang-format-15'
            """
        }
        else if (dep_name == 'gcovr') {
            sh """
            cp '${dependency_dir}/gcovr.tar' '/tmp/'
            cd /tmp
            tar xvf gcovr.tar && rm -rf gcovr.tar
            ln -sf /tmp/gcovr/gcovr /usr/bin/gcovr
            """
        }
        else {
            error "invalid dependency: ${dep_name}"
        }
    } else {
        error "unsolved dependency: ${dep_name}"
    }
}

def dispatchRunEnv(repo_path, Closure body) {
    def identifier = getBuildIdentifier(repo_path)
    def image_tag_suffix = ""
    if (fileExists("${repo_path}/.toolchain.yml")) {
        def config = readYaml(file: "${repo_path}/.toolchain.yml")
        image_tag_suffix = config.image_tag_suffix
        identifier = "${identifier}${image_tag_suffix}"
    }
    if (params.OS == 'darwin') {
        node(identifier) {
            body()
        }
    } else if (params.ARCH == "amd64") {
        if (getToolchain(repo_path) == 'llvm') {
            runBuilderClosure(identifier, "hub.pingcap.net/tiflash/tiflash-llvm-base:amd64${image_tag_suffix}", body)
        } else {
            runBuilderClosure(identifier, "hub.pingcap.net/tiflash/tiflash-builder-ci${image_tag_suffix}", body)
        }
    } else {
        if (getToolchain(repo_path) == 'llvm') {
            runBuilderClosure(identifier, "hub.pingcap.net/tiflash/tiflash-llvm-base:aarch64${image_tag_suffix}", body)
        } else {
            runBuilderClosure(identifier, "hub.pingcap.net/tiflash/tiflash-builder:arm64${image_tag_suffix}", body)
        }
    }
}

def prepareStage(repo_path) {
    def proxy_cache_ready = false
    stage("Prepare Tools") {
        parallel(
            "CCache" : {
                if (params.ENABLE_CCACHE) {
                    resolveDependency('ccache')
                }
            },
            "Clang-Tidy" : {
                if (params.ENABLE_STATIC_ANALYSIS) {
                    resolveDependency('clang-tidy')
                }
            },
            "Clang-Format" : {
                if (params.ENABLE_CCACHE) {
                    resolveDependency('clang-format')
                }
            },
            "Clang-Format-15" : {
                if (params.ENABLE_CCACHE) {
                    resolveDependency('clang-format-15')
                }
            },
            "Coverage" : {
                if (getToolchain(repo_path) == 'legacy' && params.ENABLE_COVERAGE) {
                    resolveDependency('gcovr')
                }
            },
            "CMake" : {
                resolveDependency('cmake')
            }
        )
    }
    stage("Prepare Cache") {
        parallel(
            "CCache" : {
                fetchCCache(repo_path, repo_path)
            },
            "Proxy Cache" : {
                proxy_cache_ready = fetchTiFlashProxy(repo_path, "${repo_path}/libs/libtiflash-proxy")
                if (params.OS != 'darwin') {
                    sh """
                    mkdir -p ~/.cargo/registry
                    mkdir -p ~/.cargo/git
                    mkdir -p /home/jenkins/agent/rust/registry/cache
                    mkdir -p /home/jenkins/agent/rust/registry/index
                    mkdir -p /home/jenkins/agent/rust/git/db
                    mkdir -p /home/jenkins/agent/rust/git/checkouts

                    rm -rf ~/.cargo/registry/cache && ln -s /home/jenkins/agent/rust/registry/cache ~/.cargo/registry/cache
                    rm -rf ~/.cargo/registry/index && ln -s /home/jenkins/agent/rust/registry/index ~/.cargo/registry/index
                    rm -rf ~/.cargo/git/db && ln -s /home/jenkins/agent/rust/git/db ~/.cargo/git/db
                    rm -rf ~/.cargo/git/checkouts && ln -s /home/jenkins/agent/rust/git/checkouts ~/.cargo/git/checkouts

                    rm -rf ~/.rustup/tmp
                    rm -rf ~/.rustup/toolchains
                    mkdir -p /home/jenkins/agent/rust/rustup-env/tmp
                    mkdir -p /home/jenkins/agent/rust/rustup-env/toolchains
                    ln -s /home/jenkins/agent/rust/rustup-env/tmp ~/.rustup/tmp
                    ln -s /home/jenkins/agent/rust/rustup-env/toolchains ~/.rustup/toolchains

                    """
                }
            }
        )
    }
    return proxy_cache_ready
}

def buildClusterManage(repo_path, install_dir) {
    if (!fileExists("${repo_path}/cluster_manage/release.sh")) {
        echo "cluster_manager is deprecated"
    } else {
        sh "cd ${repo_path}/cluster_manage && sh release.sh"
        sh "mkdir -p ${install_dir} && cp -rf ${repo_path}/cluster_manage/dist/flash_cluster_manager ${install_dir}/flash_cluster_manager"
    }
}

def buildTiFlashProxy(repo_path, proxy_cache_ready) {
    if (proxy_cache_ready) {
        echo "skip becuase of cache"
    } else if (getToolchain(repo_path) == 'llvm') {
        echo "skip because proxy build is integrated"
    } else {
        dir("${repo_path}/contrib/tiflash-proxy") {
            sh "env ENGINE_LABEL_VALUE=tiflash make release"
            sh "mkdir -p '${repo_path}/libs/libtiflash-proxy'"
            def suffix = getFileSuffix()
            sh "cp target/release/libtiflash_proxy.${suffix} ${repo_path}/libs/libtiflash-proxy/libtiflash_proxy.${suffix}"
            if (params.OS == 'darwin') {
                sh "otool -id @executable_path/libtiflash_proxy.${suffix} ${repo_path}/libs/libtiflash-proxy/libtiflash_proxy.${suffix}"
            }
        }
    }
}

def cmakeConfigureTiFlash(repo_path, build_dir, install_dir, proxy_cache_ready) {
    def toolchain = getToolchain(repo_path)
    def generator = "Unix Makefiles"
    def prebuilt_dir_flag = ""
    def coverage_flag = ""
    def diagnostic_flag = ""
    def compatible_flag = "-DENABLE_EMBEDDED_COMPILER=OFF -DENABLE_ICU=OFF -DENABLE_MYSQL=OFF"
    def openssl_root_dir = ""

    if (toolchain == 'llvm') {
        generator = 'Ninja'
        compatible_flag = ""
        if (proxy_cache_ready) {
            prebuilt_dir_flag = "-DPREBUILT_LIBS_ROOT='${repo_path}/contrib/tiflash-proxy/'"
            sh "mkdir -p ${repo_path}/contrib/tiflash-proxy/target/release"
            sh "cp ${repo_path}/libs/libtiflash-proxy/libtiflash_proxy.so ${repo_path}/contrib/tiflash-proxy/target/release"
        }
    } else {
        openssl_root_dir = "-DOPENSSL_ROOT_DIR='/usr/local/opt/openssl'"
        dir("${repo_path}/contrib/tipb/") {
            sh "sh generate-cpp.sh"
        }
        dir("${repo_path}/contrib/kvproto/") {
            sh "sh ./scripts/generate_cpp.sh"
        }
    }

    if (params.ENABLE_COVERAGE) {
        if (toolchain == 'llvm') {
            coverage_flag = "-DTEST_LLVM_COVERAGE=ON"
        } else if (toolchain == 'legacy') {
            coverage_flag = "-DTEST_COVERAGE=ON -DTEST_COVERAGE_XML=ON"
        }
    }

    if (params.OS == 'darwin') {
        diagnostic_flag = '-Wno-dev -DNO_WERROR=ON'
    }
    sh """
    mkdir -p ${build_dir}
    mkdir -p ${install_dir}
    """
    dir(build_dir) {
        sh """
            git version
            git config --global --add safe.directory /home/jenkins/agent/workspace/tiflash-build-common/tiflash/contrib/tiflash-proxy
            git config --global --add safe.directory /home/jenkins/agent/workspace/tiflash-build-common/tiflash

            cmake '${repo_path}' ${prebuilt_dir_flag} ${coverage_flag} ${diagnostic_flag} ${compatible_flag} ${openssl_root_dir} \\
                -G '${generator}' \\
                -DENABLE_FAILPOINTS=${params.ENABLE_FAILPOINTS} \\
                -DCMAKE_BUILD_TYPE=${params.CMAKE_BUILD_TYPE} \\
                -DCMAKE_PREFIX_PATH='/usr/local' \\
                -DCMAKE_INSTALL_PREFIX=${install_dir} \\
                -DENABLE_TESTS=${params.BUILD_TESTS} \\
                -DUSE_CCACHE=${params.ENABLE_CCACHE} \\
                -DDEBUG_WITHOUT_DEBUG_INFO=${params.DEBUG_WITHOUT_DEBUG_INFO} \\
                -DUSE_INTERNAL_TIFLASH_PROXY=${!proxy_cache_ready} \\
                -DRUN_HAVE_STD_REGEX=0 \\
        """
    }
}

def buildTiFlash(repo_path, build_dir, install_dir) {
    def toolchain = getToolchain(repo_path)
    def targets = getBuildTarget()

    dir(build_dir) {
        if (targets.contains('page_ctl') && sh(returnStatus: true, script: 'cmake --build . --target help | grep page_ctl') != 0) {
            echo "remove page_ctl from target list"
            targets = targets.replaceAll('page_ctl', '')
        }
        if (targets.contains('page_stress_testing') && sh(returnStatus: true, script: 'cmake --build . --target help | grep page_stress_testing') != 0) {
            echo "remove page_stress_testing from target list"
            targets = targets.replaceAll('page_stress_testing', '')
        }
        if (targets.contains('gtests_libdaemon') && sh(returnStatus: true, script: 'cmake --build . --target help | grep gtests_libdaemon') != 0) {
            echo "remove gtests_libdaemon from target list"
            targets = targets.replaceAll('gtests_libdaemon', '')
        }
    }

    sh """
    cmake --build '${build_dir}' --target ${targets} --parallel 12
    """
    if (params.BUILD_TIFLASH) {
        if (toolchain == 'llvm') {
                sh "cmake --install ${build_dir} --component=tiflash-release --prefix='${install_dir}'"
        } else {
            def suffix = getFileSuffix()
            sh "mkdir -p ${install_dir}"
            if (sh(returnStatus: true,  script: "which objcopy") == 0) {
                sh "objcopy --compress-debug-sections=zlib-gnu '${build_dir}/dbms/src/Server/tiflash'"
            }
            sh "cp '${build_dir}/dbms/src/Server/tiflash' '${install_dir}/tiflash'"
            sh "cp '${repo_path}/libs/libtiflash-proxy/libtiflash_proxy.${suffix}' '${install_dir}/libtiflash_proxy.${suffix}'"
            if (params.OS == 'linux') {
                sh "ldd '${install_dir}/tiflash' | grep 'libnsl.so' | grep '=>' | awk '{print \$3}' | xargs -I {} cp {} '${install_dir}'"
            }
        }
    }

    if (params.BUILD_TESTS) {
        sh "cp '${build_dir}/dbms/gtests_dbms' '${install_dir}/'"
        sh "cp '${build_dir}/libs/libcommon/src/tests/gtests_libcommon' '${install_dir}/'"
        // When toolchain is `llvm`, 
        //   if install rule `tiflash-gtest` exists, the following line will override the `gtests_dbms` binary in `install_dir` and copy some other libraries.
        //   if the rule doesn't exist, the following line will do nothing.
        if (toolchain == 'llvm') {
            sh "cmake --install ${build_dir} --component=tiflash-gtest --prefix='${install_dir}'"
        }
    }

    dir(build_dir) {
        if (targets.contains('page_ctl')) {
            def target = sh(returnStdout: true, script: 'realpath $(find . -executable | grep -v page_ctl.dir | grep page_ctl)').trim()
            sh "cp '${target}' '${install_dir}/'"
        }
        if (targets.contains('page_stress_testing')) {
            def target = sh(returnStdout: true, script: 'realpath $(find . -executable | grep -v page_stress_testing.dir | grep page_stress_testing)').trim()
            sh "cp '${target}' '${install_dir}/'"
        }
        if (targets.contains('gtests_libdaemon')) {
            def target = sh(returnStdout: true, script: 'realpath $(find . -executable | grep -v gtests_libdaemon.dir | grep gtests_libdaemon)').trim()
            sh "cp '${target}' '${install_dir}/'"
        }
    }
    if (params.ENABLE_CCACHE) {
        sh """
        ccache -s
        ls -lha ${install_dir}
        """
    }
}

def clangFormat(repo_path) {
    if (!params.ENABLE_FORMAT_CHECK) {
        echo "format check skipped"
        return
    }

    if (!fileExists("${repo_path}/format-diff.py")) {
        echo "skipped because this branch does not support format"
        return
    }

    def target_branch = "master"
    if (params.TARGET_BRANCH) {
        target_branch = params.TARGET_BRANCH
    }

    def diff_flag = "--dump_diff_files_to '/tmp/tiflash-diff-files.json'"
    if (!fileExists("${repo_path}/release-centos7-llvm/scripts/run-clang-tidy.py") && !fileExists("${repo_path}/release-centos7/build/run-clang-tidy.py")) {
        diff_flag = ""
    }

    dir(repo_path) {
        sh """
        python3 \\
            ${repo_path}/format-diff.py ${diff_flag} \\
            --repo_path '${repo_path}' \\
            --check_formatted \\
            --diff_from \$(git merge-base origin/${target_branch} HEAD)
        """
    }
}

def staticAnalysis(repo_path, build_dir) {
    if (!params.ENABLE_STATIC_ANALYSIS) {
        echo "static analysis skipped"
        return
    }

    def generator = "Unix Makefiles"
    def include_flag = "--includes=/tmp/usr/lib64/clang/12.0.0/include"
    if (getToolchain(repo_path) == 'llvm') {
        generator = "Ninja"
        include_flag = ""
    }

    def fix_compile_commands = "${repo_path}/release-centos7-llvm/scripts/fix_compile_commands.py"
    def run_clang_tidy = "${repo_path}/release-centos7-llvm/scripts/run-clang-tidy.py"

    if (getToolchain(repo_path) == 'legacy') {
        fix_compile_commands = "${repo_path}/release-centos7/build/fix_compile_commands.py"
        run_clang_tidy = "${repo_path}/release-centos7/build/run-clang-tidy.py"
    }

    if (!fileExists(run_clang_tidy)) {
        echo "skipped because this branch does not support static analysis"
        return
    }

    dir(build_dir) {
        sh """
        NPROC=\$(nproc || grep -c ^processor /proc/cpuinfo || echo '1')
        cmake "${repo_path}" \\
            -DENABLE_TESTS=${params.BUILD_TESTS} \\
            -DCMAKE_BUILD_TYPE=${params.CMAKE_BUILD_TYPE} \\
            -DUSE_CCACHE=OFF \\
            -DCMAKE_EXPORT_COMPILE_COMMANDS=ON \\
            -DRUN_HAVE_STD_REGEX=0 \\
            -G '${generator}'
        python3 ${fix_compile_commands} ${include_flag} \\
            --file_path=compile_commands.json \\
            --load_diff_files_from "/tmp/tiflash-diff-files.json"
        python3 ${run_clang_tidy} -p \$(realpath .) -j \$NPROC --files ".*/tiflash/dbms/*"
        """
    }
}

def buildStage(repo_path, build_dir, install_dir, proxy_cache_ready) {
    stage('Build Dependency and Utils') {
        parallel (
            "Cluster Manage" : {
                buildClusterManage(repo_path, install_dir)
            },
            "TiFlash Proxy" : {
                buildTiFlashProxy(repo_path, proxy_cache_ready)
            }
        )
    }
    stage('Configure Project') {
        cmakeConfigureTiFlash(repo_path, build_dir, install_dir, proxy_cache_ready)
    }
    stage("Format Check") {
        clangFormat(repo_path)
    }
    stage('Build TiFlash') {
        parallel(
            "License check": {
                dir(repo_path) {
                    sh label: "license header check", script: """
                        echo "license check"
                        if [[ -f .github/licenserc.yml ]]; then
                            wget -q -O license-eye http://fileserver.pingcap.net/download/cicd/ci-tools/license-eye_v0.4.0
                            chmod +x license-eye
                            ./license-eye -c .github/licenserc.yml header check
                        else
                            echo "skip license check"
                            exit 0
                        fi
                    """
                }
            },
            "Build TiFlash" : {
                buildTiFlash(repo_path, build_dir, install_dir)
            },
            failFast: true
        )
    }
}

def uploadCCache(repo_path, target_dir) {
    if (params.ENABLE_CCACHE && params.UPDATE_CCACHE) {
        def ccache_tag = getBuildSuffix(repo_path)
        def ccache_source = "/home/jenkins/agent/ccache/${ccache_tag}.tar"
        dir(target_dir) {
            sh"""
            cd /tmp
            rm -rf ccache.tar
            tar -cf ccache.tar .ccache
            cp ccache.tar ${ccache_source}
            cd -
            """
        }
    } else {
        echo "skip because ccache refresh is disabled"
    }
}

def uploadProxyCache(repo_path, install_dir) {
    if (!params.BUILD_TIFLASH) {
        echo "skip because proxy is not built or packaged"
    } else if (params.UPDATE_PROXY_CACHE) {
        def proxy_suffix = getProxySuffix(repo_path)
        // TODO: next-gen proxy cache?
        def proxy_commit_hash = null
        def status = true;
        dir("${repo_path}/contrib/tiflash-proxy") {
            proxy_commit_hash = sh(returnStdout: true, script: 'git log -1 --format="%H"').trim()
        }
        def cache_source = "/home/jenkins/agent/proxy-cache/${proxy_commit_hash}-${proxy_suffix}"
        def suffix = getFileSuffix()
        sh"""
        cp '${install_dir}/libtiflash_proxy.${suffix}' ${cache_source}
        """
    } else {
        echo "skip because proxy cache refresh is disabled"
    }
}

def uploadBuildArtifacts(install_dir) {
    if (params.ARCHIVE_ARTIFACTS) {
        def basename = sh(returnStdout: true, script: "basename '${install_dir}'").trim()
        dir("${install_dir}/../") {
            sh """
            tar -czf '${basename}.tar.gz' '${basename}'
            """
            archiveArtifacts artifacts: "${basename}.tar.gz"
        }
    } else {
        echo "skip because archiving is disabled"
    }
}

def uploadBuildData(repo_path, build_dir) {
    if (params.ARCHIVE_BUILD_DATA) {
        dir(build_dir) {
            def run = sh(returnStatus: true, script: 'tar -cavf build-data.tar.xz $(find . -name "*.h" -o -name "*.cpp" -o -name "*.cc" -o -name "*.hpp" -o -name "*.gcno" -o -name "*.gcna")')
            if (run == 0) {
                archiveArtifacts artifacts: "build-data.tar.xz"
            }
        }
        dir(repo_path) {
            def run = sh(returnStatus: true, script: 'tar -cavf source-patch.tar.xz $(find . -name "*.pb.h" -o -name "*.pb.cc")')
            if (run == 0) {
                archiveArtifacts artifacts: "source-patch.tar.xz"
            }
        }
    }
}

def markToolchain(repo_path) {
    def toolchain = getToolchain(repo_path)
    sh """
    echo '${toolchain}' | tee toolchain
    """
    archiveArtifacts artifacts: "toolchain"
}

def postBuildStage(repo_path, build_dir, install_dir) {
    stage("Post Build") {
        parallel(
            'Static Analysis': {
                staticAnalysis(repo_path, build_dir)
            },
            'Upload CCache': {
                uploadCCache(repo_path, repo_path)
            },
            'Upload Proxy Cache': {
                uploadProxyCache(repo_path, install_dir)
            },
            'Upload Build Artifacts': {
                uploadBuildArtifacts(install_dir)
            },
            'Mark Toolchain': {
                markToolchain(repo_path)
            },
            'Upload Build Data': {
                uploadBuildData(repo_path, build_dir)
            }
        )
    }
}

podYAML = '''
apiVersion: v1
kind: Pod
spec:
  nodeSelector:
    enable-ci: true
    ci-nvme-high-performance: true
    kubernetes.io/arch: amd64
  tolerations:
  - key: dedicated
    operator: Equal
    value: test-infra
    effect: NoSchedule
'''

def run_with_pod(Closure body) {
    def label = "${JOB_NAME}-${BUILD_NUMBER}-build"
    def cloud = "kubernetes-ksyun"
    def namespace = "jenkins-tiflash"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            yaml: podYAML,
            yamlMergeStrategy: merge(),
            containers: [
                    containerTemplate(
                        name: 'golang', alwaysPullImage: true,
                        image: "hub.pingcap.net/jenkins/centos7_golang-1.18:latest", ttyEnabled: true,
                        resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
                        command: '/bin/sh -c', args: 'cat',
                        envVars: [containerEnvVar(key: 'GOPATH', value: '/go')]
                    )
            ],
            volumes: [
                    emptyDirVolume(mountPath: '/tmp', memory: false),
                    emptyDirVolume(mountPath: '/home/jenkins', memory: false),
                    nfsVolume(mountPath: '/home/jenkins/agent/ci-cached-code-daily', serverAddress: "${NFS_SERVER_ADDRESS}",
                        serverPath: '/data/nvme1n1/nfs/git', readOnly: true),
                    ],
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${namespace} exec -ti ${NODE_NAME} bash"
            body()
        }
    }
}

run_with_pod {
    container("golang") {
        def checkout_target = getCheckoutTarget()
        def repo_path = "${pwd()}/tiflash"
        def build_dir = "${pwd()}/build"
        def install_dir = "${pwd()}/install/tiflash"
        def error_msg = ""
        try {
            dir(repo_path) {
                sh label: "copy code cache", script: """#!/usr/bin/env bash
                if [[ -f /home/jenkins/agent/ci-cached-code-daily/src-tiflash-without-submodule.tar.gz ]]; then
                    cp -R /home/jenkins/agent/ci-cached-code-daily/src-tiflash-without-submodule.tar.gz ./
                    tar -xzf src-tiflash-without-submodule.tar.gz --strip-components=1
                    rm -f src-tiflash-without-submodule.tar.gz
                    chown -R 1000:1000 ./
                    git status -s
                fi
                """
                checkoutTiFlash(checkout_target, false)
            }
            dispatchRunEnv(repo_path) {
                checkoutStage(repo_path, checkout_target)
                def proxy_cache_ready = prepareStage(repo_path)
                buildStage(repo_path, build_dir, install_dir, proxy_cache_ready)
                postBuildStage(repo_path, build_dir, install_dir)
            }
        } catch (Exception e) {
            error_msg = "Error Message: ${e}\\n"
            error "build failed: ${e}"
        } finally {
            stage('Lark Message') {
                if (params.PUSH_MESSAGE) {
                    def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
                    def result_mark = "❌"
                    if (!error_msg) {
                        result_mark = "✅"
                    }
                    def feishumsg = "TiFlash Build Common\\n" +
                            "Build Number: ${env.BUILD_NUMBER}\\n" +
                            "Result: ${result_mark}\\n" +
                            "Target: ${checkout_target}\\n" +
                            "Elapsed Time: ${duration} Mins\\n" +
                            "OS: ${params.OS}\\n" +
                            "Arch: ${params.ARCH}\\n" +
                            error_msg +
                            "Build Link: https://ci.pingcap.net/blue/organizations/jenkins/tiflash-build-common/detail/tiflash-build-common/${env.BUILD_NUMBER}/pipeline\\n" +
                            "Job Page: https://ci.pingcap.net/blue/organizations/jenkins/tiflash-build-common/detail/tiflash-build-common/activity/"
                    print feishumsg
                    withCredentials([string(credentialsId: 'tiflash-regression-lark-channel-hook', variable: 'TOKEN')]) {
                        sh """
                        curl -X POST "\$TOKEN" -H 'Content-Type: application/json' \
                        -d '{
                            "msg_type": "text",
                            "content": {
                            "text": "$feishumsg"
                            }
                        }'
                        """
                    }
                } else {
                    echo "skipped because message pushing is disabled"
                }
            }
        }
    }
}
