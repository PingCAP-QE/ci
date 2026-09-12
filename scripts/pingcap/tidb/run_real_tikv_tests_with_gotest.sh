#! /usr/bin/env bash

function wait_for_pd_cluster() {
    local url="$1"
    local expected_members="$2"
    local name="$3"
    local response
    local healthy_members

    for attempt in $(seq 1 30); do
        response="$(curl -fsS --max-time 2 "${url}" 2>/dev/null || true)"
        healthy_members="$(printf '%s' "${response}" | grep -o '"health"[[:space:]]*:[[:space:]]*true' | wc -l | tr -d '[:space:]')"
        if [[ "${healthy_members}" == "${expected_members}" ]]; then
            echo "${name} is ready"
            return 0
        fi
        echo "Waiting for ${name}... (attempt ${attempt}/30)"
        sleep 1
    done

    echo "${name} did not become ready: ${url}; last response: ${response:-<unavailable>}" >&2
    return 1
}

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

    data_base_dir="$(mktemp -d /tmp/tidb-real-tikv-data.XXXXXX)" || { echo "failed to create data dir" >&2; return 1; }
    mkdir -p "${data_base_dir}"/{pd-0,pd-1,pd-2,tikv-0,tikv-1,tikv-2}
    bin/pd-server --name=pd-0 --data-dir="${data_base_dir}/pd-0/data" --peer-urls=http://127.0.0.1:2380 --advertise-peer-urls=http://127.0.0.1:2380 --client-urls=http://127.0.0.1:2379 --advertise-client-urls=http://127.0.0.1:2379 --initial-cluster=pd-0=http://127.0.0.1:2380,pd-1=http://127.0.0.1:2381,pd-2=http://127.0.0.1:2383 --force-new-cluster &> pd1.log &
    bin/pd-server --name=pd-1 --data-dir="${data_base_dir}/pd-1/data" --peer-urls=http://127.0.0.1:2381 --advertise-peer-urls=http://127.0.0.1:2381 --client-urls=http://127.0.0.1:2382 --advertise-client-urls=http://127.0.0.1:2382 --initial-cluster=pd-0=http://127.0.0.1:2380,pd-1=http://127.0.0.1:2381,pd-2=http://127.0.0.1:2383 --force-new-cluster &> pd2.log &
    bin/pd-server --name=pd-2 --data-dir="${data_base_dir}/pd-2/data" --peer-urls=http://127.0.0.1:2383 --advertise-peer-urls=http://127.0.0.1:2383 --client-urls=http://127.0.0.1:2384 --advertise-client-urls=http://127.0.0.1:2384 --initial-cluster=pd-0=http://127.0.0.1:2380,pd-1=http://127.0.0.1:2381,pd-2=http://127.0.0.1:2383 --force-new-cluster &> pd3.log &
    wait_for_pd_cluster "http://127.0.0.1:2379/pd/api/v1/health" 3 "PD cluster" || return 1

    bin/tikv-server --addr=127.0.0.1:20160 --advertise-addr=127.0.0.1:20160 --status-addr=127.0.0.1:20180 --pd=http://127.0.0.1:2379,http://127.0.0.1:2382,http://127.0.0.1:2384 --config=tikv.toml --data-dir="${data_base_dir}/tikv-0/data" -f tikv1.log &
    bin/tikv-server --addr=127.0.0.1:20161 --advertise-addr=127.0.0.1:20161 --status-addr=127.0.0.1:20181 --pd=http://127.0.0.1:2379,http://127.0.0.1:2382,http://127.0.0.1:2384 --config=tikv.toml --data-dir="${data_base_dir}/tikv-1/data" -f tikv2.log &
    bin/tikv-server --addr=127.0.0.1:20162 --advertise-addr=127.0.0.1:20162 --status-addr=127.0.0.1:20182 --pd=http://127.0.0.1:2379,http://127.0.0.1:2382,http://127.0.0.1:2384 --config=tikv.toml --data-dir="${data_base_dir}/tikv-2/data" -f tikv3.log &

    sleep 10
    go test ./tests/realtikvtest/${test_suite} -v --tags=intest  -with-real-tikv -timeout 40m
}

function cleanup() {
    killall -9 -r -q tikv-server
    killall -9 -r -q pd-server
    [[ -n "${data_base_dir:-}" ]] && rm -rf "${data_base_dir}"
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
