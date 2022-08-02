#! /usr/bin/env bash

function main() {    
    local test_suite="$1"
    local timeout="$2"
   
    # Disable pipelined pessimistic lock temporarily until tikv#11649 is resolved
    cat <<EOF > tikv.toml
[pessimistic-txn]
pipelined = false

[raftdb]
max-open-files = 20480

[rocksdb]
max-open-files = 20480
EOF

 local pd_data_base_dir=$(mktemp -d)
    bin/pd-server --name=pd-0 --data-dir=${pd_data_base_dir}/pd-0/data --peer-urls=http://127.0.0.1:2380 --advertise-peer-urls=http://127.0.0.1:2380 --client-urls=http://127.0.0.1:2370 --advertise-client-urls=http://127.0.0.1:2370  --initial-cluster=pd-0=http://127.0.0.1:2380,pd-1=http://127.0.0.1:2381,pd-2=http://127.0.0.1:2382 -force-new-cluster &> pd1.log &
    bin/pd-server --name=pd-1 --data-dir=${pd_data_base_dir}/pd-1/data --peer-urls=http://127.0.0.1:2381 --advertise-peer-urls=http://127.0.0.1:2381 --client-urls=http://127.0.0.1:2371 --advertise-client-urls=http://127.0.0.1:2371  --initial-cluster=pd-0=http://127.0.0.1:2380,pd-1=http://127.0.0.1:2381,pd-2=http://127.0.0.1:2382 -force-new-cluster &> pd2.log &
    bin/pd-server --name=pd-2 --data-dir=${pd_data_base_dir}/pd-2/data --peer-urls=http://127.0.0.1:2382 --advertise-peer-urls=http://127.0.0.1:2382 --client-urls=http://127.0.0.1:2372 --advertise-client-urls=http://127.0.0.1:2372  --initial-cluster=pd-0=http://127.0.0.1:2380,pd-1=http://127.0.0.1:2381,pd-2=http://127.0.0.1:2382 -force-new-cluster &> pd3.log &
    bin/tikv-server --addr=127.0.0.1:20160 --advertise-addr=127.0.0.1:20160 --status-addr=127.0.0.1:20180 --pd=http://127.0.0.1:2370,http://127.0.0.1:2371,http://127.0.0.1:2372 --config=tikv.toml --data-dir=${pd_data_base_dir}/tikv-0/data -f  tikv1.log &
    bin/tikv-server --addr=127.0.0.1:20161 --advertise-addr=127.0.0.1:20161 --status-addr=127.0.0.1:20181 --pd=http://127.0.0.1:2370,http://127.0.0.1:2371,http://127.0.0.1:2372 --config=tikv.toml --data-dir=${pd_data_base_dir}/tikv-1/data -f  tikv2.log &
    bin/tikv-server --addr=127.0.0.1:20162 --advertise-addr=127.0.0.1:20162 --status-addr=127.0.0.1:20182 --pd=http://127.0.0.1:2370,http://127.0.0.1:2371,http://127.0.0.1:2372 --config=tikv.toml --data-dir=${pd_data_base_dir}/tikv-2/data -f  tikv3.log &

    sleep 10
    export log_level=error
    make failpoint-enable

    go test ./tests/realtikvtest/${test_suite} -v -with-real-tikv -timeout ${timeout:=30m}
}

function cleanup() {
    killall -9 -r -q tikv-server
    killall -9 -r -q pd-server
}

exit_code=0
{ # try block
    main "$@"
} || { # catch block
   exit_code="$?"  # exit code of last command which is 44
}
# finally block:
cleanup

if [[ "exit_code" != '0' ]]; then
   exit ${exit_code}
fi 