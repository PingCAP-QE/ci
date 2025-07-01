/**
 * The total number of integration test groups.
 */
TOTAL_COUNT = 0

/**
 * Integration testing number of tests per group.
 */
GROUP_SIZE = 4

COMMIT_SHA1_REG = /^[0-9a-f]{40}$/

/**
 * the tidb archive is packaged differently on pr than on the branch build,
 * pr build is ./bin/tidb-server
 * branch build is bin/tidb-server
 */
TIDB_ARCHIVE_PATH_PR = "./bin/tidb-server"
TIDB_ARCHIVE_PATH_BRANCH = "bin/tidb-server"

/**
 * Partition the array.
 * @param array
 * @param size
 * @return Array partitions.
 */
static def partition(array, size) {
    def partitions = []
    int partitionCount = array.size() / size

    partitionCount.times { partitionNumber ->
        int start = partitionNumber * size
        int end = start + size - 1
        partitions << array[start..end]
    }

    if (array.size() % size) partitions << array[partitionCount * size..-1]
    return partitions
}

def test_file_existed(file_url) {
    cacheExisted = sh(returnStatus: true, script: """
    if curl --output /dev/null --silent --head --fail ${file_url}; then exit 0; else exit 1; fi
    """)
    if (cacheExisted == 0) {
        return true
    } else {
        return false
    }
}


cacheBinaryPath = "test/cdc/ci/integration_test/${ghprbActualCommit}/ticdc_bin.tar.gz"
cacheBinaryDonePath = "test/cdc/ci/integration_test/${ghprbActualCommit}/done"

/**
 * Prepare the binary file for testing.
 */
def prepare_binaries() {
    stage('Prepare Binaries') {
        def prepares = [:]

        prepares["build binaries"] = {
            container("golang") {
                // if this ci triggered by upstream ci, build binary every time
                if (!params.containsKey("triggered_by_upstream_pr_ci") && test_file_existed("${FILE_SERVER_URL}/download/${cacheBinaryDonePath}") && test_file_existed("${FILE_SERVER_URL}/download/${cacheBinaryPath}")) {
                    println "cache binary existed"
                    println "binary download url: ${FILE_SERVER_URL}/download/${cacheBinaryPath}"
                    def ws = pwd()
                    deleteDir()
                    unstash 'ticdc'
                    sh """
                    cd go/src/github.com/pingcap/tiflow
                    ls -alh
                    wget -q --retry-connrefused --waitretry=1 --read-timeout=120 --timeout=120 -t 3 -O ticdc_bin.tar.gz ${FILE_SERVER_URL}/download/${cacheBinaryPath}
                    tar -xvf ticdc_bin.tar.gz
                    chmod +x bin/cdc
                    ./bin/cdc version
                    rm -rf ticdc_bin.tar.gz
                    """
                } else {
                    println "start to build binary"
                    println "debug command:\nkubectl -n jenkins-tiflow exec -ti ${NODE_NAME} -c golang bash"
                    def ws = pwd()
                    deleteDir()
                    unstash 'ticdc'
                    dir("go/src/github.com/pingcap/tiflow") {
                        sh """
                            GOPATH=\$GOPATH:${ws}/go PATH=\$GOPATH/bin:${ws}/go/bin:\$PATH make cdc
                            GOPATH=\$GOPATH:${ws}/go PATH=\$GOPATH/bin:${ws}/go/bin:\$PATH make integration_test_build
                            GOPATH=\$GOPATH:${ws}/go PATH=\$GOPATH/bin:${ws}/go/bin:\$PATH make kafka_consumer
                            GOPATH=\$GOPATH:${ws}/go PATH=\$GOPATH/bin:${ws}/go/bin:\$PATH make check_failpoint_ctl
                            tar czvf ticdc_bin.tar.gz bin/*
                            curl -F ${cacheBinaryPath}=@ticdc_bin.tar.gz http://fileserver.pingcap.net/upload
                            touch done
                            curl -F ${cacheBinaryDonePath}=@done http://fileserver.pingcap.net/upload
                        """
                    }
                }
                dir("go/src/github.com/pingcap/tiflow/tests/integration_tests") {
                    sh """
                    pwd
                    ls -alh .
                    """
                    def cases_name = sh(
                            script: 'find . -maxdepth 2 -mindepth 2 -name \'run.sh\' | awk -F/ \'{print $2}\'',
                            returnStdout: true
                    ).trim().split().join(" ")
                    sh "echo ${cases_name} > CASES"
                }
                stash includes: "go/src/github.com/pingcap/tiflow/tests/integration_tests/CASES", name: "cases_name", useDefaultExcludes: false

            }
        }

        parallel prepares
    }
}

/**
 * Start preparesning tests.
 * @param sink_type Type of Sink, optional value: mysql/kafaka.
 * @param node_label
 */
def tests(sink_type, node_label) {
    def all_task_result = []
    try {
        stage("Tests") {
            def test_cases = [:]
            // Set to fail fast.
            test_cases.failFast = false
            if (params.containsKey("ENABLE_FAIL_FAST")) {
                test_cases.failFast = params.get("ENABLE_FAIL_FAST")
            }
            println "failFast: ${test_cases.failFast}"

            // Start running integration tests.
            def run_integration_test = { step_name, case_names ->
                node(node_label) {
                    if (sink_type == "kafka") {
                        timeout(time: 6, unit: 'MINUTES') {
                            container("zookeeper") {
                                sh """
                                    echo "Waiting for zookeeper to be ready..."
                                    while ! nc -z localhost 2181; do sleep 10; done
                                """
                                sh """
                                    echo "Waiting for kafka to be ready..."
                                    while ! nc -z localhost 9092; do sleep 10; done
                                """
                                sh """
                                    echo "Waiting for kafka-broker to be ready..."
                                    while ! echo dump | nc localhost 2181 | grep brokers | awk '{\$1=\$1;print}' | grep -F -w "/brokers/ids/1"; do sleep 10; done
                                """
                            }
                        }
                    }

                    container("golang") {
                        def ws = pwd()
                        deleteDir()
                        println "debug command:\nkubectl -n jenkins-tiflow exec -ti ${NODE_NAME} -c golang bash"
                        println "work space path:\n${ws}"
                        println "this step will run tests: ${case_names}"
                        unstash 'ticdc'
                        dir("go/src/github.com/pingcap/tiflow") {
                            // download_binaries()
                            unstash "third_bins"
                            sh """ls -alh bin/"""
                            try {
                                timeout(time: 60, unit: 'MINUTES') {
                                    sh """
                                        go version
                                        s3cmd --version
                                        rm -rf /tmp/tidb_cdc_test
                                        mkdir -p /tmp/tidb_cdc_test
                                        echo "${env.KAFKA_VERSION}" > /tmp/tidb_cdc_test/KAFKA_VERSION
                                        GOPATH=\$GOPATH:${ws}/go PATH=\$GOPATH/bin:${ws}/go/bin:\$PATH make integration_test_${sink_type} CASE="${case_names}"
                                        rm -rf cov_dir
                                        mkdir -p cov_dir
                                        ls /tmp/tidb_cdc_test
                                        cp /tmp/tidb_cdc_test/cov*out cov_dir || touch cov_dir/dummy_file_${step_name}
                                    """
                                }

                                // cyclic tests do not run on kafka sink, so there is no cov* file.
                                sh """
                                tail /tmp/tidb_cdc_test/cov* || true
                                """
                            } catch (Exception e) {
                                def log_tar_name = case_names.replaceAll("\\s","-")
                                sh """
                                echo "archive logs"
                                ls /tmp/tidb_cdc_test/
                                tar -cvzf log-${log_tar_name}.tar.gz \$(find /tmp/tidb_cdc_test/ -type f -name "*.log")
                                ls -alh  log-${log_tar_name}.tar.gz
                                """

                                archiveArtifacts artifacts: "log-${log_tar_name}.tar.gz", caseSensitive: false
                                throw e;
                            }

                        }
                        stash includes: "go/src/github.com/pingcap/tiflow/cov_dir/**", name: "integration_test_${step_name}", useDefaultExcludes: false
                    }
                }
            }

            // Gets the name of each case.
            unstash 'cases_name'
            def cases_name = sh(
                    script: 'cat go/src/github.com/pingcap/tiflow/tests/integration_tests/CASES',
                    returnStdout: true
            ).trim().split()

            // Run integration tests in groups.
            def step_cases = []
            def cases_namesList = partition(cases_name, GROUP_SIZE)
            TOTAL_COUNT = cases_namesList.size()
            cases_namesList.each { case_names ->
                step_cases.add(case_names)
            }
            step_cases.eachWithIndex { case_names, index ->
                def step_name = "step_${index}"
                test_cases["integration test ${step_name}"] = {
                    try {
                        run_integration_test(step_name, case_names.join(" "))
                        all_task_result << ["name": case_names.join(" "), "status": "success", "error": ""]
                    } catch (err) {
                        all_task_result << ["name": case_names.join(" "), "status": "failed", "error": ""]
                        throw err
                    }
                }
            }

            parallel test_cases
        }
    } catch (err) {
        println "Error: ${err}"
        throw err
    } finally {
        container("golang") {
            if (all_task_result) {
                def json = groovy.json.JsonOutput.toJson(all_task_result)
                def ci_pipeline_name = ""
                if (sink_type == "kafka") {
                    ci_pipeline_name = "cdc_ghpr_kafka_integration_test"
                } else if (sink_type == "mysql") {
                    ci_pipeline_name = "cdc_ghpr_integration_test"
                }
                writeJSON file: 'ciResult.json', json: json, pretty: 4
                sh "cat ciResult.json"
                archiveArtifacts artifacts: 'ciResult.json', fingerprint: true
                sh """
                curl -F cicd/ci-pipeline-artifacts/result-${ci_pipeline_name}_${BUILD_NUMBER}.json=@ciResult.json ${FILE_SERVER_URL}/upload
                """
            }
        }
    }
}

/**
 * Download the integration test-related binaries.
 */
def download_binaries() {
    stage('Download Third party Binaries') {
        final defaultDependencyBranch = "master"
        final defaultTiDbDependencyBranch = "master"
        def releaseBranchReg = /^release\-(\d+)\.(\d+)/      // example: release-6.1
        def hotfixBranchReg = /^release\-(\d+)\.(\d+)-(\d+)/ // example: release-6.1-20220719

        def dependencyBranch
        def tidbDependencyBranch

        switch( ghprbTargetBranch ) {
            case ~releaseBranchReg:
                println "target branch is release branch, dependency use ${ghprbTargetBranch} branch to download binaries"
                dependencyBranch = ghprbTargetBranch
                tidbDependencyBranch = ghprbTargetBranch

                break
            case ~hotfixBranchReg:
                def relBr = ghprbTargetBranch.replaceFirst(/-\d+$/, "")
                println "target branch is hotfix branch, dependency use ${relBr} branch to download binaries"
                dependencyBranch = relBr
                tidbDependencyBranch = relBr

                break
            default:
                dependencyBranch = defaultDependencyBranch
                tidbDependencyBranch = defaultTiDbDependencyBranch
                println "target branch is not release branch, dependency tidb use ${tidbDependencyBranch} branch to download binaries"
                println "target branch is not release branch, dependency use ${dependencyBranch} branch instead"
        }

        def TIDB_BRANCH = params.getOrDefault("release_test__tidb_commit", tidbDependencyBranch)
        def TIKV_BRANCH = params.getOrDefault("release_test__tikv_commit", dependencyBranch)
        def PD_BRANCH = params.getOrDefault("release_test__pd_commit", dependencyBranch)
        def TIFLASH_BRANCH = params.getOrDefault("release_test__release_branch", dependencyBranch)
        def TIFLASH_COMMIT = params.getOrDefault("release_test__tiflash_commit", null)

        // parse tidb branch
        def m1 = ghprbCommentBody =~ /tidb\s*=\s*([^\s\\]+)(\s|\\|$)/
        def tidb_archive_path = TIDB_ARCHIVE_PATH_BRANCH
        if (m1) {
            TIDB_BRANCH = "${m1[0][1]}"
        }
        m1 = null
        println "TIDB_BRANCH=${TIDB_BRANCH}"

        // parse tikv branch
        def m2 = ghprbCommentBody =~ /tikv\s*=\s*([^\s\\]+)(\s|\\|$)/
        if (m2) {
            TIKV_BRANCH = "${m2[0][1]}"
        }
        m2 = null
        println "TIKV_BRANCH=${TIKV_BRANCH}"

        // parse pd branch
        def m3 = ghprbCommentBody =~ /pd\s*=\s*([^\s\\]+)(\s|\\|$)/
        if (m3) {
            PD_BRANCH = "${m3[0][1]}"
        }
        m3 = null
        println "PD_BRANCH=${PD_BRANCH}"

        // parse tiflash branch
        def m4 = ghprbCommentBody =~ /tiflash\s*=\s*([^\s\\]+)(\s|\\|$)/
        if (m4) {
            TIFLASH_BRANCH = "${m4[0][1]}"
        }
        m4 = null
        println "TIFLASH_BRANCH=${TIFLASH_BRANCH}"
        println "debug command:\nkubectl -n jenkins-tiflow exec -ti ${NODE_NAME} -c golang bash"
        def tidb_sha1 = TIDB_BRANCH
        if (!(TIDB_BRANCH =~ COMMIT_SHA1_REG)) {
            tidb_sha1 = sh(returnStdout: true, script: "curl -f ${FILE_SERVER_URL}/download/refs/pingcap/tidb/${TIDB_BRANCH}/sha1").trim()
        }
        def tikv_sha1 = TIKV_BRANCH
        if (!(TIKV_BRANCH =~ COMMIT_SHA1_REG)) {
            tikv_sha1 = sh(returnStdout: true, script: "curl -f ${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_BRANCH}/sha1").trim()
        }
        def pd_sha1 = PD_BRANCH
        if (!(PD_BRANCH =~ COMMIT_SHA1_REG)) {
            pd_sha1 = sh(returnStdout: true, script: "curl -f ${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1").trim()
        }
        def tiflash_sha1 = TIFLASH_COMMIT
        if (TIFLASH_COMMIT == null) {
            tiflash_sha1 = sh(returnStdout: true, script: "curl -f ${FILE_SERVER_URL}/download/refs/pingcap/tiflash/${TIFLASH_BRANCH}/sha1").trim()
        }
        def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz"
        def tikv_url = "${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz"
        def pd_url = "${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz"
        def tiflash_url = "${FILE_SERVER_URL}/download/builds/pingcap/tiflash/${TIFLASH_BRANCH}/${tiflash_sha1}/centos7/tiflash.tar.gz"

        // If it is triggered upstream, the upstream link is used.
        def from = params.getOrDefault("triggered_by_upstream_pr_ci", "")
        switch (from) {
            case "tikv":
                def tikv_download_link = params.upstream_pr_ci_override_tikv_download_link
                println "Use the upstream download link, upstream_pr_ci_override_tikv_download_link=${tikv_download_link}"
                tikv_url = tikv_download_link
                break;
            case "tidb":
                def tidb_download_link = params.upstream_pr_ci_override_tidb_download_link
                println "Use the upstream download link, upstream_pr_ci_override_tidb_download_link=${tidb_download_link}"
                tidb_url = tidb_download_link
                // Because the tidb archive is packaged differently on pr than on the branch build,
                // we have to use a different unzip path.
                tidb_archive_path = "./bin/tidb-server"
                println "tidb_archive_path=${tidb_archive_path}"
                break;
        }
        def sync_diff_download_url = "${FILE_SERVER_URL}/download/builds/pingcap/cdc/sync_diff_inspector_hash-79f1fd1e_linux-amd64.tar.gz"
        if (ghprbTargetBranch.startsWith("release-") && ghprbTargetBranch < "release-6.0" ) {
            println "release branch detected, use the other sync_diff version"
            sync_diff_download_url = "http://fileserver.pingcap.net/download/builds/pingcap/cdc/new_sync_diff_inspector.tar.gz"
        }

        println "tidb_url: ${tidb_url}"
        println "tidb_archive_path: ${tidb_archive_path}"
        println "cacheBinaryPath: ${cacheBinaryPath}"
        println "sync_diff_download_url: ${sync_diff_download_url}"
        container("golang") {
            sh """
                mkdir -p third_bin
                mkdir -p tmp
                mkdir -p bin
                tidb_url="${tidb_url}"
                tidb_archive_path="${tidb_archive_path}"
                tikv_url="${tikv_url}"
                pd_url="${pd_url}"
                tiflash_url="${tiflash_url}"
                minio_url="${FILE_SERVER_URL}/download/minio.tar.gz"
                schema_registry_url="${FILE_SERVER_URL}/download/builds/pingcap/cdc/schema-registry.tar.gz"

                wget -q --retry-connrefused --waitretry=1 --read-timeout=120 --timeout=150 -t 3 \${tidb_url}
                tar -xz -C ./tmp \${tidb_archive_path} -f tidb-server.tar.gz && mv tmp/bin/tidb-server third_bin/

                wget -q --retry-connrefused --waitretry=1 --read-timeout=120 --timeout=150 -t 3 -O pd-server.tar.gz  \${pd_url}
                wget -q --retry-connrefused --waitretry=1 --read-timeout=120 --timeout=150 -t 3 -O tikv-server.tar.gz \${tikv_url}
                tar -xz -C ./tmp 'bin/*' -f tikv-server.tar.gz && mv tmp/bin/* third_bin/
                tar -xz -C ./tmp 'bin/*' -f pd-server.tar.gz && mv tmp/bin/* third_bin/

                wget -q --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 -t 3 -O minio.tar.gz \${minio_url}
                tar -xz -C third_bin -f ./minio.tar.gz

                wget -q --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 -t 3 -O schema-registry.tar.gz \${schema_registry_url}
                tar -xz -C third_bin -f schema-registry.tar.gz
                mv third_bin/schema-registry third_bin/_schema_registry
	            mv third_bin/_schema_registry/* third_bin && rm -rf third_bin/_schema_registry

                wget -q --retry-connrefused --waitretry=1 --read-timeout=120 --timeout=150 -t 3 -O  tiflash.tar.gz \${tiflash_url}
                tar -xz -C third_bin -f tiflash.tar.gz
                mv third_bin/tiflash third_bin/_tiflash
                mv third_bin/_tiflash/* third_bin

                wget -q --retry-connrefused --waitretry=1 --read-timeout=120 --timeout=150 -t 3 -O third_bin/go-ycsb ${FILE_SERVER_URL}/download/builds/pingcap/go-ycsb/test-br/go-ycsb
                wget -q --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 -t 3 -O third_bin/jq ${FILE_SERVER_URL}/download/builds/pingcap/test/jq-1.6/jq-linux64

                wget -q --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 -t 3 -O etcd.tar.gz ${FILE_SERVER_URL}/download/builds/pingcap/cdc/etcd-v3.4.7-linux-amd64.tar.gz
                tar -xz -C third_bin  etcd-v3.4.7-linux-amd64/etcdctl  -f etcd.tar.gz
                mv third_bin/etcd-v3.4.7-linux-amd64/etcdctl third_bin/ && rm -rf third_bin/etcd-v3.4.7-linux-amd64

                wget -q --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 -t 3 -O sync_diff_inspector.tar.gz ${sync_diff_download_url}
                tar -xz -C third_bin -f sync_diff_inspector.tar.gz

                chmod a+x third_bin/*
                rm -rf tmp

                wget -q --retry-connrefused --waitretry=1 --read-timeout=120 --timeout=150 -t 3 -O ticdc_bin.tar.gz ${FILE_SERVER_URL}/download/${cacheBinaryPath}
                tar -xvz -C ./ -f ticdc_bin.tar.gz

                mv ./third_bin/* ./bin && ls -lh ./bin
                rm -rf third_bin
            """
            stash includes: "bin/**", name: "third_bins", useDefaultExcludes: false
        }

    }
}


/**
 * Collect and calculate test coverage.
 */
def coverage() {
    stage('Coverage') {
        node("lightweight_pod") {
            def ws = pwd()
            deleteDir()
            unstash 'ticdc'

            // unstash all integration tests.
            def step_names = []
            for (int i = 1; i < TOTAL_COUNT; i++) {
                step_names.add("integration_test_step_${i}")
            }
            step_names.each { item ->
                unstash item
            }

            // tar the coverage files and upload to file server.
            def tiflowCoverageFile = "test/cdc/ci/integration_test/${ghprbActualCommit}/tiflow_coverage.tar.gz"
            sh """
            tar -czf tiflow_coverage.tar.gz go/src/github.com/pingcap/tiflow
            curl -F ${tiflowCoverageFile}=@tiflow_coverage.tar.gz http://fileserver.pingcap.net/upload
            """

            def params_downstream_coverage_pipeline = [
                string(name: "COVERAGE_FILE", value: "${FILE_SERVER_URL}/download/${tiflowCoverageFile}"),
                string(name: "CI_BUILD_NUMBER", value: "${BUILD_NUMBER}"),
                string(name: "CI_BUILD_URL", value: "${RUN_DISPLAY_URL}"),
                string(name: "CI_BRANCH", value: "${ghprbTargetBranch}"),
                string(name: "CI_PULL_REQUEST", value: "${ghprbPullId}"),
            ]
            println "params_downstream_coverage_pipeline=${params_downstream_coverage_pipeline}"
            build job: "cdc_ghpr_downstream_coverage",
                wait: false,
                parameters: params_downstream_coverage_pipeline

            dir("go/src/github.com/pingcap/tiflow") {
                container("golang") {
                    archiveArtifacts artifacts: 'cov_dir/*', fingerprint: true
                }
            }
        }
    }
}

return this
