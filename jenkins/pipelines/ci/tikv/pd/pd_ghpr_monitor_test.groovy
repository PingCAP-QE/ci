echo "release test: ${params.containsKey("release_test")}"
if (params.containsKey("release_test")) {
    ghprbTargetBranch = params.getOrDefault("release_test__ghpr_target_branch", params.release_test__release_branch)
    ghprbCommentBody = params.getOrDefault("release_test__ghpr_comment_body", "")
    ghprbActualCommit = params.getOrDefault("release_test__ghpr_actual_commit", params.release_test__pd_commit)
    ghprbPullId = params.getOrDefault("release_test__ghpr_pull_id", "")
    ghprbPullTitle = params.getOrDefault("release_test__ghpr_pull_title", "")
    ghprbPullLink = params.getOrDefault("release_test__ghpr_pull_link", "")
    ghprbPullDescription = params.getOrDefault("release_test__ghpr_pull_description", "")
}

def TIKV_BRANCH = ghprbTargetBranch
def TIDB_BRANCH = ghprbTargetBranch

// parse tidb branch
def m3 = ghprbCommentBody =~ /tidb\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m3) {
    TIDB_BRANCH = "${m3[0][1]}"
}
m3 = null
println "TIDB_BRANCH=${TIDB_BRANCH}"

// parse tikv branch
def m1 = ghprbCommentBody =~ /tikv\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m1) {
    TIKV_BRANCH = "${m1[0][1]}"
}
m1 = null
println "TIKV_BRANCH=${TIKV_BRANCH}"


label = "${JOB_NAME}-${BUILD_NUMBER}"
def run_with_pod(Closure body) {
    def cloud = "kubernetes"
    def namespace = "jenkins-pd"
    def pod_go_docker_image = 'hub.pingcap.net/jenkins/centos7_golang-1.13:cached-pigz'
    def jnlp_docker_image = "jenkins/inbound-agent:3148.v532a_7e715ee3-10"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 10,
            nodeSelector: "kubernetes.io/arch=amd64",
            containers: [
                    containerTemplate(
                            name: 'golang', alwaysPullImage: false,
                            image: "${pod_go_docker_image}", ttyEnabled: true,
                            resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
                            command: '/bin/sh -c', args: 'cat',
                            envVars: [containerEnvVar(key: 'GOPATH', value: '/go')],

                    )
            ],
            volumes: [
                    emptyDirVolume(mountPath: '/tmp', memory: false),
                    emptyDirVolume(mountPath: '/home/jenkins', memory: false)
                    ],
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${namespace} exec -ti ${NODE_NAME} bash"
            body()
        }
    }
}

def specStr = "+refs/heads/*:refs/remotes/origin/*"
if (ghprbPullId != null && ghprbPullId != "") {
    specStr = "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*"
}

try {
    stage('PD Monitor Test') {
        run_with_pod {
            container("golang") {
                def ws = pwd()
                deleteDir()

                stage("Checkout") {
                    // fetch source
                    dir("/home/jenkins/agent/git/pd") {
                        if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                            deleteDir()
                        }
                        checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: ghprbActualCommit ]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:tikv/pd.git']]]
                        sh """
                        git status
                        """
                    }
                }

                def pd_url = "${FILE_SERVER_URL}/download/builds/pingcap/pd/pr/${ghprbActualCommit}/centos7/pd-server.tar.gz"
                def pd_sha1_file = "${FILE_SERVER_URL}/download/refs/pingcap/pd/pr/${ghprbPullId}/sha1"
                timeout(10) {
                    sh """
                    tidb_sha1=`curl "${FILE_SERVER_URL}/download/refs/pingcap/tidb/${TIDB_BRANCH}/sha1"`
                    tidb_url="${FILE_SERVER_URL}/download/builds/pingcap/tidb/\${tidb_sha1}/centos7/tidb-server.tar.gz"

                    tikv_sha1=`curl "${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_BRANCH}/sha1"`
                    tikv_url="${FILE_SERVER_URL}/download/builds/pingcap/tikv/\${tikv_sha1}/centos7/tikv-server.tar.gz"


                    while ! curl --output /dev/null --silent --head --fail \${tikv_url}; do sleep 1; done
                    curl \${tikv_url} | tar xz

                    while ! curl --output /dev/null --silent --head --fail ${pd_url}; do sleep 1; done
                    curl ${pd_url} | tar xz

                    mkdir -p ./tidb-src
                    while ! curl --output /dev/null --silent --head --fail \${tidb_url}; do sleep 1; done
                    curl \${tidb_url} | tar xz -C ./tidb-src

                    mv tidb-src/bin/tidb-server ./bin/tidb-server

                    rm -rf metrics
                    cp -R /home/jenkins/agent/git/pd/metrics .

                    curl ${FILE_SERVER_URL}/download/bin/prometheus-2.15.2.linux-amd64.tar.gz | tar xz
                    curl ${FILE_SERVER_URL}/download/bin/grafana-6.4.5.linux-amd64.tar.gz | tar xz
                    curl -O ${FILE_SERVER_URL}/download/bin/kubectl
                    chmod +x kubectl
                    curl -O ${FILE_SERVER_URL}/download/comment-pr
                    chmod +x comment-pr
                    """
                }

                // run tests

                try {
                    sh """
                    set +e
                    killall -9 -r -q tidb-server
                    killall -9 -r -q tikv-server
                    killall -9 -r -q pd-server
                    killall -9 -r -q prometheus
                    killall -9 -r -q grafana-server
                    rm -rf ./tikv ./pd
                    set -e

                    bin/pd-server --name=pd --data-dir=pd &>pd.log &
                    sleep 5
                    bin/tikv-server --pd=127.0.0.1:2379 -s tikv --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv.log &
                    sleep 5
                    bin/tidb-server --store tikv --path=127.0.0.1:2379 --log-file ./tidb.log &

                    cat <<EOF >> prometheus-2.15.2.linux-amd64/prometheus.yml
  - job_name: tidb
    honor_labels: true
    scrape_interval: 5s
    scrape_timeout: 5s
    metrics_path: /metrics
    scheme: http
    static_configs:
    - targets:
      - 127.0.0.1:10080
  - job_name: tikv
    honor_labels: true
    scrape_interval: 5s
    scrape_timeout: 3s
    metrics_path: /metrics
    scheme: http
    static_configs:
    - targets:
      - 127.0.0.1:20180
  - job_name: pd
    honor_labels: true
    scrape_interval: 5s
    scrape_timeout: 3s
    metrics_path: /metrics
    scheme: http
    static_configs:
    - targets:
      - 127.0.0.1:2379
EOF
                    cat <<EOF > grafana-6.4.5/conf/provisioning/dashboards/pd.yaml
apiVersion: 1
providers:
  - name: 'pd'
    orgId: 1
    folder: ''
    folderUid: ''
    type: file
    options:
      path: ${ws}/metrics/grafana
EOF
                    """
                    sh """
                    cd prometheus-2.15.2.linux-amd64
                    ./prometheus > ../prometheus.log &
                    cd ../grafana-6.4.5
                    cat <<EOF > conf/provisioning/datasources/pd.yaml
apiVersion: 1
datasources:
  - access: proxy
    editable: true
    name: \\\$\\\${DS_TEST-CLUSTER}
    orgId: 1
    type: prometheus
    url: http://localhost:9090
    version: 1
EOF
                    bin/grafana-server > ../grafana.log &
                    cd ..
                    """
                    sh """
                    cat <<EOF > service.yaml
apiVersion: v1
kind: Service
metadata:
  labels:
    app: grafana-expose-pd
  name: grafana-expose-pd-pr-${ghprbPullId}
spec:
  ports:
  - protocol: TCP
    port: 3000
  selector:
    jenkins/label: "${label}"
    pr: "pd-${ghprbPullId}"
  sessionAffinity: None
  type: NodePort
EOF
                    """
                    sh """
                    ./kubectl -n jenkins-pd get service | grep "grafana-expose-pd-pr-${ghprbPullId} " > /dev/null && ./kubectl -n jenkins-pd delete service grafana-expose-pd-pr-${ghprbPullId}
                    ./kubectl -n jenkins-pd label pod ${NODE_NAME} --overwrite pr="pd-${ghprbPullId}"
                    ./kubectl -n jenkins-pd apply -f service.yaml
                    sleep 3
                    """
                    withCredentials([string(credentialsId: 'sre-bot-token', variable: 'TOKEN')]) {
                        sh"""
                        port=`./kubectl -n jenkins-pd get service | grep  "grafana-expose-pd-pr-${ghprbPullId} " | awk '{print \$5}' | cut -d: -f2 | cut -d/ -f1`
                        echo service port: \${port}
                        ./comment-pr --token=$TOKEN --owner=tikv --repo=pd --number=${ghprbPullId} --comment="Visit the grafana server at: http://172.16.5.21:\${port}, it will last for 5 hours"
                        """
                    }
                    sh "sleep 18000"
                } catch (err) {
                    sh """
                    set +e
                    cat ${ws}/tidb.log
                    cat ${ws}/tikv.log || true
                    cat ${ws}/pd.log || true
                    cat ${ws}/prometheus.log
                    cat ${ws}/grafana.log
                    """
                    throw err
                } finally {
                    sh """
                    set +e
                    ./kubectl -n jenkins-pd delete service grafana-expose-pd-pr-${ghprbPullId}
                    killall -9 -r tidb-server
                    killall -9 -r tikv-server
                    killall -9 -r pd-server
                    killall -9 -r prometheus
                    killall -9 -r grafana-server
                    """
                }
            }
        }
    }

    currentBuild.result = "SUCCESS"
}catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
    println e
    // this ambiguous condition means a user probably aborted
    currentBuild.result = "ABORTED"
} catch (hudson.AbortException e) {
    println e
    // this ambiguous condition means during a shell step, user probably aborted
    if (e.getMessage().contains('script returned exit code 143')) {
        currentBuild.result = "ABORTED"
    } else {
        currentBuild.result = "FAILURE"
    }
} catch (InterruptedException e) {
    println e
    currentBuild.result = "ABORTED"
}
catch (Exception e) {
    if (e.getMessage().equals("hasBeenTested")) {
        currentBuild.result = "SUCCESS"
    } else {
        currentBuild.result = "FAILURE"
        slackcolor = 'danger'
        echo "${e}"
    }
}



stage('Summary') {
    echo "finished"
}
