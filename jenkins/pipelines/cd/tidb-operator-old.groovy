builderYaml = '''
apiVersion: v1
kind: Pod
metadata:
  labels:
    app: tidb-operator-e2e
spec:
  containers:
  - name: main
    image: hub.pingcap.net/tidb-operator/kubekins-e2e:v20210808-1eaeec7-master
    command:
    - runner.sh
    # Clean containers on TERM signal in root process to avoid cgroup leaking.
    # https://github.com/pingcap/tidb-operator/issues/1603#issuecomment-582402196
    - exec
    - bash
    - -c
    - |
      function clean() {
        echo "info: clean all containers to avoid cgroup leaking"
        docker kill `docker ps -q` || true
        docker system prune -af || true
      }
      function setup_docker_mirror() {
        sed -i "s/mirror.gcr.io/registry-mirror.pingcap.net/g" /etc/default/docker
        service docker restart
      }
      setup_docker_mirror
      trap clean TERM
      sleep 1d & wait
    # we need privileged mode in order to do docker in docker
    securityContext:
      privileged: true
    env:
    - name: DOCKER_IN_DOCKER_ENABLED
      value: "true"
    resources:
      requests:
        cpu: "6"
        memory: "10Gi"
        ephemeral-storage: 150Gi
      limits:
        cpu: "8"
        memory: "32Gi"
        ephemeral-storage: 150Gi
    # kind needs /lib/modules and cgroups from the host
    volumeMounts:
    - mountPath: /lib/modules
      name: modules
      readOnly: true
    - mountPath: /sys/fs/cgroup
      name: cgroup
    # dind expects /var/lib/docker to be volume
    - name: docker-root
      mountPath: /var/lib/docker
    # legacy docker path for cr.io/k8s-testimages/kubekins-e2e
    - name: docker-graph
      mountPath: /docker-graph
    # use memory storage for etcd hostpath in kind cluster
    - name: etcd-data-dir
      mountPath: /mnt/tmpfs/etcd
  volumes:
  - name: modules
    hostPath:
      path: /lib/modules
      type: Directory
  - name: cgroup
    hostPath:
      path: /sys/fs/cgroup
      type: Directory
  - name: docker-root
    emptyDir: {}
  - name: docker-graph
    emptyDir: {}
  - name: etcd-data-dir
    emptyDir:
      medium: Memory
  tolerations:
  - effect: NoSchedule
    key: tidb-operator
    operator: Exists
  affinity:
    podAntiAffinity:
      preferredDuringSchedulingIgnoredDuringExecution:
      - weight: 100
        podAffinityTerm:
          labelSelector:
            matchExpressions:
            - key: app
              operator: In
              values:
              - tidb-operator-e2e
          topologyKey: kubernetes.io/hostname
'''

e2eTestPodYaml = '''
apiVersion: v1
kind: Pod
metadata:
  namespace: "jenkins-tidb-operator"
  labels:
    app: tidb-operator-e2e
spec:
  containers:
  - name: main
    image: hub.pingcap.net/tidb-operator/kubekins-e2e:v20210808-1eaeec7-master
    command:
    - runner.sh
    # Clean containers on TERM signal in root process to avoid cgroup leaking.
    # https://github.com/pingcap/tidb-operator/issues/1603#issuecomment-582402196
    - exec
    - bash
    - -c
    - |
      function clean() {
        echo "info: clean all containers to avoid cgroup leaking"
        docker kill `docker ps -q` || true
        docker system prune -af || true
      }
      function setup_docker_mirror() {
        sed -i "s/mirror.gcr.io/registry-mirror.pingcap.net/g" /etc/default/docker
        service docker restart
      }
      setup_docker_mirror
      trap clean TERM
      sleep 1d & wait
    # we need privileged mode in order to do docker in docker
    securityContext:
      privileged: true
    env:
    - name: DOCKER_IN_DOCKER_ENABLED
      value: "true"
    resources:
      requests:
        cpu: "6"
        memory: "10Gi"
        ephemeral-storage: 150Gi
      limits:
        cpu: "8"
        memory: "16Gi"
        ephemeral-storage: 150Gi
    # kind needs /lib/modules and cgroups from the host
    volumeMounts:
    - mountPath: /lib/modules
      name: modules
      readOnly: true
    - mountPath: /sys/fs/cgroup
      name: cgroup
    # dind expects /var/lib/docker to be volume
    - name: docker-root
      mountPath: /var/lib/docker
    # legacy docker path for cr.io/k8s-testimages/kubekins-e2e
    - name: docker-graph
      mountPath: /docker-graph
    # use memory storage for etcd hostpath in kind cluster
    - name: etcd-data-dir
      mountPath: /mnt/tmpfs/etcd
  volumes:
  - name: modules
    hostPath:
      path: /lib/modules
      type: Directory
  - name: cgroup
    hostPath:
      path: /sys/fs/cgroup
      type: Directory
  - name: docker-root
    emptyDir: {}
  - name: docker-graph
    emptyDir: {}
  - name: etcd-data-dir
    emptyDir:
      medium: Memory
  tolerations:
  - effect: NoSchedule
    key: tidb-operator
    operator: Exists
  affinity:
    nodeAffinity:
      requiredDuringSchedulingIgnoredDuringExecution:
        nodeSelectorTerms:
        - matchExpressions:
          - key: ci.pingcap.com
            operator: In
            values:
            - tidb-operator
    podAntiAffinity:
      preferredDuringSchedulingIgnoredDuringExecution:
      - weight: 100
        podAffinityTerm:
          labelSelector:
            matchExpressions:
            - key: app
              operator: In
              values:
              - tidb-operator-e2e
          topologyKey: kubernetes.io/hostname
'''

uploaderYaml = '''
apiVersion: v1
kind: Pod
metadata:
  labels:
    app: tidb-operator-e2e
spec:
  containers:
  - name: main
    image: hub.pingcap.net/tidb-operator/kubekins-e2e:v20210808-1eaeec7-master
    command:
    - runner.sh
    # Clean containers on TERM signal in root process to avoid cgroup leaking.
    # https://github.com/pingcap/tidb-operator/issues/1603#issuecomment-582402196
    - exec
    - bash
    - -c
    - |
      function clean() {
        echo "info: clean all containers to avoid cgroup leaking"
        docker kill `docker ps -q` || true
        docker system prune -af || true
      }
      function setup_docker_mirror() {
        sed -i "s/mirror.gcr.io/registry-mirror.pingcap.net/g" /etc/default/docker
        service docker restart
      }
      setup_docker_mirror
      trap clean TERM
      sleep 1d & wait
    # we need privileged mode in order to do docker in docker
    securityContext:
      privileged: true
    env:
    - name: DOCKER_IN_DOCKER_ENABLED
      value: "true"
    resources:
      requests:
        cpu: "1"
        memory: "2Gi"
        ephemeral-storage: 15Gi
    # kind needs /lib/modules and cgroups from the host
    volumeMounts:
    - mountPath: /lib/modules
      name: modules
      readOnly: true
    - mountPath: /sys/fs/cgroup
      name: cgroup
    # dind expects /var/lib/docker to be volume
    - name: docker-root
      mountPath: /var/lib/docker
    # legacy docker path for cr.io/k8s-testimages/kubekins-e2e
    - name: docker-graph
      mountPath: /docker-graph
    # use memory storage for etcd hostpath in kind cluster
    - name: etcd-data-dir
      mountPath: /mnt/tmpfs/etcd
  volumes:
  - name: modules
    hostPath:
      path: /lib/modules
      type: Directory
  - name: cgroup
    hostPath:
      path: /sys/fs/cgroup
      type: Directory
  - name: docker-root
    emptyDir: {}
  - name: docker-graph
    emptyDir: {}
  - name: etcd-data-dir
    emptyDir:
      medium: Memory
  tolerations:
  - effect: NoSchedule
    key: tidb-operator
    operator: Exists
  affinity:
    podAntiAffinity:
      preferredDuringSchedulingIgnoredDuringExecution:
      - weight: 100
        podAffinityTerm:
          labelSelector:
            matchExpressions:
            - key: app
              operator: In
              values:
              - tidb-operator-e2e
          topologyKey: kubernetes.io/hostname
'''

dindYaml = '''
apiVersion: v1
kind: Pod
metadata:
  name: dinp
spec:
  containers:
  - name: builder
    image: hub.pingcap.net/jenkins/jenkins-slave-centos7:delivery3
    args: ["-c", "sleep infinity"]
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
  - name: jnlp
    image: jenkins/inbound-agent:4.10-3
'''


goBuildYaml = '''
apiVersion: v1
kind: Pod
metadata:
  name: golang
spec:
  containers:
  - name: builder
    image: hub.pingcap.net/jenkins/centos7_golang-1.16:latest
    args: ["sleep", "infinity"]
'''

def GitHash = ''
def ImageTag = ''
def ReleaseTag = params.GitRef
/*def WhetherUpload = params.GitRef ==~ /^(master|)$/ || params.GitRef ==~ /^(release-.*)$/
        || params.GitRef ==~ /^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-((?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\.(?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?(?:\+([0-9a-zA-Z-]+(?:\.[0-9a-zA-Z-]+)*))?$/}
*/
def WhetherUpload = false
def ARTIFACT_DIR = '_artifacts'
def CHART_ITEMS = 'tidb-operator tidb-cluster tidb-backup tidb-drainer tidb-lightning tikv-importer'

pipeline {
    agent none
    parameters {
        string(name: 'GitRef', defaultValue: 'master', description: 'branch or commit hash')
        string(name: 'DeleteNamespaceOnFailure', defaultValue: 'false', description: 'DeleteNamespaceOnFailure')
        string(name: 'GinkgoNodes', defaultValue: '6', description: 'the number of nodes in e2e test')
        string(name: 'E2eArgs', defaultValue: "--ginkgo.focus='NULL'", description: 'the number of nodes in e2e test')
    }
    stages {
        stage("Build") {
//            when { expression { false } } // todo remove
            agent {
                kubernetes {
                    yaml builderYaml
                    defaultContainer 'main'
                }
            }
            stages {
                stage("Checkout") {
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
                            ImageTag = GitHash[0..6]
                        }
                    }
                }
                stage("Build Bin") {
                    environment { CODECOV_TOKEN = credentials('tp-codecov-token') }
                    steps {
                        sh """#!/bin/bash
                            set -eux
                            echo "info: building"
                            go mod download
                            GOARCH=arm64 make build
                            GOARCH=amd64 make build
                            make e2e-build
                            if [ "${params.GitRef}" == "master" ]; then
                                echo "info: run unit tests and report coverage results for master branch"
                                make test GOFLAGS='-race' GO_COVER=y
                                curl -s https://codecov.io/bash | bash -s - -t \${CODECOV_TOKEN} || echo 'Codecov did not collect coverage reports'
                            fi
                            """
                    }
                }
                stage("Build E2E Docker") {
                    environment { HUB = credentials('TIDB_OPERATOR_HUB_AUTH') }
                    steps {
                        sh """#!/bin/bash
                            set -eu
                            echo "info: logging into hub.pingcap.net"
                            printenv HUB_PSW | docker login -u \$HUB_USR --password-stdin hub.pingcap.net
                            echo "info: build and push images for e2e"
                            NO_BUILD=y DOCKER_REPO=hub.pingcap.net/tidb-operator-e2e ImageTag=${ImageTag} make docker-push e2e-docker-push
                            echo "info: download binaries for e2e"
                            SKIP_BUILD=y SKIP_IMAGE_BUILD=y SKIP_UP=y SKIP_TEST=y SKIP_DOWN=y ./hack/e2e.sh
                            echo "info: change ownerships for jenkins"
                            # we run as root in our pods, this is required
                            # otherwise jenkins agent will fail because of the lack of permission
                            chown -R 1000:1000 .
                            """
                        stash excludes: "vendor/**,deploy/**,tests/**", name: "tidb-operator"
                    }
                }
            }
        }

        stage("E2E Test") {
            when { expression { false } } // todo remove
            agent {
                kubernetes {
                    yaml e2eTestPodYaml
                    defaultContainer 'main'
                    cloud "kubernetes-ng"
                }
            }
            stages {
                stage("recover env") {
                    steps {
                        unstash 'tidb-operator'
                        println "debug command: kubectl -n jenkins-tidb exec -ti ${NODE_NAME} bash"
                        sh """
                            echo "====== shell env ======"
                            echo "pwd: \$(pwd)"
                            env
                            echo "====== go env ======"
                            go env
                            echo "====== docker version ======"
                            docker version
                            """
                    }
                }
                stage("run") {
                    steps {
                        sh """#!/bin/bash
                        export ARTIFACT_DIR=${ARTIFACT_DIR}
                        export RUNNER_SUITE_NAME='tidb-operator'
                        export KIND_ETCD_DATADIR=/mnt/tmpfs/etcd SKIP_BUILD=y SKIP_IMAGE_BUILD=y DOCKER_REPO=hub.pingcap.net/tidb-operator-e2e ImageTag=${ImageTag} DELETE_NAMESPACE_ON_FAILURE=${params.DeleteNamespaceOnFailure} GINKGO_NO_COLOR=y
                        export GinkgoNodes=${params.GinkgoNodes}
                        ./hack/e2e.sh -- ${params.E2eArgs}
                        """
                    }
                }
                stage("artifact") {
                    steps {
                        dir(ARTIFACT_DIR) {
                            sh """#!/bin/bash
                        echo "info: change ownerships for jenkins"
                        chown -R 1000:1000 .
                        echo "info: print total size of artifacts"
                        du -sh .
                        echo "info: list all files"
                        find .
                        echo "info: moving all artifacts into a sub-directory"
                        shopt -s extglob
                        mkdir tidb-operator
                        mv !(tidb-operator) tidb-operator/
                        """
                            archiveArtifacts artifacts: "tidb-operator/**", allowEmptyArchive: true
                            junit testResults: "tidb-operator/*.xml", allowEmptyResults: true, keepLongStdio: true
                        }
                    }
                }
            }
        }

        stage("Upload Assert") {
            when { expression { WhetherUpload } }
            agent {
                kubernetes {
                    yaml uploaderYaml
                    defaultContainer 'main'
                }
            }
            steps {
                unstash 'tidb-operator'
                sh """
                          export BUILD_BRANCH=${GitRef}
                          export GITHASH=${GITHASH}
                          ./ci/upload-binaries-charts.sh
                          """
            }
        }
        stage("Build & release Operator") {
            agent {
                kubernetes {
                    yaml dindYaml
                    defaultContainer 'builder'
                    cloud "kubernetes-ng"
                    namespace "jenkins-tidb-operator"
                }
            }
            stages {
                stage("fetch bin") {
                    steps {
                        sh "curl http://fileserver.pingcap.net/download/pingcap/tidb-operator/builds/${GitHash}/tidb-operator.tar.gz | tar xz"
                        sh 'docker run --rm --privileged multiarch/qemu-user-static:6.1.0-8 --reset'
                        sh 'docker buildx create --name mybuilder --use || true'
                    }
                }
                stage("build and release images") {
                    matrix {
                        axes {
                            axis {
                                name 'TARGET'
                                values 'tidb-operator', 'tidb-backup-manager'
                            }
                        }
                        stages {
                            stage('Build') {
                                steps {
                                    container("dind"){
                                        sh "docker buildx build --platform=linux/arm64,linux/amd64 --push -t pingcap/${TARGET}:${ReleaseTag} images/${TARGET}"
                                    }
                                }
                            }
                            stage('Sync Aliyun') {
                                steps {
                                    println("should sync here")  // todo: really sync
//                                    withDockerRegistry([url: "https://registry.cn-beijing.aliyuncs.com", credentialsId: "ACR_TIDB_ACCOUNT"]) {
//                                        sh "docker buildx build --platform=linux/arm64,linux/amd64 --push -t registry.cn-beijing.aliyuncs.com/tidb/${TARGET}:${ReleaseTag} images/${TARGET}"
//                                    }
                                }
                            }
                        }
                    }
                }
                stage('Publish charts to charts.pingcap.org') {
                    environment {
                        QINIU_ACCESS_KEY = credentials('qn_access_key');
                        QINIU_SECRET_KEY = credentials('qiniu_secret_key')
                    }
                    steps {
                        ansiColor('xterm') {
                            sh """
						set +x
						export QINIU_BUCKET_NAME="charts"
						set -x
						curl https://raw.githubusercontent.com/pingcap/docs-cn/a4db3fc5171ed8e4e705fb34552126a302d29c94/scripts/upload.py -o upload.py
						sed -i 's%https://download.pingcap.org%https://charts.pingcap.org%g' upload.py
						sed -i 's/python3/python/g' upload.py
						chmod +x upload.py
						for chartItem in ${CHART_ITEMS}
						do
							chartPrefixName=\$chartItem-${RELEASE_TAG}
							echo "======= release \$chartItem chart ======"
							sed -i "s/version:.*/version: ${RELEASE_TAG}/g" charts/\$chartItem/Chart.yaml
							sed -i "s/appVersion:.*/appVersion: ${RELEASE_TAG}/g" charts/\$chartItem/Chart.yaml
                            # update image tag to current release
                            sed -r -i "s#pingcap/(tidb-operator|tidb-backup-manager):.*#pingcap/\\1:${RELEASE_TAG}#g" charts/\$chartItem/values.yaml
							tar -zcf \${chartPrefixName}.tgz -C charts \$chartItem
							sha256sum \${chartPrefixName}.tgz > \${chartPrefixName}.sha256
							./upload.py \${chartPrefixName}.tgz \${chartPrefixName}.tgz
							./upload.py \${chartPrefixName}.sha256 \${chartPrefixName}.sha256
						done
						# Generate index.yaml for helm repo if the version is not "latest" (not a valid semantic version)
                        if [ "${RELEASE_TAG}" != "latest" -a "${RELEASE_TAG}" != "nightly" ]; then
                            wget https://get.helm.sh/helm-v2.14.1-linux-amd64.tar.gz
                            tar -zxvf helm-v2.14.1-linux-amd64.tar.gz
                            mv linux-amd64/helm /usr/local/bin/helm
                            chmod +x /usr/local/bin/helm
                            #ls
                            curl http://charts.pingcap.org/index.yaml -o index.yaml
                            cat index.yaml
                            helm repo index . --url http://charts.pingcap.org/ --merge index.yaml
                            cat index.yaml
                            ./upload.py index.yaml index.yaml
                        else
                            echo "info: RELEASE_TAG is ${RELEASE_TAG}, skip adding it into chart index file"
                        fi
						"""
                        }
                    }
                }
            }

        }

        stage("Build & Release Debug Toolkit") {
            stages {
                stage("Build Toolkit") {
                    agent {
                        kubernetes {
                            yaml goBuildYaml
                            defaultContainer 'builder'
                            cloud "kubernetes-ng"
                            namespace "jenkins-tidb-operator"
                        }
                    }
                    steps {
                        checkout changelog: false,
                                poll: false,
                                scm: [
                                        $class                           : 'GitSCM',
                                        branches                         : [[name: "${params.GitRef}"]],
                                        doGenerateSubmoduleConfigurations: false,
                                        userRemoteConfigs                : [[
                                                                                    credentialsId: "github-sre-bot-ssh",
                                                                                    refspec      : '+refs/heads/*:refs/remotes/origin/* +refs/pull/*:refs/remotes/origin/pr/*',
                                                                                    url          : "git@github.com:pingcap/tidb-operator.git",
                                                                            ]]
                                ]
                        sh "make debug-build"
                        script {
                            ["linux", "darwin", "windows"].each {
                                def TKCTL_CLI_PACKAGE = "tkctl-${it}-amd64-${RELEASE_TAG}"
                                sh """
							GOOS=${it} GOARCH=amd64 make cli
							tar -zcf ${TKCTL_CLI_PACKAGE}.tgz tkctl
							sha256sum ${TKCTL_CLI_PACKAGE}.tgz > ${TKCTL_CLI_PACKAGE}.sha256
							"""
                            }
                        }
                        stash excludes: "vendor/**", name: "toolkit"
                    }
                }
                stage("Release Toolkit") {
                    agent {
                        kubernetes {
                            yaml dindYaml
                            defaultContainer 'builder'
                            cloud "kubernetes-ng"
                            namespace "jenkins-tidb-operator"
                        }
                    }
                    steps {
                        deleteDir()
                        unstash 'toolkit'
                        script {
                            println("release bin")
                            ["linux", "darwin", "windows"].each {
                                def TKCTL_CLI_PACKAGE = "tkctl-${it}-amd64-${RELEASE_TAG}"
                                sh """
							upload.py ${TKCTL_CLI_PACKAGE}.tgz ${TKCTL_CLI_PACKAGE}.tgz
							upload.py ${TKCTL_CLI_PACKAGE}.sha256 ${TKCTL_CLI_PACKAGE}.sha256
							"""
                            }

                            println("release docker")
                            ["debug-launcher", "tidb-control", "tidb-debug"].each {
                                docker.build("pingcap/${it}:${RELEASE_TAG}", "misc/images/${it}").push()
                                withDockerRegistry([url: "https://registry.cn-beijing.aliyuncs.com", credentialsId: "ACR_TIDB_ACCOUNT"]) {
                                    println("should sync here")  // todo: really sync// todo: really sync
//                                    sh """
//                                        docker tag pingcap/${it}:${RELEASE_TAG} registry.cn-beijing.aliyuncs.com/tidb/${it}:${RELEASE_TAG}
//                                        docker push registry.cn-beijing.aliyuncs.com/tidb/${it}:${RELEASE_TAG}
//                                        """
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
