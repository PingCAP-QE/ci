#! /usr/bin/env bash

function main() {
    # Disable pipelined pessimistic lock temporarily until tikv#11649 is resolved
    cat <<EOF > tikv.toml
[pessimistic-txn]
pipelined = false
EOF

    local pd_peer_addr1="127.0.0.1:2378"
    local pd_peer_addr2="127.0.0.1:2388"
    local pd_peer_addr3="127.0.0.1:2398"
    local pd_addr1="127.0.0.1:2379"
    local pd_addr2="127.0.0.1:2389"
    local pd_addr3="127.0.0.1:2399"

    bin/pd-server -name=pd1 --data-dir=pd1 --client-urls=http://${pd_addr1} --peer-urls=http://${pd_peer_addr1} -force-new-cluster &> pd1.log &
    bin/pd-server -name=pd2 --data-dir=pd2 --client-urls=http://${pd_addr2} --peer-urls=http://${pd_peer_addr1} -force-new-cluster &> pd2.log &
    bin/pd-server -name=pd3 --data-dir=pd3 --client-urls=http://${pd_addr3} --peer-urls=http://${pd_peer_addr3} -force-new-cluster &> pd3.log &
    bin/tikv-server --pd=${pd_addr1} -s tikv1 --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 --advertise-status-addr=127.0.0.1:20165 -C tikv.toml -f tikv1.log &
    bin/tikv-server --pd=${pd_addr2} -s tikv2 --addr=0.0.0.0:20170 --advertise-addr=127.0.0.1:20170 --advertise-status-addr=127.0.0.1:20175 -C tikv.toml -f tikv2.log &
    bin/tikv-server --pd=${pd_addr3} -s tikv3 --addr=0.0.0.0:20180 --advertise-addr=127.0.0.1:20180 --advertise-status-addr=127.0.0.1:20185 -C tikv.toml -f tikv3.log &

    if [ -d "cmd/explaintest" ]; then
        chmod +x cmd/explaintest/run-tests.sh

        export TIDB_SERVER_PATH="$(pwd)/bin/explain_test_tidb-server"
        export TIKV_PATH="${tikv_addr1}"
        pushd cmd/explaintest &&
            ./run-tests.sh -s "${TIDB_SERVER_PATH}" -d "$@" &&
        popd
    else
        chmod +x tests/integrationtest/run-tests.sh

        export TIDB_SERVER_PATH="$(pwd)/bin/integration_test_tidb-server"
        export TIKV_PATH="${tikv_addr1}"
        pushd tests/integrationtest &&
            ./run-tests.sh -s "${TIDB_SERVER_PATH}" -d "$@" &&
        popd
    fi
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
