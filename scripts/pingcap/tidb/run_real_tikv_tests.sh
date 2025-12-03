#! /usr/bin/env bash

function main() {
    local test_suite="$1"
    local timeout="$2"

    # fast exit when no target existing
    if !make -n ${test_suite} 1>/dev/null; then
        echo "⚠️🏃Skip for non-existing target..."
        exit 0
    fi

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
    bin/pd-server --name=pd-0 --data-dir=/home/jenkins/.tiup/data/T9Z9nII/pd-0/data --peer-urls=http://127.0.0.1:2380 --advertise-peer-urls=http://127.0.0.1:2380 --client-urls=http://127.0.0.1:2379 --advertise-client-urls=http://127.0.0.1:2379 --initial-cluster=pd-0=http://127.0.0.1:2380,pd-1=http://127.0.0.1:2381,pd-2=http://127.0.0.1:2383 --force-new-cluster &> pd1.log &
    bin/pd-server --name=pd-1 --data-dir=/home/jenkins/.tiup/data/T9Z9nII/pd-1/data --peer-urls=http://127.0.0.1:2381 --advertise-peer-urls=http://127.0.0.1:2381 --client-urls=http://127.0.0.1:2382 --advertise-client-urls=http://127.0.0.1:2382 --initial-cluster=pd-0=http://127.0.0.1:2380,pd-1=http://127.0.0.1:2381,pd-2=http://127.0.0.1:2383 --force-new-cluster &> pd2.log &
    bin/pd-server --name=pd-2 --data-dir=/home/jenkins/.tiup/data/T9Z9nII/pd-2/data --peer-urls=http://127.0.0.1:2383 --advertise-peer-urls=http://127.0.0.1:2383 --client-urls=http://127.0.0.1:2384 --advertise-client-urls=http://127.0.0.1:2384 --initial-cluster=pd-0=http://127.0.0.1:2380,pd-1=http://127.0.0.1:2381,pd-2=http://127.0.0.1:2383 --force-new-cluster &> pd3.log &
    bin/tikv-server --addr=127.0.0.1:20160 --advertise-addr=127.0.0.1:20160 --status-addr=127.0.0.1:20180 --pd=http://127.0.0.1:2379,http://127.0.0.1:2382,http://127.0.0.1:2384 --config=tikv.toml --data-dir=/home/jenkins/.tiup/data/T9Z9nII/tikv-0/data -f tikv1.log &
    bin/tikv-server --addr=127.0.0.1:20161 --advertise-addr=127.0.0.1:20161 --status-addr=127.0.0.1:20181 --pd=http://127.0.0.1:2379,http://127.0.0.1:2382,http://127.0.0.1:2384 --config=tikv.toml --data-dir=/home/jenkins/.tiup/data/T9Z9nII/tikv-1/data -f tikv2.log &
    bin/tikv-server --addr=127.0.0.1:20162 --advertise-addr=127.0.0.1:20162 --status-addr=127.0.0.1:20182 --pd=http://127.0.0.1:2379,http://127.0.0.1:2382,http://127.0.0.1:2384 --config=tikv.toml --data-dir=/home/jenkins/.tiup/data/T9Z9nII/tikv-2/data -f tikv3.log &

    sleep 10
    make ${test_suite}
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

if [[ "$exit_code" != '0' ]]; then
   exit ${exit_code}
fi
