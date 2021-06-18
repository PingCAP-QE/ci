if (params.containsKey("release_test")) {
    echo "this build is triggered by qa for release testing"
    ghprbTargetBranch = params.getOrDefault("release_test__ghpr_target_branch", params.release_test__release_branch)
    ghprbCommentBody = params.getOrDefault("release_test__ghpr_comment_body", "")
    ghprbActualCommit = params.getOrDefault("release_test__ghpr_actual_commit", params.release_test__tidb_commit)
    ghprbPullId = params.getOrDefault("release_test__ghpr_pull_id", "")
    ghprbPullTitle = params.getOrDefault("release_test__ghpr_pull_title", "")
    ghprbPullLink = params.getOrDefault("release_test__ghpr_pull_link", "")
    ghprbPullDescription = params.getOrDefault("release_test__ghpr_pull_description", "")
}

def checkoutTiCS(commit, pullId) {
    def refspec = "+refs/heads/*:refs/remotes/origin/*"
    if (pullId) {
        refspec += " +refs/pull/${pullId}/*:refs/remotes/origin/pr/${pullId}/*"
    }
    checkout(changelog: false, poll: false, scm: [
            $class: "GitSCM",
            branches: [
                    [name: "${commit}"],
            ],
            userRemoteConfigs: [
                    [
                            url: "git@github.com:pingcap/tics.git",
                            refspec: refspec,
                            credentialsId: "github-sre-bot-ssh",
                    ]
            ],
            extensions: [
                    [$class: 'PruneStaleBranch'],
                    [$class: 'CleanBeforeCheckout'],
            ],
    ])
}

def run(label, Closure body) {
    podTemplate(name: label, label: label, instanceCap: 20, nodeSelector: "role_type=slave", containers: [
            containerTemplate(name: 'dockerd', image: 'docker:18.09.6-dind', privileged: true,
                            resourceRequestCpu: '5000m', resourceRequestMemory: '10Gi',
                            resourceLimitCpu: '16000m', resourceLimitMemory: '32Gi'),
            containerTemplate(name: 'docker', image: 'hub.pingcap.net/zyguan/docker:build-essential-java',
                            alwaysPullImage: true, envVars: [
                                    envVar(key: 'DOCKER_HOST', value: 'tcp://localhost:2375'),
                            ], ttyEnabled: true, command: 'cat'),
    ]) { node(label) {
        println("${NODE_NAME}")
        body()
    } }
}

def fallback() {
    catchError {

        def label = "tidb-test-tics"
        def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/tidb-server.tar.gz"
        def tidb_done_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/done"

        def TIKV_BRANCH = ghprbTargetBranch
        def PD_BRANCH = ghprbTargetBranch
        def TICS_BRANCH = ghprbTargetBranch
        def TIDB_BRANCH = ghprbTargetBranch

        // parse tikv branch
        def m1 = ghprbCommentBody =~ /tikv\s*=\s*([^\s\\]+)(\s|\\|$)/
        if (m1) {
            TIKV_BRANCH = "${m1[0][1]}"
        }
        m1 = null
        println "TIKV_BRANCH=${TIKV_BRANCH}"

        // parse pd branch
        def m2 = ghprbCommentBody =~ /pd\s*=\s*([^\s\\]+)(\s|\\|$)/
        if (m2) {
            PD_BRANCH = "${m2[0][1]}"
        }
        m2 = null
        println "PD_BRANCH=${PD_BRANCH}"

        // parse tics branch
        def m3 = ghprbCommentBody =~ /tics\s*=\s*([^\s\\]+)(\s|\\|$)/
        if (m3) {
            TICS_BRANCH = "${m3[0][1]}"
        }
        //debug
		//TICS_BRANCH = "master"
        println "TICS_BRANCH=${TICS_BRANCH}"
        m3 = null
        // run test logic on branch master / release-4.0 / release-5.x
        if (TIDB_BRANCH !="master" && TIDB_BRANCH !="release-4.0" && !TIDB_BRANCH.startsWith("release-5.")){
        	return
        }

        parallel(
            "TiCS Test": {
                run(label) {
                    dir("tics") {
                        stage("Checkout") {
                            container("docker") {
                            
                                sh """
                                archive_url=${FILE_SERVER_URL}/download/builds/pingcap/tics/cache/tics-repo_latest.tar.gz
                                if [ ! -d contrib ]; then curl -sL \$archive_url | tar -zx --strip-components=1 || true; fi
                                echo http://dl-cdn.alpinelinux.org/alpine/edge/testing >> /etc/apk/repositories
                                apk add --update --no-cache lcov
                                """
                                sh "chown -R 1000:1000 ./"
                                sh """
                                # if ! grep -q hub.pingcap.net /etc/hosts ; then echo '172.16.10.5 hub.pingcap.net' >> /etc/hosts; fi
                                if [ -d '../tiflash/tests/maven' ]; then
                                    cd '../tiflash/tests/maven'
                                    docker-compose down || true
                                    cd -
                                fi
                                """
                            }
                            //checkoutTiCS("${TICS_BRANCH}", "${ghprbPullId}")
                            checkoutTiCS("${TICS_BRANCH}", null)
                        }
                        stage("Test") {
                            timeout(time: 10, unit: 'MINUTES') {
                                container("docker") {
                                	def tikvTag= TIKV_BRANCH
                                	def pdTag = PD_BRANCH
                                	def ticsTag = TICS_BRANCH
                                    sh """
                                    while ! docker pull hub.pingcap.net/tiflash/tics:${TICS_BRANCH}; do sleep 60; done
                                    """
									dir("tidbtmp") {
										deleteDir()
									    timeout(5) {
				                             sh """
                                             while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 5; done
                                             curl ${tidb_url} | tar xz
                                             """
									    }
									    sh """
									    printf 'FROM registry-mirror.pingcap.net/pingcap/alpine-glibc \n
                                        RUN apk add --no-cache curl \n
                                        RUN curl -sL -o /usr/local/bin/dumb-init https://github.com/Yelp/dumb-init/releases/download/v1.2.2/dumb-init_1.2.2_amd64 && chmod +x /usr/local/bin/dumb-init \n
                                        COPY bin/tidb-server /tidb-server \n
                                        WORKDIR / \n
                                        EXPOSE 4000 \n
                                        ENTRYPOINT ["/usr/local/bin/dumb-init", "/tidb-server"] \n' > Dockerfile
									    """
									    sh "docker build -t hub.pingcap.net/qa/tidb:${TIDB_BRANCH} -f Dockerfile ."
									}
									if (TIKV_BRANCH.contains("pr")){
										dir("tikvtmp"){
										    deleteDir()
										    timeout(5) {
					                            sh """
					                            tikv_sha1=`curl "${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_BRANCH}/sha1"`
					                            tikv_url="${FILE_SERVER_URL}/download/builds/pingcap/tikv/\${tikv_sha1}/centos7/tikv-server.tar.gz"										
					                            while ! curl --output /dev/null --silent --head --fail \${tikv_url}; do sleep 15; done
					                            curl \${tikv_url} | tar xz
					                            """ 										        
										    }
										    sh """
										    printf 'FROM registry-mirror.pingcap.net/pingcap/alpine-glibc \n
	                                        COPY bin/tikv-ctl /tikv-ctl \n
	                                        COPY bin/tikv-server /tikv-server \n
	                                        EXPOSE 20160 20180 \n
	                                        ENTRYPOINT ["/tikv-server"] \n' > Dockerfile
										    """
										    tikvTag = TIKV_BRANCH.replace("/","-")
										    sh "docker build -t hub.pingcap.net/qa/tikv:${tikvTag} -f Dockerfile ."            					    
										}									    
									}
									if (PD_BRANCH.contains("pr")){
										dir("pdtmp"){
										    deleteDir()
										    timeout(5) {										    
					                            sh """
					                            pd_sha1=`curl "${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1"`
					                            pd_url="${FILE_SERVER_URL}/download/builds/pingcap/pd/\${pd_sha1}/centos7/pd-server.tar.gz"
					
					                            while ! curl --output /dev/null --silent --head --fail \${pd_url}; do sleep 15; done
					                            curl \${pd_url} | tar xz
					                            """                    
				                            }
										    sh """
										    printf 'FROM registry-mirror.pingcap.net/pingcap/alpine-glibc \n
	                                        COPY bin/pd-server /pd-server \n
	                                        COPY bin/pd-ctl /pd-ctl \n
	                                        COPY bin/pd-recover /pd-recover \n
	                                        EXPOSE 2379 2380 \n
	                                        ENTRYPOINT ["/pd-server"] \n' > Dockerfile
										    """
										    pdTag = PD_BRANCH.replace("/","-")
										    sh "docker build -t hub.pingcap.net/qa/pd:${pdTag} -f Dockerfile ."				                                    									    
										}									    
									}
									if (TICS_BRANCH.contains("pr")){									
									    ticsTag=TICS_BRANCH.replace("/","-")									    									   
									}

                                    dir("tests/docker") {
                                        try {
                                            //sh "TIDB_CI_ONLY=1 TAG=${TICS_BRANCH} BRANCH=${TICS_BRANCH} bash -xe run.sh"
                                            sh "TIDB_CI_ONLY=1 TAG=${ticsTag} PD_BRANCH=${pdTag} TIKV_BRANCH=${tikvTag} TIDB_BRANCH=${TIDB_BRANCH} bash -xe run.sh"
                                        } catch(e) {
                                            archiveArtifacts(artifacts: "log/**/*.log", allowEmptyArchive: true)
                                            sh "find log -name '*.log' | xargs tail -n 50"
                                            sh "docker ps -a"
                                            throw e
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
        )

        currentBuild.result = "SUCCESS"
    }

    stage("upload status"){
        node("master") {
            sh """curl --connect-timeout 2 --max-time 4 -d '{"job":"$JOB_NAME","id":$BUILD_NUMBER}' http://172.16.5.25:36000/api/v1/ci/job/sync || true"""
        }
    }

    if (params.containsKey("triggered_by_upstream_ci")) {
        stage("update commit status") {
            node("master") {
                if (currentBuild.currentResult == "ABORTED") {
                    PARAM_DESCRIPTION = 'Jenkins job aborted'
                    // Commit state. Possible values are 'pending', 'success', 'error' or 'failure'
                    PARAM_STATUS = 'error'
                } else if (currentBuild.currentResult == "FAILURE") {
                    PARAM_DESCRIPTION = 'Jenkins job failed'
                    PARAM_STATUS = 'failure'
                } else if (currentBuild.currentResult == "SUCCESS") {
                    PARAM_DESCRIPTION = 'Jenkins job success'
                    PARAM_STATUS = 'success'
                } else {
                    PARAM_DESCRIPTION = 'Jenkins job meets something wrong'
                    PARAM_STATUS = 'error'
                }
                def default_params = [
                        string(name: 'TIDB_COMMIT_ID', value: ghprbActualCommit ),
                        string(name: 'CONTEXT', value: 'idc-jenkins-ci-tidb/tics-test'),
                        string(name: 'DESCRIPTION', value: PARAM_DESCRIPTION ),
                        string(name: 'BUILD_URL', value: RUN_DISPLAY_URL ),
                        string(name: 'STATUS', value: PARAM_STATUS ),
                ]
                echo("default params: ${default_params}")
                build(job: "tidb_update_commit_status", parameters: default_params, wait: true)
            }
        }
    }

    stage('Summary') {
        echo "Send slack here ..."
        def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
        def slackmsg = "[#${ghprbPullId}: ${ghprbPullTitle}]" + "\n" +
        "${ghprbPullLink}" + "\n" +
        "${ghprbPullDescription}" + "\n" +
        "Build Result: `${currentBuild.currentResult}`" + "\n" +
        "Elapsed Time: `${duration} mins` " + "\n" +
        "${env.RUN_DISPLAY_URL}"

        if (currentBuild.currentResult != "SUCCESS") {
            slackSend channel: '#jenkins-ci', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
        }
	}
}


fallback()