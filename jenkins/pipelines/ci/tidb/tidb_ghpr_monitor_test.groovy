echo "release test: ${params.containsKey("release_test")}"
if (params.containsKey("release_test")) {
    ghprbTargetBranch = params.getOrDefault("release_test__ghpr_target_branch", params.release_test__release_branch)
    ghprbCommentBody = params.getOrDefault("release_test__ghpr_comment_body", "")
    ghprbActualCommit = params.getOrDefault("release_test__ghpr_actual_commit", params.release_test__tidb_commit)
    ghprbPullId = params.getOrDefault("release_test__ghpr_pull_id", "")
    ghprbPullTitle = params.getOrDefault("release_test__ghpr_pull_title", "")
    ghprbPullLink = params.getOrDefault("release_test__ghpr_pull_link", "")
    ghprbPullDescription = params.getOrDefault("release_test__ghpr_pull_description", "")
}

def TIKV_BRANCH = ghprbTargetBranch
def PD_BRANCH = ghprbTargetBranch

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

try {
    stage('TiDB Monitor Test') {
        node("test_tidb_monitor") {
            container("golang") {
                def ws = pwd()
                deleteDir()

                println "debug command:\nkubectl -n jenkins-tidb exec -ti ${NODE_NAME} bash"

                def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/tidb-server.tar.gz"
                def tidb_done_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/done"
                timeout(10) {
                    sh """
                    tikv_sha1=`curl "${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_BRANCH}/sha1"`
                    tikv_url="${FILE_SERVER_URL}/download/builds/pingcap/tikv/\${tikv_sha1}/centos7/tikv-server.tar.gz"

                    pd_sha1=`curl "${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1"`
                    pd_url="${FILE_SERVER_URL}/download/builds/pingcap/pd/\${pd_sha1}/centos7/pd-server.tar.gz"


                    while ! curl --output /dev/null --silent --head --fail \${tikv_url}; do sleep 1; done
                    curl \${tikv_url} | tar xz

                    while ! curl --output /dev/null --silent --head --fail \${pd_url}; do sleep 1; done
                    curl \${pd_url} | tar xz

                    mkdir -p ./tidb-src
                    while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
                    curl ${tidb_url} | tar xz -C ./tidb-src

                    mv tidb-src/bin/tidb-server ./bin/tidb-server

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
                    cat <<EOF > grafana-6.4.5/conf/provisioning/dashboards/tidb.yaml
apiVersion: 1
providers:
  - name: 'tidb'
    orgId: 1
    folder: ''
    folderUid: ''
    type: file
    options:
      path: ${ws}/tidb-src/metrics/grafana
EOF
                    """
                    sh """
                    cd prometheus-2.15.2.linux-amd64
                    ./prometheus > ../prometheus.log &
                    cd ../grafana-6.4.5
                    cat <<EOF > conf/provisioning/datasources/tidb.yaml
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
    app: grafana-expose-tidb
  name: grafana-expose-tidb-pr-${ghprbPullId}
spec:
  ports:
  - protocol: TCP
    port: 3000
  selector:
    jenkins/label: "test_tidb_monitor"
    pr: "tidb-${ghprbPullId}"
  sessionAffinity: None
  type: NodePort
EOF
                    """
                    sh """
                    ./kubectl -n jenkins-tidb get service | grep "grafana-expose-tidb-pr-${ghprbPullId} " > /dev/null && ./kubectl -n jenkins-tidb delete service grafana-expose-tidb-pr-${ghprbPullId}
                    ./kubectl -n jenkins-tidb label pod ${NODE_NAME} --overwrite pr="tidb-${ghprbPullId}"
                    ./kubectl -n jenkins-tidb apply -f service.yaml
                    sleep 3
                    """
                    withCredentials([string(credentialsId: 'sre-bot-token', variable: 'TOKEN')]) {
                        sh"""
                        port=`./kubectl -n jenkins-tidb get service | grep  "grafana-expose-tidb-pr-${ghprbPullId} " | awk '{print \$5}' | cut -d: -f2 | cut -d/ -f1`
                        echo service port: \${port}
                        ./comment-pr --token=$TOKEN --owner=pingcap --repo=tidb --number=${ghprbPullId} --comment="Visit the grafana server at: http://172.16.5.5:\${port}, it will last for 5 hours"
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
                    ./kubectl -n jenkins-tidb delete service grafana-expose-tidb-pr-${ghprbPullId}
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

stage("upload status"){
    node{
        sh """curl --connect-timeout 2 --max-time 4 -d '{"job":"$JOB_NAME","id":$BUILD_NUMBER}' http://172.16.5.13:36000/api/v1/ci/job/sync || true"""
    }
}

stage('Summary') {
    echo "finished"
}
