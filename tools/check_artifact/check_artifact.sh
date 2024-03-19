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
for com in 'br' 'cdc' 'ctl' 'dm-master' 'dm-worker' 'dmctl' 'drainer' 'dumpling' 'grafana' 'grafana' 'pd' 'pd-recover' 'prometheus' 'prometheus' 'pump' 'tidb' 'tidb-lightning' 'tiflash' 'tikv' ;
do
    echo "check tiup $com:$VERSION"
    platforms=$(tiup list $com | grep $VERSION)
    echo $platforms
    echo $platforms | grep "darwin/amd64" | grep "darwin/arm64" | grep "linux/amd64" |grep -q "linux/arm64"
    record_failure $?
done

# check docker
for com in 'br' 'dm' 'dumpling' 'ng-monitoring' 'pd' 'ticdc' 'tidb' 'tidb-binlog' 'tidb-lightning' 'tidb-monitor-initializer' 'tiflash' 'tikv' ;
do
    echo "check docker $com:$VERSION"
    oras manifest fetch hub.pingcap.net/qa/$com:$VERSION | grep -q 'application/vnd.docker.distribution.manifest.list'
    record_failure $?
    echo "check docker enterprise $com:$VERSION"
    oras manifest fetch hub.pingcap.net/qa/$com-enterprise:$VERSION | grep -q 'application/vnd.docker.distribution.manifest.list'
    record_failure $?
    echo "check gcr $com:$VERSION"
    oras manifest fetch gcr.io/pingcap-public/dbaas/$com:$VERSION | grep -q 'application/vnd.docker.distribution.manifest.list'
    record_failure $?
done

# check failpoint
for com in 'pd' 'tidb' 'tikv';
do
    echo "check docker failpoint $com:$VERSION-failpoint"
    oras manifest fetch hub.pingcap.net/qa/$com:$VERSION-failpoint | grep -q 'application/vnd.docker.distribution.manifest'
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
