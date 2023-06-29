def get_commit_hash = { prj, branch_or_hash ->
    if (branch_or_hash.length() == 40) {
        return branch_or_hash
    }

    def hash = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/${prj}/${branch_or_hash}/sha1").trim()
    return hash
}

catchError {
    def label = "${env.JOB_NAME}-${BUILD_NUMBER}"
    podTemplate(name: label , label: label, instanceCap: 5, idleMinutes: 0, nodeSelector: "kubernetes.io/arch=amd64", containers: [
        containerTemplate(name: 'golang', image: 'hub.pingcap.net/jenkins/centos7_tpcc:test', privileged: true,
            ttyEnabled: true, command: 'cat', resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi'),
    ]) {
        node(label) {
            println "debug command: kubectl -n jenkins-tidb exec -ti ${NODE_NAME} bash"
            container('golang') {
                def ws = pwd()
                stage('Prepare') {
                    sh """
                    set +e
                    killall -9 -r -q br
                    killall -9 -r -q cdc
                    killall -9 -r -q pump
                    killall -9 -r -q drainer
                    killall -9 -r -q tiflash
                    killall -9 -r -q tidb-server
                    killall -9 -r -q tikv-server
                    killall -9 -r -q pd-server
                    rm -rf /tmp/tidb
                    set -e
                    """
                    deleteDir()

                    timeout(10) {
                        def tidb_sha1 = get_commit_hash("tidb", TIDB_BRANCH_OR_COMMIT)
                        def tikv_sha1 = get_commit_hash("tikv", TIKV_BRANCH_OR_COMMIT)
                        def pd_sha1 = get_commit_hash("pd", PD_BRANCH_OR_COMMIT)
                        def binlog_sha1 = get_commit_hash("tidb-binlog", BINLOG_BRANCH_OR_COMMIT)
                        def br_sha1 = BR_BRANCH_AND_COMMIT
                        if (br_sha1 == "master" || br_sha1 == "") {
                            br_sha1 = "master/" + get_commit_hash("br", "master")
                        }
                        def cdc_sha1 = get_commit_hash("ticdc", TICDC_BRANCH_OR_COMMIT)
                        def tools_sha1 = get_commit_hash("tidb-tools", TOOLS_BRANCH_OR_COMMIT)
                        def tiflash_branch_sha1 = TIFLASH_BRANCH_AND_COMMIT
                        if (tiflash_branch_sha1 == "master" || tiflash_branch_sha1 == "") {
                            tiflash_branch_sha1 = "master/" + get_commit_hash("tiflash", "master")
                        }
                        sh """
                        curl -C - --retry 3 -f ${FILE_SERVER_URL}/download/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz | tar xz bin
                        curl -C - --retry 3 -f ${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz | tar xz
                        curl -C - --retry 3 -f ${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz | tar xz
                        curl -C - --retry 3 -f ${FILE_SERVER_URL}/download/builds/pingcap/tidb-binlog/${binlog_sha1}/centos7/tidb-binlog.tar.gz | tar xz
                        curl -C - --retry 3 -f ${FILE_SERVER_URL}/download/builds/pingcap/br/${br_sha1}/centos7/br.tar.gz | tar xz
                        curl -C - --retry 3 -f ${FILE_SERVER_URL}/download/builds/pingcap/tiflow/${cdc_sha1}/centos7/tiflow-linux-amd64.tar.gz | tar xz
                        curl -C - --retry 3 -f ${FILE_SERVER_URL}/download/builds/pingcap/tidb-tools/${tools_sha1}/centos7/tidb-tools.tar.gz | tar xz bin
                        curl -C - --retry 3 -f ${FILE_SERVER_URL}/download/builds/pingcap/tiflash/${tiflash_branch_sha1}/centos7/tiflash.tar.gz | tar xz
                        cp -R /home/jenkins/bin/go-tpc bin/
                        """
                    }
                }

                def start_tidb_cluster = { i, enable_binlog -> 
                    def pd_port = 2379 + 10 * i
                    def peer_port = 2378 + 10 * i
                    sh """
                    cat > pd.toml << __EOF__
name = "pd-${pd_port}"
data-dir = "pd-${pd_port}"
client-urls = "http://127.0.0.1:${pd_port}"
peer-urls = "http://127.0.0.1:${peer_port}"
initial-cluster-state = "new"

[replication]
max-replicas = 1
enable-placement-rules = true

__EOF__

                    """
                    def tikv_port = 20160 + 10 * i
                    def status_port = 20165 + 10 * i
                    sh """
                    ${ws}/bin/pd-server --config ./pd.toml -force-new-cluster &> pd.log &
                    ${ws}/bin/tikv-server --pd=127.0.0.1:${pd_port} -s tikv-${tikv_port} --addr=0.0.0.0:${tikv_port} --advertise-addr=127.0.0.1:${tikv_port} --status-addr=0.0.0.0:${status_port} -f ./tikv.log &
                    sleep 5
                    """
                    pump_port = 8250 + 10 * i
                    def tidb_status_port = 10800 + i
                    def tidb_port = 4000 + i
                    if (enable_binlog) {
                        sh """
                        ${ws}/bin/pump -addr 127.0.0.1:${pump_port} -pd-urls  http://127.0.0.1:${pd_port} -log-file pump.log &
                        sleep 5
                        ${ws}/bin/tidb-server --store tikv --path=127.0.0.1:${pd_port} -P ${tidb_port} -enable-binlog -status ${tidb_status_port} --log-file ./tidb.log &
                        sleep 5
                        """
                    } else {
                        sh """
                        ${ws}/bin/tidb-server --store tikv --path=127.0.0.1:${pd_port} -P ${tidb_port} -status ${tidb_status_port} --log-file ./tidb.log &
                        sleep 5
                        """
                    }
                    sh """
                    sleep 30
                    """
                    
                    sh """
                    mysql -uroot -h 127.0.0.1 -P ${tidb_port} -e "UPDATE mysql.tidb SET VARIABLE_VALUE = '720h' WHERE VARIABLE_NAME = 'tikv_gc_life_time';"
                    """
                    def tiflash_tcp_port = 9110 + i
                    def tiflash_http_port = 8223 + i
                    def tiflash_service_addr = 5030 + i
                    def tiflash_metrics_port = 8234 + i
                    sh """
                        cat > tiflash.toml << __EOF__
default_profile = "default"
display_name = "TiFlash"
listen_host = "0.0.0.0"
mark_cache_size = 5368709120
tmp_path = "tiflash.tmp"
path = "tiflash.data"
tcp_port = ${tiflash_tcp_port}
http_port = ${tiflash_http_port}

[flash]
tidb_status_addr = "127.0.0.1:${tidb_status_port}"
service_addr = "127.0.0.1:${tiflash_service_addr}"

[flash.flash_cluster]
cluster_manager_path = "${ws}/tiflash/flash_cluster_manager"
log = "logs/tiflash_cluster_manager.log"
master_ttl = 60
refresh_interval = 20
update_rule_interval = 5

[status]
metrics_port = ${tiflash_metrics_port}

[logger]
errorlog = "logs/tiflash_error.log"
log = "logs/tiflash.log"
count = 20
level = "debug"
size = "1000M"

[application]
runAsDaemon = true

[raft]
pd_addr = "127.0.0.1:${pd_port}"
storage_engine = "tmt"

[quotas]

[quotas.default]

[quotas.default.interval]
duration = 3600
errors = 0
execution_time = 0
queries = 0
read_rows = 0
result_rows = 0

[users]

[users.default]
password = ""
profile = "default"
quota = "default"

[users.default.networks]
ip = "::/0"

[users.readonly]
password = ""
profile = "readonly"
quota = "default"

[users.readonly.networks]
ip = "::/0"

[profiles]

[profiles.default]
load_balancing = "random"
max_memory_usage = 10000000000
use_uncompressed_cache = 0

[profiles.readonly]
readonly = 1
__EOF__
                        """
                        sh """
                        LD_LIBRARY_PATH=${ws}/tiflash ${ws}/tiflash/tiflash server --config-file ./tiflash.toml &
                        sleep 5
                        """

                }

                def gen_sync_diff_conf = { source, target ->
                    sh """
                    cat > sync_diff.toml << __EOF__
log-level = "warn"
check-thread-count = 4
sample-percent = 10

[[check-tables]]
schema = "test"
tables = ["~.*"]

[[source-db]]
    host = "127.0.0.1"
    port = ${source}
    user = "root"
    instance-id = "source-1"

[target-db]
    host = "127.0.0.1"
    port = ${target}
    user = "root"
    instance-id = "target-1"
__EOF__
                    """
                }

                stage('Start Clusters') {
                    // strat TiDB cluster
                    dir('cluster-source') {
                        start_tidb_cluster(0, true)
                    }

                    dir('cluster-cdc') {
                        start_tidb_cluster(1, false)
                        gen_sync_diff_conf(4000, 4001)
                        sh """
                        ../bin/cdc server --log-level=info --pd http://127.0.0.1:2379 &
                        ../bin/cdc cli changefeed create --pd http://127.0.0.1:2379 --sink-uri mysql://root@127.0.0.1:4001/?max-txn-row=5000
                        """
                    }

                    dir("cluster-binlog") {
                        sh """
                        cat > drainer.toml << __EOF__
addr = "127.0.0.1:8249"
pd-urls = "http://127.0.0.1:2379"

[syncer]
ignore-schemas = "INFORMATION_SCHEMA,PERFORMANCE_SCHEMA,mysql"

[syncer.to]
host = "127.0.0.1"
user = "root"
port = 4002
__EOF__
                        """
                        start_tidb_cluster(2, false)
                        gen_sync_diff_conf(4000, 4002)
                        sh """
                        ../bin/drainer --config drainer.toml -log-file drainer.log & 
                        """
                    }
                }

                stage('Run TPCC Test') {
                    sh """
                    ./bin/go-tpc tpcc --warehouses 4 prepare
                    ./bin/go-tpc tpcc --warehouses 4 --time 600s run
                    """
                }

                stage('BR Full Backup & Restore') {
                    dir('cluster-br') {
                        start_tidb_cluster(3, false)
                        gen_sync_diff_conf(4000, 4003)
                        sh """
                        mkdir backup-full
                        ${ws}/bin/br backup full --pd 127.0.0.1:2379 -s "local://${ws}/cluster-br/backup-full"
                        ${ws}/bin/br restore full --pd 127.0.0.1:2409 -s "local://${ws}/cluster-br/backup-full"
                        ${ws}/bin/br validate decode --field end-version --pd 127.0.0.1:2379 -s local://${ws}/cluster-br/backup-full > backup_full_ts
                        ${ws}/bin/sync_diff_inspector -config ./sync_diff.toml
                        """
                    }
                }

                stage('Addational Tpcc 1min') {
                    sh"""
                    ./bin/go-tpc tpcc --warehouses 4 --time 600s run
                    """
                }

                stage('BR Delta Backup & Restore') {
                    dir('cluster-br') {
                        sh """
                        mkdir backup-delta
                        last_ts=`cat backup_full_ts`
                        ../bin/br backup full --pd 127.0.0.1:2379 -s "local://${ws}/cluster-br/backup-delta" --lastbackupts \${last_ts}
                        ../bin/br restore full --pd 127.0.0.1:2409 -s "local://${ws}/cluster-br/backup-delta"
                        ../bin/sync_diff_inspector -config ./sync_diff.toml
                        """
                    }
                }

                stage("Check Binlog") {
                    dir("cluster-binlog") {
                        timeout(20) {
                            sh """
                            end_ts=`../bin/br validate decode --field end-version --pd 127.0.0.1:2379 -s local://${ws}/cluster-br/backup-delta`
                            binlog_ts=`../bin/binlogctl -pd-urls=http://127.0.0.1:2379  -cmd drainers | grep -E -o "MaxCommitTS: [0-9]+"  | awk '{print \$2}'`
                            while [[ "\$binlog_ts" -le "\$end_ts" ]]; do
                                sleep 5
                                binlog_ts=`../bin/binlogctl -pd-urls=http://127.0.0.1:2379  -cmd drainers | grep -E -o "MaxCommitTS: [0-9]+"  | awk '{print \$2}'`
                            done
                            """
                        }
                        sh """
                        ../bin/sync_diff_inspector -config ./sync_diff.toml
                        """
                    }
                }

                stage("Check CDC") {
                    dir("cluster-cdc") {
                        // wait cdc finish
                        timeout(120) {
                            sh """
                            end_ts=`../bin/br validate decode --field end-version --pd 127.0.0.1:2379 -s local://${ws}/cluster-br/backup-delta`
                            cdc_ts=`curl http://127.0.0.1:8300/debug/info | grep -E -o "CheckpointTs:[0-9]+" | cut -d: -f2`
                            while [[ "\$cdc_ts" -le "\$end_ts" ]]; do
                                sleep 5
                                cdc_ts=`curl http://127.0.0.1:8300/debug/info | grep -E -o "CheckpointTs:[0-9]+" | cut -d: -f2`
                            done
                            """
                        }
                        sh """
                        ../bin/sync_diff_inspector -config ./sync_diff.toml
                        """
                    }
                }

            }
        }
    }

    currentBuild.result = "SUCCESS"
}

stage('Summary') {
    echo "finished"
}
