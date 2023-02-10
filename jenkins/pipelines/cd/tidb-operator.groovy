package cd

String GitHash
String ReleaseTag
boolean PushPublic
def EnableE2E = false



final CHART_ITEMS = 'tidb-operator tidb-cluster tidb-backup tidb-drainer tidb-lightning tikv-importer'
final TOOLS_BUILD_DIR = 'output/tkctl'
final CHARTS_BUILD_DIR = 'output/chart'
final K8S_CLUSTER = "kubernetes"
final K8S_NAMESPACE="jenkins-tidb-operator"

final dindYaml = '''
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: builder
    image: hub.pingcap.net/jenkins/docker-builder
    args: ["sleep", "infinity"]
    env:
    - name: DOCKER_HOST
      value: tcp://localhost:2375
  - name: dind
    image: docker:dind
    args: ["--registry-mirror=https://registry-mirror.pingcap.net"]
    env:
    - name: REGISTRY
      value: hub.pingcap.net
    - name: DOCKER_TLS_CERTDIR
      value: ""
    - name: DOCKER_HOST
      value: tcp://localhost:2375
    securityContext:
      privileged: true
    tty: true
    readinessProbe:
      exec:
        command: ["docker", "info"]
      initialDelaySeconds: 10
      failureThreshold: 6
'''
final goBuildYaml = '''
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: builder
    image: hub.pingcap.net/jenkins/centos7_golang-1.16:latest
    args: ["sleep", "infinity"]
    resources:
      requests:
        memory: "8Gi"
        cpu: "4"
      limits:
        memory: "32Gi"
        cpu: "16"
  tolerations:
  - effect: NoSchedule
    key: tidb-operator
    operator: Exists
'''
final dockerSyncYaml = '''
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: regctl
    image: hub.pingcap.net/jenkins/regctl
    args: ["sleep", "infinity"]
  tolerations:
  - effect: NoSchedule
    key: tidb-operator
    operator: Exists
'''
final uploaderYaml = '''
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: uploader
    image: hub.pingcap.net/jenkins/uploader
    args: ["sleep", "infinity"]
  - name: helm
    image: hub.pingcap.net/jenkins/helm:2.14.1
    args: ["sleep", "infinity"]
  tolerations:
  - effect: NoSchedule
    key: tidb-operator
    operator: Exists
'''


pipeline {
    agent none
    parameters {
        string(name: 'GitRef', defaultValue: 'master', description: 'branch or commit hash')
        string(name: 'ReleaseTag', defaultValue: 'test', description: 'empty means the same with GitRef')
    }
    stages {
        stage("PARAMS") {
            steps {
                script {
                    ReleaseTag = params.ReleaseTag
                    if (!ReleaseTag) {
                        ReleaseTag = params.GitRef
                    }
                    PushPublic = true
                    println("ReleaseTag: $ReleaseTag")
                    println("PushPublic: $PushPublic")
                }
            }
        }
        stage("BUILD") {
            stages {
                stage("Bin") {
                    agent {
                        kubernetes {
                            yaml goBuildYaml
                            defaultContainer 'builder'
                            cloud K8S_CLUSTER
                            namespace K8S_NAMESPACE
                        }
                    }
                    stages {
                        stage("checkout") {
                            steps {
                                script {
                                    def scmVars = checkout changelog: false, poll: false, scm: [
                                            $class           : 'GitSCM',
                                            branches         : [[name: "${params.GitRef}"]],
                                            userRemoteConfigs: [[
                                                                        refspec: '+refs/heads/*:refs/remotes/origin/* +refs/pull/*:refs/remotes/origin/pull/*',
                                                                        url    : 'https://github.com/pingcap/tidb-operator.git',
                                                                ]]
                                    ]
                                    GitHash = scmVars.GIT_COMMIT
                                }
                                println("GitHash: $GitHash")
                            }
                        }
                        stage("bin") {
                            environment {GIT_COMMIT = "$GitHash"; GOPROXY = "http://goproxy.pingcap.net,https://proxy.golang.org,direct" }
                            steps {
                                sh """set -eux
                                    go mod download
                                    git checkout -- go.sum
                                    """
                                sh "git status"
                                sh "GOOS=linux GOARCH=arm64 make build"
                                sh "GOOS=linux GOARCH=amd64 make build"
                                // todo only x86
                                sh "GOOS=linux GOARCH=amd64 make debug-build"

                                script {
                                    sh "mkdir -p ${TOOLS_BUILD_DIR}"
                                    for (OS in ["linux", "darwin", "windows"]) {
                                        for (ARCH in ["amd64", "arm64"]) {
                                            if ("$OS/$ARCH" == "windows/arm64") {
                                                // unsupported platform
                                                continue
                                            }
                                            sh """
                                            TKCTL_CLI_PACKAGE="tkctl-${OS}-${ARCH}-${ReleaseTag}"
                                            GOOS=${OS} GOARCH=${ARCH} make cli
                                            tar -czf ${TOOLS_BUILD_DIR}/\${TKCTL_CLI_PACKAGE}.tgz tkctl
                                            sha256sum ${TOOLS_BUILD_DIR}/\${TKCTL_CLI_PACKAGE}.tgz > ${TOOLS_BUILD_DIR}/\${TKCTL_CLI_PACKAGE}.sha256
                                            """
                                        }
                                    }
                                }
                            }
                        }
                        stage("e2e bin") {
                            when { expression { EnableE2E } }
                            steps {
                                sh "make e2e-build"
                            }
                        }
                        stage("upload code coverage") {
                            when { equals expected: 'master', actual: params.GitRef }

                            environment { CODECOV_TOKEN = credentials('tp-codecov-token') }
                            steps {
                                catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                                    sh "make test GOFLAGS='-race' GO_COVER=y"
                                    sh 'curl -s https://codecov.io/bash | bash -s - -t ${CODECOV_TOKEN} || echo "Codecov did not collect coverage reports"'
                                }
                            }
                        }
                        stage("charts") {
                            steps {
                                sh """
                        	mkdir ${CHARTS_BUILD_DIR}
				for chartItem in ${CHART_ITEMS}
				do
					chartPrefixName=\$chartItem-${ReleaseTag}
					sed -i "s/version:.*/version: ${ReleaseTag}/g" charts/\$chartItem/Chart.yaml
					sed -i "s/appVersion:.*/appVersion: ${ReleaseTag}/g" charts/\$chartItem/Chart.yaml
                            		# update image tag to current release
                            		sed -r -i "s#pingcap/(tidb-operator|tidb-backup-manager):.*#pingcap/\\1:${ReleaseTag}#g" charts/\$chartItem/values.yaml
					tar -zcf ${CHARTS_BUILD_DIR}/\${chartPrefixName}.tgz -C charts \$chartItem
					sha256sum ${CHARTS_BUILD_DIR}/\${chartPrefixName}.tgz > ${CHARTS_BUILD_DIR}/\${chartPrefixName}.sha256
				done
				cp -R charts ${CHARTS_BUILD_DIR}/charts
				"""
                            }
                        }
                        stage("stash") {
                            steps {
                                stash name: "bin", includes: "Makefile,tests/images/e2e/bin/,images/,${TOOLS_BUILD_DIR}/,${CHARTS_BUILD_DIR}/,misc/images/"
                            }
                        }
                    }
                }
                stage("Images") {
                    agent {
                        kubernetes {
                            yaml dindYaml
                            defaultContainer 'builder'
                            cloud K8S_CLUSTER
                            namespace K8S_NAMESPACE
                        }
                    }
                    stages {
                        stage("prepare") {
                            environment { HUB = credentials('harbor-pingcap') }
                            steps {
                                unstash "bin"
                                sh "ls -Ral"
                                sh 'printenv HUB_PSW | docker login -u $HUB_USR --password-stdin hub.pingcap.net'
                                writeFile file: 'buildkitd.toml', text: '''[registry."docker.io"]\nmirrors = ["registry-mirror.pingcap.net"]'''
                                sh 'docker buildx create --name mybuilder --use --config buildkitd.toml && rm buildkitd.toml'
                            }
                        }
                        stage("e2e") {
                            when { expression { EnableE2E } }
                            steps {
                                sh """set -ex
                                      NO_BUILD=y GOARCH=amd64 DOCKER_REPO=hub.pingcap.net/tidb-operator-e2e Image_Tag=${ReleaseTag} make docker-push # e2e-docker-push
                                      echo "info: download binaries for e2e"
                                      SKIP_BUILD=y SKIP_IMAGE_BUILD=y SKIP_UP=y SKIP_TEST=y SKIP_DOWN=y ./hack/e2e.sh
                                   """
                            }
                        }
                        stage("operator") {
                            steps {
                                sh "docker buildx build --platform=linux/arm64,linux/amd64 --build-arg BUILDKIT_INLINE_CACHE=1 --cache-from hub.pingcap.net/rc/tidb-operator --push -t hub.pingcap.net/rc/tidb-operator:${ReleaseTag} images/tidb-operator/"
                            }
                        }
                        stage("backup manager") {
                            steps {
                                sh "docker buildx build --platform=linux/arm64,linux/amd64 --build-arg BUILDKIT_INLINE_CACHE=1 --cache-from hub.pingcap.net/rc/tidb-backup-manager --push -t hub.pingcap.net/rc/tidb-backup-manager:${ReleaseTag} images/tidb-backup-manager/"
                            }
                        }
                        stage("debug helper images") {
                            // todo only x86
                            steps {
                                script {
                                    ["debug-launcher", "tidb-control", "tidb-debug"].each {
                                        sh """
                                           docker buildx build --build-arg BUILDKIT_INLINE_CACHE=1 --cache-from hub.pingcap.net/rc/${it} --platform=linux/amd64 --push -t hub.pingcap.net/rc/${it}:${ReleaseTag} misc/images/${it}
                                           """
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        stage("ASK") {
            agent {
                kubernetes {
                    yaml uploaderYaml
                    defaultContainer 'uploader'
                    cloud K8S_CLUSTER
                    namespace K8S_NAMESPACE
                }
            }
            steps {
                unstash "bin"
                println("these images to publish are hub.pingcap.net/rc/[debug-launcher, tidb-control, tidb-debug, tidb-operator, tidb-backup-manager]:$ReleaseTag")
                println("the charts to publish are in workspace $CHARTS_BUILD_DIR")
                println("the tools to publish are in workspace $TOOLS_BUILD_DIR")
                input("continue?")
            }
        }
        stage("RELEASE") {
            options { retry(3) }
            stages {
                stage("Publish Images") {
                    agent {
                        kubernetes {
                            yaml dockerSyncYaml
                            defaultContainer 'regctl'
                            cloud K8S_CLUSTER
                            namespace K8S_NAMESPACE
                        }
                    }
                    stages {
                        stage("sync to registry") {
                            matrix {
                                axes {
                                    axis {
                                        name 'component'
                                        values 'debug-launcher', 'tidb-control', 'tidb-debug', 'tidb-operator', 'tidb-backup-manager'
                                    }
                                }
                                stages {
                                    stage("harbor") {
                                        environment { HUB = credentials('harbor-pingcap') }
                                        steps {
                                            sh 'set +x; regctl registry login hub.pingcap.net -u $HUB_USR -p $(printenv HUB_PSW)'
                                            sh "regctl image copy hub.pingcap.net/rc/${component}:${ReleaseTag}  hub.pingcap.net/release/${component}:${ReleaseTag}"
                                        }
                                    }
                                    stage("gcr") {
                                        environment { HUB = credentials('gcr-registry-key') }
                                        steps {
                                            sh 'set +x; regctl registry login gcr.io -u _json_key -p "$(cat $(printenv HUB))"'
                                            sh "regctl image copy hub.pingcap.net/rc/${component}:${ReleaseTag}  gcr.io/pingcap-public/dbaas/${component}:${ReleaseTag}"
                                        }
                                    }
                                    stage("aliyun") {
                                        when{expression{PushPublic}}
                                        environment { HUB = credentials('ACR_TIDB_ACCOUNT') }
                                        steps {
                                            sh 'set +x; regctl registry login registry.cn-beijing.aliyuncs.com -u $HUB_USR -p $(printenv HUB_PSW)'
                                            sh "regctl image copy hub.pingcap.net/rc/${component}:${ReleaseTag}  registry.cn-beijing.aliyuncs.com/tidb/${component}:${ReleaseTag}"
                                        }
                                    }
                                    stage("dockerhub") {
                                        when{expression{PushPublic}}
                                        environment { HUB = credentials('dockerhub-pingcap') }
                                        steps {
                                            sh 'set +x; regctl registry login docker.io -u $HUB_USR -p $(printenv HUB_PSW)'
                                            sh "regctl image copy hub.pingcap.net/rc/${component}:${ReleaseTag}  docker.io/pingcap/${component}:${ReleaseTag}"
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                stage("Publish Files") {
                    when{expression{PushPublic}}
                    agent {
                        kubernetes {
                            yaml uploaderYaml
                            defaultContainer 'uploader'
                            cloud K8S_CLUSTER
                            namespace K8S_NAMESPACE
                        }
                    }
                    environment {
                        QINIU_ACCESS_KEY = credentials('qn_access_key');
                        QINIU_SECRET_KEY = credentials('qiniu_secret_key');
                    }
                    stages {
                        stage("charts") {
                            environment {
                                QINIU_BUCKET_NAME = "charts";
                            }
                            steps {
                                unstash "bin"
                                sh """
                                    cd ${CHARTS_BUILD_DIR}
                                    for chartItem in ${CHART_ITEMS}
                                    do
                                        chartPrefixName=\$chartItem-${ReleaseTag}
                                        upload_qiniu.py \${chartPrefixName}.tgz \${chartPrefixName}.tgz
                                        upload_qiniu.py \${chartPrefixName}.sha256 \${chartPrefixName}.sha256
                                    done
                                    """
                            }
                        }
                        stage("charts index") {
                            when { expression { !(ReleaseTag in ["latest", "nightly", "test"]) } }
                            environment {
                                QINIU_BUCKET_NAME = "charts";
                            }
                            steps {
                                dir(CHARTS_BUILD_DIR){
                                    sh "curl http://charts.pingcap.org/index.yaml -o index.yaml"
				    container("helm") {
                                        sh "helm repo index . --url http://charts.pingcap.org/ --merge index.yaml"
                                    }
                                    sh "cat index.yaml"
                                    sh "upload_qiniu.py index.yaml index.yaml"
                                }
                            }
                        }
                        stage("tkcli") {
                            environment {
                                QINIU_BUCKET_NAME = "tidb";
                            }
                            steps {
                                script {
                                    for (OS in ["linux", "darwin", "windows"]) {
                                        for (ARCH in ["amd64", "arm64"]) {
                                            if ("$OS/$ARCH" == "windows/arm64") {
                                                // unsupported platform
                                                continue
                                            }
                                            sh """
                                                TKCTL_CLI_PACKAGE="tkctl-${OS}-${ARCH}-${ReleaseTag}"
                                                cd ${TOOLS_BUILD_DIR}
                                                upload_qiniu.py \${TKCTL_CLI_PACKAGE}.tgz \${TKCTL_CLI_PACKAGE}.tgz
                                                upload_qiniu.py \${TKCTL_CLI_PACKAGE}.sha256 \${TKCTL_CLI_PACKAGE}.sha256
                                               """
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
