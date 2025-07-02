
def podYaml = '''
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: docker
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
  - name: uploader
    image: hub.pingcap.net/jenkins/uploader
    args: ["sleep", "infinity"]
'''

pipeline {
    agent {
        kubernetes {
            yaml podYaml
            nodeSelector "kubernetes.io/arch=amd64"
            defaultContainer 'docker'
            namespace 'jenkins-cd'
        }
    }
    options {
        timeout(time: 40, unit: 'MINUTES')
    }
    stages {
        stage ("package binary") {
            steps{
                sh label: 'package binary', script: """
                    mkdir -p tidb-$TARBALL_NAME/bin
                    docker pull hub.pingcap.net/$TIDB_TAG
                    docker run --rm hub.pingcap.net/$TIDB_TAG -V
                    docker run --rm --volume \$(pwd)/tidb-$TARBALL_NAME/bin:/mount --entrypoint sh hub.pingcap.net/$TIDB_TAG -c "cp /tidb-server /mount/tidb-server && chown 1000:1000 /mount/tidb-server"
                    docker pull hub.pingcap.net/$TIKV_TAG
                    docker run --rm hub.pingcap.net/$TIKV_TAG -V
                    docker run --rm --volume \$(pwd)/tidb-$TARBALL_NAME/bin:/mount --entrypoint sh hub.pingcap.net/$TIKV_TAG -c "cp /tikv-server /mount/tikv-server && chown 1000:1000 /mount/tikv-server"
                    docker pull hub.pingcap.net/$PD_TAG
                    docker run --rm hub.pingcap.net/$PD_TAG -V
                    docker run --rm --volume \$(pwd)/tidb-$TARBALL_NAME/bin:/mount --entrypoint sh hub.pingcap.net/$PD_TAG -c "cp /pd-server /pd-ctl /mount && chown 1000:1000 /mount/pd-server /mount/pd-ctl"
                    tar -zcf 'tidb-${TARBALL_NAME}.tar.gz' tidb-${TARBALL_NAME}
                """
            }
        }
        stage ("Upload") {
            environment {
                MINIO_URL = credentials('qa-minio-url')
                MINIO_ACCESS_KEY = credentials('qa-minio-access-key')
                MINIO_SECRET_KEY = credentials('qa-minio-secret-key')
            }
            steps{
                container("uploader") {
                    sh label: 'upload to minio', script: """
                        curl $MINIO_URL/tp-team/tools/mc-linux-amd64.tar.gz | tar -xz
                        ./mc config host add idc $MINIO_URL $MINIO_ACCESS_KEY $MINIO_SECRET_KEY
                        ./mc cp 'tidb-${TARBALL_NAME}.tar.gz' idc/tp-team/tests/jepsen/
                    """
                }
            }
        }

        stage ("package failpoint binary") {
            steps{
                sh label: 'package binary', script: """
                    mkdir -p tidb-$FAILPOINT_TARBALL_NAME/bin
                    docker pull hub.pingcap.net/$TIDB_FAILPOINT_TAG
                    docker run --rm hub.pingcap.net/$TIDB_FAILPOINT_TAG -V
                    docker run --rm --volume \$(pwd)/tidb-$FAILPOINT_TARBALL_NAME/bin:/mount --entrypoint sh hub.pingcap.net/$TIDB_FAILPOINT_TAG -c "cp /tidb-server /mount/tidb-server && chown 1000:1000 /mount/tidb-server"
                    docker pull hub.pingcap.net/$TIKV_FAILPOINT_TAG
                    docker run --rm hub.pingcap.net/$TIKV_FAILPOINT_TAG -V
                    docker run --rm --volume \$(pwd)/tidb-$FAILPOINT_TARBALL_NAME/bin:/mount --entrypoint sh hub.pingcap.net/$TIKV_FAILPOINT_TAG -c "cp /tikv-server /mount/tikv-server && chown 1000:1000 /mount/tikv-server"
                    docker pull hub.pingcap.net/$PD_FAILPOINT_TAG
                    docker run --rm hub.pingcap.net/$PD_FAILPOINT_TAG -V
                    docker run --rm --volume \$(pwd)/tidb-$FAILPOINT_TARBALL_NAME/bin:/mount --entrypoint sh hub.pingcap.net/$PD_FAILPOINT_TAG -c "cp /pd-server /pd-ctl /mount && chown 1000:1000 /mount/pd-server /mount/pd-ctl"
                    tar -zcf 'tidb-${FAILPOINT_TARBALL_NAME}.tar.gz' tidb-${FAILPOINT_TARBALL_NAME}
                """
            }
        }
        stage ("Upload failpoint") {
            environment {
                MINIO_URL = credentials('qa-minio-url')
                MINIO_ACCESS_KEY = credentials('qa-minio-access-key')
                MINIO_SECRET_KEY = credentials('qa-minio-secret-key')
            }
            steps{
                container("uploader") {
                    sh label: 'upload to minio', script: """
                        curl $MINIO_URL/tp-team/tools/mc-linux-amd64.tar.gz | tar -xz
                        ./mc config host add idc $MINIO_URL $MINIO_ACCESS_KEY $MINIO_SECRET_KEY
                        ./mc cp 'tidb-${FAILPOINT_TARBALL_NAME}.tar.gz' idc/tp-team/tests/jepsen/
                    """
                }
            }
        }
    }
}
