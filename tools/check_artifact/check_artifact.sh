VERSION=$1

failure=0

function record_failure(){
    if [ "$1" -eq 0 ];then 
        echo "success"
    else
        echo "failure"
        failure=1
    fi
}

# check tiup
for com in tidb tikv tiflash pd ctl grafana prometheus pd-recover tidb-lightning dumpling cdc dm-worker dm-master dmctl br grafana prometheus pump drainer ;
do
    echo "check tiup $com:$VERSION"
    platforms=$(tiup list $com | grep $VERSION)
    echo $platforms
    echo $platforms | grep "darwin/amd64" | grep "darwin/arm64" | grep "linux/amd64" |grep -q "linux/arm64"
    record_failure $?
done

# check docker
for com in "dumpling" "br" "ticdc" "tidb-binlog" "tiflash" "tidb" "tikv" "pd" "dm" "tidb-lightning" "tidb-monitor-initializer" "ng-monitoring";
do
    echo "check docker $com:$VERSION"
    oras manifest fetch hub.pingcap.net/qa/$com:$VERSION | grep -q 'application/vnd.docker.distribution.manifest.list'
    record_failure $?
    echo "check docker enterprise $com:$VERSION"
    oras manifest fetch hub.pingcap.net/qa/$com-enterprise:$VERSION | grep -q 'application/vnd.docker.distribution.manifest.list'
    record_failure $?
done

if [ $failure -eq 0 ]; then
    echo '======='
    echo "check success"
    exit 0
else
    echo '======='
    echo "check failure"
    exit 1
fi
